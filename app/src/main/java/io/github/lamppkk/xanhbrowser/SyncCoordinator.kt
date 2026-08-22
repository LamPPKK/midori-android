package io.github.lamppkk.xanhbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.room.withTransaction
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.lamppkk.xanhbrowser.sync.AccountServer
import io.github.lamppkk.xanhbrowser.sync.AccountState
import io.github.lamppkk.xanhbrowser.sync.LegacyBookmark
import io.github.lamppkk.xanhbrowser.sync.LegacyHistoryVisit
import io.github.lamppkk.xanhbrowser.sync.PlacesMutationPolicy
import io.github.lamppkk.xanhbrowser.sync.SyncConfiguration
import io.github.lamppkk.xanhbrowser.sync.SyncReason
import io.github.lamppkk.xanhbrowser.sync.SyncEngine
import io.github.lamppkk.xanhbrowser.sync.SyncSnapshot
import io.github.lamppkk.xanhbrowser.sync.XanhSyncRuntime
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import mozilla.appservices.remotetabs.RemoteTabRecord

class SyncCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var runtime: XanhSyncRuntime? = null
    @Volatile private var nativeOwnershipUncertain = false
    private val placesMutex = Mutex()

    @Synchronized
    fun configure(configuration: SyncConfiguration) {
        configuration.validate()
        check(!placesMutex.isLocked) { "A Places workflow is already running" }
        check(!nativeOwnershipUncertain) { "Mozilla Sync native ownership is uncertain" }
        try {
            runtime?.close()
        } catch (error: Throwable) {
            nativeOwnershipUncertain = true
            throw error
        }
        runtime = null
        preferences.edit {
            putString(CLIENT_ID, configuration.clientId)
            putString(ACCOUNTS_URL, (configuration.server as? AccountServer.SelfHosted)?.accountsUrl)
            putString(TOKEN_URL, (configuration.server as? AccountServer.SelfHosted)?.tokenServerUrl)
        }
        runtime = createRuntime(configuration)
    }

    @Synchronized
    fun runtimeOrNull(): XanhSyncRuntime? {
        if (nativeOwnershipUncertain) return null
        runtime?.let { return it }
        val configuration = storedConfiguration() ?: return null
        return runCatching { createRuntime(configuration) }
            .getOrNull()
            .also { runtime = it }
    }

    fun hasConfiguration(): Boolean = storedConfiguration() != null

    fun beginOAuth(): String = requireNotNull(runtimeOrNull()).beginOAuth()

    fun completeOAuth(uri: Uri): AccountState {
        require(uri.scheme == REDIRECT_SCHEME && uri.host == REDIRECT_HOST && uri.path == REDIRECT_PATH)
        return requireNotNull(runtimeOrNull()).completeOAuth(
            requireNotNull(uri.getQueryParameter("code")),
            requireNotNull(uri.getQueryParameter("state")),
        )
    }

    fun snapshot(): SyncSnapshot? = runtimeOrNull()?.snapshot()

    suspend fun recordLocalChange() = withContext(Dispatchers.IO) {
        val runtime = runtimeOrNull() ?: return@withContext
        runtime.recordLocalChange()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_REASON to SyncReason.LOCAL_CHANGE.name))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            LOCAL_CHANGE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun isDue(reason: SyncReason): Boolean = runtimeOrNull()?.isSyncDue(reason) == true

    fun setEngineEnabled(engine: SyncEngine, enabled: Boolean) {
        preferences.edit { putBoolean("$ENGINE_PREFIX${engine.name}", enabled) }
        runtimeOrNull()?.setEngineEnabled(engine, enabled)
    }

    suspend fun recordPageVisit(tabId: Long, entry: HistoryEntry) = withContext(Dispatchers.IO) {
        if (!isSyncableWebUrl(entry.url)) {
            BrowserDatabase.get(appContext).tabs().updatePage(
                tabId,
                entry.url,
                PlacesMutationPolicy.sanitizeTitle(entry.title, entry.url),
                entry.visitedAt,
            )
            return@withContext
        }
        placesMutex.withLock {
            val title = PlacesMutationPolicy.sanitizeTitle(entry.title, entry.url)
            val syncTimestamp = writeOrQueuePending {
                recordHistory(entry.url, title, entry.visitedAt)
                entry.visitedAt
            } ?: 0
            val database = BrowserDatabase.get(appContext)
            database.withTransaction {
                database.tabs().updatePage(tabId, entry.url, title, entry.visitedAt)
                database.history().record(
                    entry.copy(
                        title = title,
                        syncTimestampMillis = syncTimestamp,
                        syncIsRemote = false,
                    ),
                )
            }
        }
    }

    suspend fun saveBookmark(bookmark: Bookmark) = withContext(Dispatchers.IO) {
        if (!isSyncableWebUrl(bookmark.url)) return@withContext
        placesMutex.withLock {
            val title = PlacesMutationPolicy.sanitizeTitle(bookmark.title, bookmark.url)
            val syncGuid = writeOrQueuePending { saveBookmark(bookmark.url, title) }.orEmpty()
            BrowserDatabase.get(appContext).bookmarks().save(
                bookmark.copy(title = title, syncGuid = syncGuid),
            )
        }
    }

    suspend fun deleteHistory(id: Long) = withContext(Dispatchers.IO) {
        placesMutex.withLock {
            val dao = BrowserDatabase.get(appContext).history()
            val existing = dao.get(id) ?: return@withLock
            if (existing.syncTimestampMillis > 0) {
                requireNotNull(runtimeOrNull()) {
                    "Places is unavailable; exact history deletion was not applied"
                }.deleteHistory(existing.url, existing.syncTimestampMillis)
            } else {
                invalidatePendingMigration()
            }
            dao.delete(id)
        }
    }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) {
        placesMutex.withLock {
            val dao = BrowserDatabase.get(appContext).bookmarks()
            val existing = dao.get(id) ?: return@withLock
            if (existing.syncGuid.isNotEmpty()) {
                val guid = PlacesMutationPolicy.requireSyncGuid(existing.syncGuid)
                requireNotNull(runtimeOrNull()) {
                    "Places is unavailable; exact bookmark deletion was not applied"
                }.deleteBookmark(guid)
            } else {
                invalidatePendingMigration()
            }
            dao.delete(id)
        }
    }

    suspend fun renameBookmark(id: Long, title: String): String = withContext(Dispatchers.IO) {
        placesMutex.withLock {
            val dao = BrowserDatabase.get(appContext).bookmarks()
            val existing = requireNotNull(dao.get(id)) { "Bookmark no longer exists" }
            val safeTitle = PlacesMutationPolicy.sanitizeTitle(title, existing.url)
            if (existing.syncGuid.isNotEmpty()) {
                val guid = PlacesMutationPolicy.requireSyncGuid(existing.syncGuid)
                requireNotNull(runtimeOrNull()) {
                    "Places is unavailable; bookmark rename was not applied"
                }.renameBookmark(guid, safeTitle)
            } else {
                invalidatePendingMigration()
            }
            check(dao.rename(id, safeTitle) == 1) { "Bookmark changed during rename" }
            safeTitle
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        placesMutex.withLock {
            val active = runtimeOrNull()
            if (active == null) invalidatePendingMigration() else active.clearHistory()
            BrowserDatabase.get(appContext).history().clear()
        }
    }

    suspend fun sync(reason: SyncReason): SyncSnapshot = withContext(Dispatchers.IO) {
        placesMutex.withLock {
            val runtime = requireNotNull(runtimeOrNull())
            if (!runtime.isSyncDue(reason)) return@withLock runtime.snapshot()
            migrateLegacyDataOnce(runtime)
            val tabs = BrowserDatabase.get(appContext).tabs().getAll()
                .filter { isSyncableWebUrl(it.url) }
                .mapIndexed { index, tab ->
                    RemoteTabRecord(
                        title = tab.title.ifBlank { tab.url },
                        urlHistory = listOf(tab.url),
                        icon = null,
                        lastUsed = tab.updatedAt,
                        inactive = false,
                        pinned = false,
                        index = index.toUInt(),
                        windowId = "",
                        tabGroupId = "",
                    )
                }
            runtime.setLocalTabs(tabs)
            runtime.sync(reason).also { refreshPlacesCompatibilityMirror(runtime) }
        }
    }

    fun lockVault() = runtimeOrNull()?.lockVault()

    suspend fun disconnect(deleteLocal: Boolean) = withContext(Dispatchers.IO) {
        placesMutex.withLock {
            synchronized(this@SyncCoordinator) {
                val active = runtimeOrNull()
                check(!nativeOwnershipUncertain) {
                    "Cannot delete Sync data while native ownership is uncertain"
                }
                var failure: Throwable? = null
                try {
                    active?.disconnect(deleteLocal)
                } catch (error: Throwable) {
                    failure = error
                }

                var closedCleanly = false
                try {
                    active?.close()
                    closedCleanly = true
                } catch (closeError: Throwable) {
                    nativeOwnershipUncertain = true
                    val first = failure
                    if (first == null) failure = closeError else first.addSuppressed(closeError)
                }

                if (closedCleanly) {
                    nativeOwnershipUncertain = false
                    if (runtime === active) runtime = null
                    if (deleteLocal) {
                        fun cleanup(block: () -> Unit) {
                            try {
                                block()
                            } catch (cleanupError: Throwable) {
                                val first = failure
                                if (first == null) failure = cleanupError else first.addSuppressed(cleanupError)
                            }
                        }
                        cleanup { XanhSyncRuntime.deleteLocalData(appContext) }
                        cleanup {
                            check(preferences.edit().clear().commit()) {
                                "Failed to clear Firefox Sync preferences"
                            }
                        }
                        cleanup {
                            val migration = File(appContext.noBackupFilesDir, "sync-migration")
                            check(!migration.exists() || migration.deleteRecursively()) {
                                "Failed to remove Firefox Sync migration backup"
                            }
                        }
                    }
                }
                failure?.let { throw it }
            }
        }
    }

    fun scheduleBackgroundSync() {
        if (!hasConfiguration()) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun schedulePreSleepSync() {
        if (snapshot()?.accountState != AccountState.CONNECTED) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_REASON to SyncReason.PRE_SLEEP.name))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            PRE_SLEEP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun storedConfiguration(): SyncConfiguration? {
        val clientId = preferences.getString(CLIENT_ID, null)
            ?: BuildConfig.XANH_FXA_CLIENT_ID.takeIf(String::isNotBlank)
            ?: return null
        val accounts = preferences.getString(ACCOUNTS_URL, null)
        val token = preferences.getString(TOKEN_URL, null)
        val server = if (accounts != null && token != null) {
            AccountServer.SelfHosted(accounts, token)
        } else {
            AccountServer.Mozilla
        }
        return SyncConfiguration(server, clientId, REDIRECT_URI, appContext.getString(R.string.app_name))
    }

    private fun applyEnginePreferences(runtime: XanhSyncRuntime) {
        SyncEngine.entries.forEach { engine ->
            runtime.setEngineEnabled(
                engine,
                preferences.getBoolean("$ENGINE_PREFIX${engine.name}", true),
            )
        }
    }

    private fun createRuntime(configuration: SyncConfiguration): XanhSyncRuntime {
        val created = try {
            XanhSyncRuntime(appContext, configuration)
        } catch (error: Throwable) {
            if (!XanhSyncRuntime.isProcessRegistryAvailable()) {
                nativeOwnershipUncertain = true
            }
            throw error
        }
        try {
            applyEnginePreferences(created)
            return created
        } catch (error: Throwable) {
            try {
                created.close()
            } catch (closeError: Throwable) {
                nativeOwnershipUncertain = true
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    private fun isSyncableWebUrl(value: String): Boolean = runCatching {
        val uri = value.toUri()
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private fun <T> writeOrQueuePending(operation: XanhSyncRuntime.() -> T): T? {
        val active = runtimeOrNull()
        if (active == null) {
            invalidatePendingMigration()
            return null
        }
        return try {
            active.operation()
        } catch (_: Exception) {
            // The native operation can fail after a partial local commit. A
            // write-ahead invalidation makes the idempotent legacy importer
            // reconcile the compatibility row on the next successful Sync.
            invalidatePendingMigration()
            null
        }
    }

    @SuppressLint("UseKtx")
    private fun invalidatePendingMigration() {
        if (!preferences.contains(MIGRATION_COMPLETE) &&
            !preferences.contains(MIGRATION_CHECKSUM)
        ) {
            return
        }
        // KTX edit(commit = true) discards commit()'s boolean result. Keep the
        // explicit call so Room is never changed after a failed write-ahead.
        check(
            preferences.edit()
                .remove(MIGRATION_COMPLETE)
                .remove(MIGRATION_CHECKSUM)
                .commit(),
        ) { "Failed to persist pending Places migration intent" }
    }

    private suspend fun migrateLegacyDataOnce(runtime: XanhSyncRuntime) {
        if (preferences.getBoolean(MIGRATION_COMPLETE, false)) return
        val database = BrowserDatabase.get(appContext)
        val bookmarks = database.bookmarks().getAll().filter { it.syncGuid.isEmpty() }
        val history = database.history().getAll().filter { it.syncTimestampMillis == 0L }
        val snapshot = JSONObject().apply {
            put("schema", 1)
            put("bookmarks", JSONArray(bookmarks.map { JSONObject().put("url", it.url).put("title", it.title) }))
            put("history", JSONArray(history.map {
                JSONObject().put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt)
            }))
        }.toString().toByteArray()
        val backup = File(appContext.noBackupFilesDir, "sync-migration/xanh-browser-room-v1.json")
        backup.parentFile?.mkdirs()
        val temporary = File(backup.parentFile, "${backup.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(snapshot)
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            backup.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val checksum = MessageDigest.getInstance("SHA-256").digest(snapshot).joinToString("") { "%02x".format(it) }
        check(backup.readBytes().contentEquals(snapshot)) { "Pre-Sync migration backup verification failed" }
        val counts = runtime.importLegacyData(
            bookmarks.map { LegacyBookmark(it.url, it.title) },
            history.map { LegacyHistoryVisit(it.url, it.title, it.visitedAt) },
        )
        check(counts.bookmarks == bookmarks.distinctBy { it.url }.size)
        check(counts.history == history.distinctBy { it.url to it.visitedAt }.size)
        preferences.edit {
            putBoolean(MIGRATION_COMPLETE, true)
            putString(MIGRATION_CHECKSUM, checksum)
        }
    }

    private suspend fun refreshPlacesCompatibilityMirror(runtime: XanhSyncRuntime) {
        val mirror = runtime.placesMirror(PLACES_MIRROR_LIMIT)
        val database = BrowserDatabase.get(appContext)
        database.withTransaction {
            database.history().clear()
            mirror.history.forEach { visit ->
                database.history().record(
                    HistoryEntry(
                        url = visit.url,
                        title = visit.title,
                        visitedAt = visit.visitedAt,
                        syncTimestampMillis = visit.visitedAt,
                        syncIsRemote = visit.isRemote,
                    ),
                )
            }
            database.bookmarks().clear()
            mirror.bookmarks.forEach { bookmark ->
                database.bookmarks().save(
                    Bookmark(
                        url = bookmark.url,
                        title = bookmark.title,
                        createdAt = bookmark.createdAt,
                        syncGuid = bookmark.guid,
                    ),
                )
            }
        }
    }

    companion object {
        const val REDIRECT_SCHEME = "xanh-browser-android"
        const val REDIRECT_HOST = "accounts"
        const val REDIRECT_PATH = "/oauth"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST$REDIRECT_PATH"
        private const val PREFERENCES = "xanh_sync_configuration"
        private const val CLIENT_ID = "client_id"
        private const val ACCOUNTS_URL = "accounts_url"
        private const val TOKEN_URL = "token_url"
        private const val ENGINE_PREFIX = "engine_enabled_"
        private const val MIGRATION_COMPLETE = "places_migration_v1_complete"
        private const val MIGRATION_CHECKSUM = "places_migration_v1_sha256"
        private const val WORK_NAME = "xanh-firefox-sync"
        private const val LOCAL_CHANGE_WORK_NAME = "xanh-firefox-sync-local-change"
        private const val PRE_SLEEP_WORK_NAME = "xanh-firefox-sync-pre-sleep"
        private const val PLACES_MIRROR_LIMIT = 2_000
        @Volatile private var instance: SyncCoordinator? = null

        fun get(context: Context): SyncCoordinator = instance ?: synchronized(this) {
            instance ?: SyncCoordinator(context).also { instance = it }
        }
    }
}
