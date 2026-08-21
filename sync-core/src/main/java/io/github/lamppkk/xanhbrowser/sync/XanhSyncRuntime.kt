package io.github.lamppkk.xanhbrowser.sync

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import mozilla.appservices.fxaclient.DeviceConfig
import mozilla.appservices.fxaclient.FirefoxAccount
import mozilla.appservices.fxaclient.FxaClient
import mozilla.appservices.fxaclient.FxaConfig
import mozilla.appservices.fxaclient.FxaEvent
import mozilla.appservices.fxaclient.FxaServer
import mozilla.appservices.fxaclient.FxaState
import mozilla.appservices.logins.DatabaseLoginsStorage
import mozilla.appservices.logins.Login
import mozilla.appservices.logins.LoginEntry
import mozilla.appservices.logins.createKey
import mozilla.appservices.logins.createStaticKeyManager
import mozilla.appservices.places.PlacesApi
import mozilla.appservices.places.BookmarkRoot
import mozilla.appservices.places.uniffi.BookmarkItem
import mozilla.appservices.places.uniffi.VisitObservation
import mozilla.appservices.places.uniffi.VisitType
import mozilla.appservices.remotetabs.ClientRemoteTabs
import mozilla.appservices.remotetabs.RemoteTabRecord
import mozilla.appservices.remotetabs.TabsStore
import mozilla.appservices.sync15.DeviceType
import mozilla.appservices.syncmanager.DeviceSettings
import mozilla.appservices.syncmanager.ServiceStatus
import mozilla.appservices.syncmanager.SyncAuthInfo
import mozilla.appservices.syncmanager.SyncEngineSelection
import mozilla.appservices.syncmanager.SyncManager
import mozilla.appservices.syncmanager.SyncParams

