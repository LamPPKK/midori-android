package io.github.lamppkk.xanhbrowser

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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import mozilla.appservices.remotetabs.RemoteTabRecord

class SyncCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var runtime: XanhSyncRuntime? = null
    @Volatile private var nativeOwnershipUncertain = false

    @Synchronized
    fun configure(configuration: SyncConfiguration) {
        configuration.validate()
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

    suspend fun recordHistory(url: String, title: String, visitedAt: Long) = withContext(Dispatchers.IO) {
        if (!isSyncableWebUrl(url)) return@withContext
        runtimeOrNull()?.recordHistory(url, title, visitedAt)
    }

    suspend fun saveBookmark(url: String, title: String) = withContext(Dispatchers.IO) {
        if (!isSyncableWebUrl(url)) return@withContext
        runtimeOrNull()?.saveBookmark(url, title)
    }

    suspend fun deleteHistory(url: String) = withContext(Dispatchers.IO) {
        if (!isSyncableWebUrl(url)) return@withContext
        runtimeOrNull()?.deleteHistory(url)
    }

    suspend fun deleteBookmark(url: String) = withContext(Dispatchers.IO) {
        if (!isSyncableWebUrl(url)) return@withContext
        runtimeOrNull()?.deleteBookmark(url)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        runtimeOrNull()?.clearHistory()
    }

    suspend fun sync(reason: SyncReason): SyncSnapshot = withContext(Dispatchers.IO) {
        val runtime = requireNotNull(runtimeOrNull())
        if (!runtime.isSyncDue(reason)) return@withContext runtime.snapshot()
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

    fun lockVault() = runtimeOrNull()?.lockVault()

    suspend fun disconnect(deleteLocal: Boolean) = withContext(Dispatchers.IO) {
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

    private suspend fun migrateLegacyDataOnce(runtime: XanhSyncRuntime) {
        if (preferences.getBoolean(MIGRATION_COMPLETE, false)) return
        val database = BrowserDatabase.get(appContext)
        val bookmarks = database.bookmarks().getAll()
        val history = database.history().getAll()
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
