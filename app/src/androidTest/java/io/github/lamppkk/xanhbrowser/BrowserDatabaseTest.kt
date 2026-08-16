package io.github.lamppkk.xanhbrowser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserDatabaseTest {
    private lateinit var database: BrowserDatabase

    @Before fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BrowserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun persistsSelectedTab() = runBlocking {
        val id = database.tabs().insert(BrowserTab(position = 0, url = "https://example.com", selected = true))
        val selected = database.tabs().getSelected()
        assertNotNull(selected)
        assertEquals(id, selected?.id)
    }

    @Test fun historyReplacesDuplicateUrl() = runBlocking {
        database.history().record(HistoryEntry(url = "https://example.com", title = "First"))
        database.history().record(HistoryEntry(url = "https://example.com", title = "Latest"))
        val entries = database.history().observeAll().first()
        assertEquals(1, entries.size)
        assertEquals("Latest", entries.first().title)
    }

    @Test fun repositoryManagesTabsAndRestoresSelection() = runBlocking {
        val repository = BrowserRepository(database)
        val first = repository.createTab("https://one.example")
        val second = repository.createTab("https://two.example")
        assertEquals(second.id, repository.selected()?.id)

        repository.selectTab(first.id)
        assertEquals(first.id, repository.selected()?.id)
        val selectedAfterClose = repository.closeTab(first.id, "https://home.example")
        assertEquals(second.id, selectedAfterClose.id)
        assertEquals(1, repository.allTabs().size)
    }

    @Test fun repositoryClearsPrivateDataButKeepsBookmarks() = runBlocking {
        val repository = BrowserRepository(database)
        val tab = repository.createTab("https://example.com")
        repository.updatePage(tab.id, "https://example.com/page", "Example")
        repository.saveBookmark("https://example.com/page", "Example")
        repository.saveDownload(42, "https://example.com/file", "file.bin")

        val cleanTab = repository.clearPrivateData("https://home.example")
        assertEquals("https://home.example", cleanTab.url)
        assertEquals(0, repository.history.first().size)
        assertEquals(0, repository.downloads.first().size)
        assertEquals(1, repository.bookmarks.first().size)
        assertEquals(1, repository.allTabs().size)
    }

    @Test fun repositoryTracksDownloadCompletion() = runBlocking {
        val repository = BrowserRepository(database)
        repository.saveDownload(7, "https://example.com/file", "file.bin")
        repository.updateDownload(7, "successful", "content://downloads/7", 0)

        val download = repository.downloads.first().single()
        assertEquals("successful", download.status)
        assertEquals("content://downloads/7", download.destination)

        repository.deleteDownload(7)
        assertEquals(0, repository.downloads.first().size)
    }
}
