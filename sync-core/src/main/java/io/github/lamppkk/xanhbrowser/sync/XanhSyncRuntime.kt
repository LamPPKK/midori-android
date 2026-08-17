package io.github.lamppkk.xanhbrowser.sync

import android.content.Context
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock
import mozilla.appservices.fxaclient.DeviceConfig
import mozilla.appservices.fxaclient.FirefoxAccount
import mozilla.appservices.fxaclient.FxaClient
import mozilla.appservices.fxaclient.FxaConfig
import mozilla.appservices.fxaclient.FxaEvent
import mozilla.appservices.fxaclient.FxaServer
import mozilla.appservices.fxaclient.FxaState
import mozilla.appservices.logins.DatabaseLoginsStorage
import mozilla.appservices.logins.createKey
import mozilla.appservices.logins.createStaticKeyManager
import mozilla.appservices.places.PlacesApi
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
    private val lock = ReentrantLock()
    private val syncInFlight = AtomicBoolean(false)
    private val enabledEngines = SyncEngine.entries.toMutableSet()
    private val schedule = SyncSchedule(
        lastSyncEpochSeconds = secureState.get(LAST_SYNC)?.toLongOrNull(),
        nextSyncAllowedEpochSeconds = secureState.get(NEXT_ALLOWED)?.toLongOrNull(),
    )
    private val places = PlacesApi(appContext.getDatabasePath("xanh-places.sqlite").absolutePath)
    private val tabs = TabsStore(appContext.getDatabasePath("xanh-tabs.sqlite").absolutePath)
    private val syncManager = SyncManager()
    private var logins: DatabaseLoginsStorage? = null
    private val persistCallback = object : FxaClient.PersistCallback {
        override fun persist(data: String) = persistAccount(data)
    }
    private var fxaClient: FxaClient
    private var accountState = AccountState.DISCONNECTED
    private var status = SyncStatus.IDLE
    private var vaultUnlockedAtEpochSeconds: Long? = null

    init {
        configuration.validate()
        appContext.getDatabasePath("xanh-places.sqlite").parentFile?.mkdirs()
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
        places.registerWithSyncManager()
        tabs.registerWithSyncManager()
        accountState = fxaClient.processEvent(
            FxaEvent.Initialize(DeviceConfig(configuration.deviceName, DeviceType.MOBILE, emptyList())),
        ).toAccountState()
    }

    fun snapshot(): SyncSnapshot = lock.withLock {
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

    fun setEngineEnabled(engine: SyncEngine, enabled: Boolean) = lock.withLock {
        if (enabled) enabledEngines += engine else enabledEngines -= engine
    }

    /** Returns the PKCE OAuth URL to open in a system Custom Tab. */
    fun beginOAuth(): String = lock.withLock {
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
    fun completeOAuth(code: String, state: String): AccountState = lock.withLock {
        require(code.isNotBlank() && state.isNotBlank())
        accountState = fxaClient.processEvent(FxaEvent.CompleteOAuthFlow(code, state)).toAccountState()
        accountState
    }

    fun recordLocalChange(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000) = lock.withLock {
        schedule.localChangeEpochSeconds = nowEpochSeconds
    }

    fun isSyncDue(reason: SyncReason, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean =
        lock.withLock { schedule.due(reason, nowEpochSeconds) }

    /**
     * Opens the password store after the host has completed BiometricPrompt or
     * device-credential authentication. Never call this from background work.
     */
    fun unlockVault(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000) = lock.withLock {
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
        logins = DatabaseLoginsStorage(
            appContext.getDatabasePath("xanh-logins.sqlite").absolutePath,
            createStaticKeyManager(loginsKey),
        ).also { it.registerWithSyncManager() }
        vaultUnlockedAtEpochSeconds = nowEpochSeconds
    }

    fun touchVault(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean = lock.withLock {
        expireVault(nowEpochSeconds)
        if (logins == null) false else {
            vaultUnlockedAtEpochSeconds = nowEpochSeconds
            true
        }
    }

    fun lockVault() = lock.withLock {
        logins?.close()
        logins = null
        vaultUnlockedAtEpochSeconds = null
    }

    fun setLocalTabs(localTabs: List<RemoteTabRecord>) = lock.withLock {
        tabs.setLocalTabs(localTabs.filterNot { it.urlHistory.firstOrNull()?.startsWith("about:private") == true })
    }

    fun remoteTabs(): List<ClientRemoteTabs> = lock.withLock { tabs.getAll() }

    fun places(): PlacesApi = places

    fun loginsOrNull(): DatabaseLoginsStorage? = lock.withLock {
        expireVault(System.currentTimeMillis() / 1_000)
        logins
    }

    fun sync(
        reason: SyncReason,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
    ): SyncSnapshot {
        check(syncInFlight.compareAndSet(false, true)) { "A sync is already running" }
        return try {
            lock.withLock {
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

    fun disconnect(deleteLocalData: Boolean) = lock.withLock {
        if (deleteLocalData) {
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
            places.getWriter().deleteEverything()
            places.getWriter().deleteAllBookmarks()
            vaultKeyStore.delete()
            secureState.clear()
            appContext.deleteDatabase("xanh-logins.sqlite")
            tabs.closeConnection()
            appContext.deleteDatabase("xanh-tabs.sqlite")
        }
    }

    override fun close() = lock.withLock {
        lockVault()
        fxaClient.close()
        syncManager.close()
        tabs.close()
        places.close()
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
            SecureStateStore(appContext).clear()
            VaultKeyStore(appContext).delete()
            listOf("xanh-places.sqlite", "xanh-tabs.sqlite", "xanh-logins.sqlite")
                .forEach(appContext::deleteDatabase)
        }
    }
}
