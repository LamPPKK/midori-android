package io.github.lamppkk.xanhbrowser

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class BrowserRepository internal constructor(
    private val database: BrowserDatabase,
    private val onLocalChange: suspend () -> Unit = {},
    private val onHistoryRecorded: suspend (String, String, Long) -> Unit = { _, _, _ -> },
    private val onBookmarkSaved: suspend (String, String) -> Unit = { _, _ -> },
    private val onHistoryDeleted: suspend (String) -> Unit = {},
    private val onBookmarkDeleted: suspend (String) -> Unit = {},
    private val onHistoryCleared: suspend () -> Unit = {},
) {
    constructor(context: Context) : this(
        BrowserDatabase.get(context),
        { SyncCoordinator.get(context).recordLocalChange() },
        { url, title, visitedAt -> SyncCoordinator.get(context).recordHistory(url, title, visitedAt) },
        { url, title -> SyncCoordinator.get(context).saveBookmark(url, title) },
        { url -> SyncCoordinator.get(context).deleteHistory(url) },
        { url -> SyncCoordinator.get(context).deleteBookmark(url) },
        { SyncCoordinator.get(context).clearHistory() },
    )

    val tabs: Flow<List<BrowserTab>> = database.tabs().observeAll()
    val history: Flow<List<HistoryEntry>> = database.history().observeAll()
    val bookmarks: Flow<List<Bookmark>> = database.bookmarks().observeAll()
    val downloads: Flow<List<DownloadRecord>> = database.downloads().observeAll()

    suspend fun selectedOrCreate(homeUrl: String): BrowserTab = database.withTransaction {
        database.tabs().getSelected() ?: createTabInternal(homeUrl)
    }

    suspend fun selected(): BrowserTab? = database.tabs().getSelected()

    suspend fun allTabs(): List<BrowserTab> = database.tabs().getAll()

    suspend fun createTab(url: String): BrowserTab = database.withTransaction {
        createTabInternal(url)
    }

    private suspend fun createTabInternal(url: String): BrowserTab {
        database.tabs().clearSelection()
        val tab = BrowserTab(position = database.tabs().maxPosition() + 1, url = url, selected = true)
        return tab.copy(id = database.tabs().insert(tab))
    }

    suspend fun selectTab(id: Long) = database.withTransaction {
        database.tabs().clearSelection()
        database.tabs().select(id)
    }

    suspend fun closeTab(id: Long, homeUrl: String): BrowserTab = database.withTransaction {
        val wasSelected = database.tabs().getSelected()?.id == id
        database.tabs().delete(id)
        val remaining = database.tabs().getAll()
        val selected = if (remaining.isEmpty()) {
            createTabInternal(homeUrl)
        } else if (wasSelected) {
            remaining.first().also { database.tabs().select(it.id) }.copy(selected = true)
        } else {
            database.tabs().getSelected() ?: remaining.first().also { database.tabs().select(it.id) }
        }
        selected
    }

    suspend fun updatePage(id: Long, url: String, title: String) {
        val visitedAt = System.currentTimeMillis()
        database.withTransaction {
            database.tabs().updatePage(id, url, title)
            if (url.startsWith("http://") || url.startsWith("https://")) {
                database.history().record(HistoryEntry(url = url, title = title, visitedAt = visitedAt))
            }
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            onHistoryRecorded(url, title, visitedAt)
        }
        onLocalChange()
    }

    suspend fun saveBookmark(url: String, title: String) {
        database.bookmarks().save(Bookmark(url = url, title = title))
        onBookmarkSaved(url, title)
        onLocalChange()
    }

    suspend fun deleteHistory(id: Long) {
        val existing = database.history().get(id)
        database.history().delete(id)
        existing?.let { onHistoryDeleted(it.url) }
        onLocalChange()
    }

    suspend fun deleteBookmark(id: Long) {
        val existing = database.bookmarks().get(id)
        database.bookmarks().delete(id)
        existing?.let { onBookmarkDeleted(it.url) }
        onLocalChange()
    }

    suspend fun saveDownload(id: Long, url: String, fileName: String) {
        database.downloads().save(DownloadRecord(id = id, url = url, fileName = fileName))
    }

    suspend fun hasDownload(id: Long): Boolean = database.downloads().get(id) != null

    suspend fun allDownloads(): List<DownloadRecord> = database.downloads().getAll()

    suspend fun updateDownload(id: Long, status: String, destination: String, reason: Int) {
        database.downloads().update(id, status, destination, reason)
    }

    suspend fun deleteDownload(id: Long) = database.downloads().delete(id)

    suspend fun clearPrivateData(homeUrl: String): BrowserTab {
        val tab = database.withTransaction {
            database.history().clear()
            database.downloads().clear()
            database.tabs().clear()
            createTabInternal(homeUrl)
        }
        onHistoryCleared()
        onLocalChange()
        return tab
    }
}
