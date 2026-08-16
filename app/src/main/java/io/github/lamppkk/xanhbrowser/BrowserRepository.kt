package io.github.lamppkk.xanhbrowser

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class BrowserRepository internal constructor(private val database: BrowserDatabase) {
    constructor(context: Context) : this(BrowserDatabase.get(context))

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
        database.withTransaction {
            database.tabs().updatePage(id, url, title)
            if (url.startsWith("http://") || url.startsWith("https://")) {
                database.history().record(HistoryEntry(url = url, title = title))
            }
        }
    }

    suspend fun saveBookmark(url: String, title: String) {
        database.bookmarks().save(Bookmark(url = url, title = title))
    }

    suspend fun deleteHistory(id: Long) = database.history().delete(id)
    suspend fun deleteBookmark(id: Long) = database.bookmarks().delete(id)

    suspend fun saveDownload(id: Long, url: String, fileName: String) {
        database.downloads().save(DownloadRecord(id = id, url = url, fileName = fileName))
    }

    suspend fun hasDownload(id: Long): Boolean = database.downloads().get(id) != null

    suspend fun allDownloads(): List<DownloadRecord> = database.downloads().getAll()

    suspend fun updateDownload(id: Long, status: String, destination: String, reason: Int) {
        database.downloads().update(id, status, destination, reason)
    }

    suspend fun deleteDownload(id: Long) = database.downloads().delete(id)

    suspend fun clearPrivateData(homeUrl: String): BrowserTab = database.withTransaction {
        database.history().clear()
        database.downloads().clear()
        database.tabs().clear()
        createTabInternal(homeUrl)
    }
}