class XanhSyncRuntime(
    context: Context,
    private val configuration: SyncConfiguration,
) : Closeable {
    private val appContext = context.applicationContext
    private val secureState = SecureStateStore(appContext)
    private val vaultKeyStore = VaultKeyStore(appContext)
    private val lifecycle = RuntimeLifecycleGate()
    private val syncInFlight = AtomicBoolean(false)
    private val enabledEngines = SyncEngine.entries.toMutableSet()
    private val schedule = SyncSchedule(
        lastSyncEpochSeconds = secureState.get(LAST_SYNC)?.toLongOrNull(),
        nextSyncAllowedEpochSeconds = secureState.get(NEXT_ALLOWED)?.toLongOrNull(),
    )
    private lateinit var placesApi: PlacesApi
    private lateinit var tabs: TabsStore
    private lateinit var syncManager: SyncManager
    private var registryLease: ApplicationServicesRuntimeRegistry.Lease? = null
    private var logins: DatabaseLoginsStorage? = null
    private var pendingLoginsClose: DatabaseLoginsStorage? = null
    private val persistCallback = object : FxaClient.PersistCallback {
        override fun persist(data: String) = persistAccount(data)
    }
    private lateinit var fxaClient: FxaClient
    private var accountState = AccountState.DISCONNECTED
    private var status = SyncStatus.IDLE
    private var vaultUnlockedAtEpochSeconds: Long? = null

    init {
        configuration.validate()
        val lease = ApplicationServicesRuntimeRegistry.acquire()
        registryLease = lease
        try {
            appContext.getDatabasePath("xanh-places.sqlite").parentFile?.mkdirs()
            placesApi = PlacesApi(appContext.getDatabasePath("xanh-places.sqlite").absolutePath)
            tabs = TabsStore(appContext.getDatabasePath("xanh-tabs.sqlite").absolutePath)
            syncManager = SyncManager()
            val configurationIdentity = configuration.persistenceIdentity()
            if (secureState.get(CONFIG_IDENTITY) != configurationIdentity) {
                // Serialized FirefoxAccount state embeds its server. Never reuse it
                // after changing Accounts/Token endpoints, client ID, or redirect.
                secureState.remove(ACCOUNT_STATE, SYNC_STATE, LAST_SYNC, NEXT_ALLOWED)
                secureState.put(CONFIG_IDENTITY, configurationIdentity)
            }
            val saved = secureState.get(ACCOUNT_STATE)
            fxaClient = if (saved != null) {
                FxaClient(FirefoxAccount.fromJson(saved), persistCallback)
            } else {
                FxaClient(configuration.toFxaConfig(), persistCallback)
            }
            placesApi.registerWithSyncManager()
            tabs.registerWithSyncManager()
            accountState = fxaClient.processEvent(
                FxaEvent.Initialize(DeviceConfig(configuration.deviceName, DeviceType.MOBILE, emptyList())),
            ).toAccountState()
        } catch (error: Throwable) {
            val cleanupFailure = closeNativeResources()
            if (cleanupFailure == null) {
                registryLease = null
                lease.close()
            } else {
                // Fail closed: a native engine that did not close cleanly may
                // still occupy Application Services' global registry.
                error.addSuppressed(cleanupFailure)
            }
            throw error
        }
    }

    fun snapshot(): SyncSnapshot = lifecycle.withOpen {
        expireVault(System.currentTimeMillis() / 1_000)
        SyncSnapshot(
            accountState,
            status,
            enabledEngines.toSet(),
            schedule.lastSyncEpochSeconds,
            schedule.nextSyncAllowedEpochSeconds,
            logins != null,
        )
    }

    fun accountDomain(): String = configuration.accountDomain()

    fun setEngineEnabled(engine: SyncEngine, enabled: Boolean) = lifecycle.withOpen {
        if (enabled) enabledEngines += engine else enabledEngines -= engine
    }

    /** Returns the PKCE OAuth URL to open in a system Custom Tab. */
    fun beginOAuth(): String = lifecycle.withOpen {
        val next = fxaClient.processEvent(
            FxaEvent.BeginOAuthFlow(
                service = "",
                scopes = listOf(SYNC_SCOPE, PROFILE_SCOPE),
                entrypoint = "xanh-browser-sync",
            ),
        )
        accountState = next.toAccountState()
        (next as? FxaState.Authenticating)?.oauthUrl
            ?: error("Unexpected Firefox Accounts state: $next")
    }

    /** Completes OAuth only when both code and state came from our redirect. */
    fun completeOAuth(code: String, state: String): AccountState = lifecycle.withOpen {
        require(code.isNotBlank() && state.isNotBlank())
        accountState = fxaClient.processEvent(FxaEvent.CompleteOAuthFlow(code, state)).toAccountState()
        accountState
    }

    fun recordLocalChange(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000) = lifecycle.withOpen {
        schedule.localChangeEpochSeconds = nowEpochSeconds
    }

    fun isSyncDue(reason: SyncReason, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean =
        lifecycle.withOpen {
            schedule.due(reason, nowEpochSeconds)
        }

    /**
     * Opens the password store after the host has completed BiometricPrompt or
     * device-credential authentication. Never call this from background work.
     */
    fun unlockVault(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000) = lifecycle.withOpen {
        check(pendingLoginsClose == null) { "Password store cleanup is incomplete" }
        val existingKey = if (vaultKeyStore.hasWrappedKey()) {
            runCatching(vaultKeyStore::unwrap).getOrNull()
        } else null
        val loginsKey = existingKey ?: run {
            // A restored database cannot be decrypted if its device-bound key
            // was lost or invalidated. Remove only the unreadable local copy;
            // the next authenticated Sync restores server records.
            logins?.close()
            logins = null
            appContext.deleteDatabase("xanh-logins.sqlite")
            vaultKeyStore.delete()
            createKey().also(vaultKeyStore::wrap)
        }
        logins?.close()
        logins = null
        val opened = DatabaseLoginsStorage(
            appContext.getDatabasePath("xanh-logins.sqlite").absolutePath,
            createStaticKeyManager(loginsKey),
        )
        try {
            opened.registerWithSyncManager()
            logins = opened
        } catch (error: Throwable) {
            try {
                opened.close()
            } catch (closeError: Throwable) {
                // Keep the failed handle reachable so close() can retry and
                // the process-wide registry lease remains fail-closed.
                pendingLoginsClose = opened
                error.addSuppressed(closeError)
            }
            throw error
        }
        vaultUnlockedAtEpochSeconds = nowEpochSeconds
    }

    fun touchVault(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean = lifecycle.withOpen {
        expireVault(nowEpochSeconds)
        if (logins == null) false else {
            vaultUnlockedAtEpochSeconds = nowEpochSeconds
            true
        }
    }

    fun lockVault() = lifecycle.withOpen {
        logins?.close()
        logins = null
        vaultUnlockedAtEpochSeconds = null
    }

    fun setLocalTabs(localTabs: List<RemoteTabRecord>) = lifecycle.withOpen {
        tabs.setLocalTabs(localTabs.filterNot { it.urlHistory.firstOrNull()?.startsWith("about:private") == true })
    }

    fun remoteTabs(): List<ClientRemoteTabs> = lifecycle.withOpen {
        tabs.getAll()
    }

    fun listLogins(): List<Login> = lifecycle.withOpen {
        expireVault(System.currentTimeMillis() / 1_000)
        logins?.list().orEmpty()
    }

    fun addLogin(entry: LoginEntry) = lifecycle.withOpen {
        expireVault(System.currentTimeMillis() / 1_000)
        checkNotNull(logins) { "Password vault is locked" }.add(entry)
    }

    fun updateLogin(id: String, entry: LoginEntry) = lifecycle.withOpen {
        expireVault(System.currentTimeMillis() / 1_000)
        checkNotNull(logins) { "Password vault is locked" }.update(id, entry)
    }

    fun deleteLogin(id: String) = lifecycle.withOpen {
        expireVault(System.currentTimeMillis() / 1_000)
        checkNotNull(logins) { "Password vault is locked" }.delete(id)
    }

    fun touchLogin(id: String) = lifecycle.withOpen {
        expireVault(System.currentTimeMillis() / 1_000)
        checkNotNull(logins) { "Password vault is locked" }.touch(id)
    }

    fun recordHistory(url: String, title: String, visitedAtMillis: Long) = lifecycle.withOpen {
        placesApi.getWriter().noteObservation(
            VisitObservation(
                url = url,
                title = title,
                visitType = VisitType.LINK,
                at = visitedAtMillis,
            ),
        )
    }

    fun saveBookmark(url: String, title: String) = lifecycle.withOpen {
        val writer = placesApi.getWriter()
        if (writer.getBookmarksWithURL(url).isEmpty()) {
            writer.createBookmarkItem(BookmarkRoot.Mobile.id, url, title.ifBlank { url })
        }
    }

    fun deleteHistory(url: String) = lifecycle.withOpen {
        placesApi.getWriter().deleteVisitsFor(url)
    }

    fun deleteBookmark(url: String) = lifecycle.withOpen {
        val writer = placesApi.getWriter()
        writer.getBookmarksWithURL(url).forEach { item ->
            val bookmark = item as? BookmarkItem.Bookmark ?: return@forEach
            writer.deleteBookmarkNode(bookmark.b.guid)
        }
    }

    fun clearHistory() = lifecycle.withOpen {
        placesApi.getWriter().deleteEverything()
    }

    fun importLegacyData(
        bookmarks: List<LegacyBookmark>,
        history: List<LegacyHistoryVisit>,
    ): MigrationCounts = lifecycle.withOpen {
        PlacesMigration(placesApi).import(bookmarks, history)
    }

    fun placesMirror(limit: Int): PlacesMirror = lifecycle.withOpen {
        require(limit in 1..2_000) { "Places mirror limit is out of range" }
        val reader = placesApi.openReader()
        try {
            val visits = reader.getVisitPage(0, limit.toLong(), emptyList()).map { visit ->
                PlacesHistoryRecord(visit.url, visit.title.orEmpty(), visit.timestamp)
            }
            val bookmarks = reader.getRecentBookmarks(limit).mapNotNull { item ->
                (item as? BookmarkItem.Bookmark)?.b?.let { bookmark ->
                    PlacesBookmarkRecord(
                        bookmark.url,
                        bookmark.title.orEmpty(),
                        bookmark.dateAdded,
                    )
                }
            }
            PlacesMirror(visits, bookmarks)
        } finally {
            reader.close()
        }
    }

    fun sync(
        reason: SyncReason,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
    ): SyncSnapshot {
        check(syncInFlight.compareAndSet(false, true)) { "A sync is already running" }
        return try {
            lifecycle.withOpen {
                check(accountState == AccountState.CONNECTED) { "Firefox Account is not connected" }
                schedule.nextSyncAllowedEpochSeconds?.let {
                    check(nowEpochSeconds >= it) { "Server backoff is active" }
                }
                expireVault(nowEpochSeconds)
                status = SyncStatus.RUNNING
                try {
                    val token = fxaClient.getAccessToken(SYNC_SCOPE, false)
                    val scopedKey = requireNotNull(token.key) { "Sync scoped key missing" }
                    val selected = enabledEngines
                        .filterNot { it == SyncEngine.PASSWORDS && logins == null }
                        .map(SyncEngine::serviceName)
                    val result = syncManager.sync(
                        SyncParams(
                            reason = reason.toMozillaReason(),
                            engines = SyncEngineSelection.Some(selected),
                            enabledChanges = emptyMap(),
                            localEncryptionKeys = emptyMap(),
                            authInfo = SyncAuthInfo(
                                scopedKey.kid,
                                token.token,
                                scopedKey.k,
                                fxaClient.getTokenServerEndpointURL().also {
                                    require(it.startsWith("https://")) { "Token server must use HTTPS" }
                                },
                            ),
                            persistedState = secureState.get(SYNC_STATE),
                            deviceSettings = DeviceSettings(
                                fxaDeviceId = fxaClient.getCurrentDeviceId(),
                                name = configuration.deviceName,
                                kind = DeviceType.MOBILE,
                            ),
                        ),
                    )
                    secureState.put(SYNC_STATE, result.persistedState)
                    status = result.toSyncStatus()
                    schedule.lastSyncEpochSeconds = nowEpochSeconds
                    schedule.nextSyncAllowedEpochSeconds = result.nextSyncAllowedAt?.epochSecond
                    schedule.localChangeEpochSeconds = null
                    secureState.put(LAST_SYNC, nowEpochSeconds.toString())
                    result.nextSyncAllowedAt?.epochSecond?.let { secureState.put(NEXT_ALLOWED, it.toString()) }
                        ?: secureState.remove(NEXT_ALLOWED)
                } catch (error: Throwable) {
                    status = when {
                        error.javaClass.simpleName.contains("Auth", ignoreCase = true) -> SyncStatus.AUTH_ERROR
                        else -> SyncStatus.NETWORK_ERROR
                    }
                    if (status == SyncStatus.AUTH_ERROR) accountState = AccountState.AUTH_ISSUES
                    throw error
                }
                snapshot()
            }
        } finally {
            syncInFlight.set(false)
        }
    }

    fun disconnect(deleteLocalData: Boolean) = lifecycle.withOpen {
        if (deleteLocalData) {
            pendingLoginsClose?.let { pending ->
                // A failed registration may still own a native database
                // handle. Never delete its file until a retry closes it.
                pending.close()
                pendingLoginsClose = null
            }
            // Wipe an unlocked login store before its device-bound key is removed.
            // A locked store is deleted below after all handles are closed.
            logins?.wipeLocal()
        }
        lockVault()
        fxaClient.disconnect()
        syncManager.disconnect()
        secureState.remove(ACCOUNT_STATE, SYNC_STATE, LAST_SYNC, NEXT_ALLOWED)
        accountState = AccountState.DISCONNECTED
        status = SyncStatus.IDLE
        if (deleteLocalData) {
            placesApi.getWriter().deleteEverything()
            placesApi.getWriter().deleteAllBookmarks()
            vaultKeyStore.delete()
            secureState.clear()
            appContext.deleteDatabase("xanh-logins.sqlite")
            tabs.closeConnection()
            appContext.deleteDatabase("xanh-tabs.sqlite")
        }
    }

    override fun close() {
        lifecycle.close {
            val cleanupFailure = closeNativeResources()
            if (cleanupFailure == null) {
                registryLease?.close()
                registryLease = null
            }
            // A partially closed Application Services engine may still be in
            // its process-global registry. RuntimeLifecycleGate keeps the
            // lease and fails closed when cleanupFailure is non-null.
            cleanupFailure
        }
    }

    private fun closeNativeResources(): Throwable? {
        var failure: Throwable? = null
        fun closeResource(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }

        closeResource { logins?.close() }
        logins = null
        closeResource { pendingLoginsClose?.close() }
        pendingLoginsClose = null
        vaultUnlockedAtEpochSeconds = null
        if (::fxaClient.isInitialized) closeResource(fxaClient::close)
        if (::syncManager.isInitialized) closeResource(syncManager::close)
        if (::tabs.isInitialized) closeResource(tabs::close)
        if (::placesApi.isInitialized) closeResource(placesApi::close)
        return failure
    }

    private fun persistAccount(value: String) = secureState.put(ACCOUNT_STATE, value)

    private fun expireVault(nowEpochSeconds: Long) {
        val last = vaultUnlockedAtEpochSeconds ?: return
        if (nowEpochSeconds - last >= VaultKeyStore.VAULT_TIMEOUT_SECONDS) lockVault()
    }

    private fun SyncConfiguration.toFxaConfig(): FxaConfig = when (val value = server) {
        AccountServer.Mozilla -> FxaConfig(FxaServer.Release, clientId, redirectUri, null)
        is AccountServer.SelfHosted -> FxaConfig(
            FxaServer.Custom(value.accountsUrl),
            clientId,
            redirectUri,
            value.tokenServerUrl,
        )
    }

    private fun FxaState.toAccountState(): AccountState = when (this) {
        FxaState.Connected -> AccountState.CONNECTED
        is FxaState.Authenticating -> AccountState.AUTHENTICATING
        FxaState.AuthIssues -> AccountState.AUTH_ISSUES
        else -> AccountState.DISCONNECTED
    }

    private fun SyncReason.toMozillaReason(): mozilla.appservices.syncmanager.SyncReason = when (this) {
        SyncReason.STARTUP -> mozilla.appservices.syncmanager.SyncReason.STARTUP
        SyncReason.MANUAL -> mozilla.appservices.syncmanager.SyncReason.USER
        SyncReason.SCHEDULED, SyncReason.LOCAL_CHANGE -> mozilla.appservices.syncmanager.SyncReason.SCHEDULED
        SyncReason.PRE_SLEEP -> mozilla.appservices.syncmanager.SyncReason.PRE_SLEEP
    }

    private fun mozilla.appservices.syncmanager.SyncResult.toSyncStatus(): SyncStatus = when {
        status == ServiceStatus.OK && failures.isEmpty() -> SyncStatus.SUCCESS
        status == ServiceStatus.OK -> SyncStatus.PARTIAL
        status == ServiceStatus.AUTH_ERROR -> SyncStatus.AUTH_ERROR
        status == ServiceStatus.BACKED_OFF -> SyncStatus.BACKED_OFF
        status == ServiceStatus.NETWORK_ERROR || status == ServiceStatus.SERVICE_ERROR -> SyncStatus.NETWORK_ERROR
        else -> SyncStatus.PARTIAL
    }

    companion object {
        private const val SYNC_SCOPE = "https://identity.mozilla.com/apps/oldsync"
        private const val PROFILE_SCOPE = "profile"
        private const val ACCOUNT_STATE = "account_state"
        private const val SYNC_STATE = "sync_state"
        private const val LAST_SYNC = "last_sync"
        private const val NEXT_ALLOWED = "next_allowed"
        private const val CONFIG_IDENTITY = "configuration_identity"

        /** Removes device-local Sync state after all runtime handles are closed. */
        fun deleteLocalData(context: Context) {
            val appContext = context.applicationContext
            var failure: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try {
                    block()
                } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else first.addSuppressed(error)
                }
            }

            cleanup { SecureStateStore(appContext).clear() }
            cleanup { VaultKeyStore(appContext).delete() }
            listOf("xanh-places.sqlite", "xanh-tabs.sqlite", "xanh-logins.sqlite").forEach { name ->
                cleanup {
                    val database = appContext.getDatabasePath(name)
                    val files = listOf(
                        database,
                        File("${database.path}-wal"),
                        File("${database.path}-shm"),
                        File("${database.path}-journal"),
                    )
                    val existed = files.any(File::exists)
                    check(!existed || appContext.deleteDatabase(name)) {
                        "Failed to delete local Sync database $name"
                    }
                    check(files.none(File::exists)) {
                        "Local Sync database files remain for $name"
                    }
                }
            }
            failure?.let { throw it }
        }

        /** False means a constructor/cleanup path still owns the process-wide
         * Application Services registry and destructive recovery must wait for
         * process restart. No native handle is exposed. */
        fun isProcessRegistryAvailable(): Boolean = ApplicationServicesRuntimeRegistry.isAvailable()
    }
}
