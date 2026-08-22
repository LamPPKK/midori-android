package io.github.lamppkk.xanhbrowser

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun migratesV1RowsToPendingPlacesIdentities() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            BrowserDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )
        val name = "xanh-browser-migration-${System.nanoTime()}"
        helper.createDatabase(name, 1).use { old ->
            old.execSQL(
                "INSERT INTO bookmarks (url, title, createdAt) " +
                    "VALUES ('https://bookmark.example', 'Bookmark', 10)",
            )
            old.execSQL(
                "INSERT INTO history (url, title, visitedAt) " +
                    "VALUES ('https://history.example', 'History', 20)",
            )
        }
        helper.runMigrationsAndValidate(name, 2, true, BrowserDatabase.MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT syncGuid FROM bookmarks").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
            migrated.query("SELECT syncTimestampMillis, syncIsRemote FROM history").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getLong(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    @Test fun persistsSelectedTab() = runBlocking {
        val id = database.tabs().insert(BrowserTab(position = 0, url = "https://example.com", selected = true))
        val selected = database.tabs().getSelected()
        assertNotNull(selected)
        assertEquals(id, selected?.id)
    }

    @Test fun historyPreservesExactVisitIdentities() = runBlocking {
        database.history().record(HistoryEntry(url = "https://example.com", title = "First"))
        database.history().record(
            HistoryEntry(
                url = "https://example.com",
                title = "Latest",
                visitedAt = 2,
                syncTimestampMillis = 2,
                syncIsRemote = true,
            ),
        )
        val entries = database.history().observeAll().first()
        assertEquals(2, entries.size)
        assertEquals("Latest", entries.first().title)
        assertEquals(2, entries.first().syncTimestampMillis)
        assertEquals(true, entries.first().syncIsRemote)
    }

    @Test fun bookmarkMirrorKeepsDuplicateUrlsByGuid() = runBlocking {
        val dao = database.bookmarks()
        dao.save(Bookmark(url = "https://example.com", title = "One", syncGuid = "AbCdEf123_-x"))
        dao.save(Bookmark(url = "https://example.com", title = "Two", syncGuid = "ZyXwVu987_-q"))
        assertEquals(2, dao.getAll().size)
        val second = requireNotNull(dao.getBySyncGuid("ZyXwVu987_-q"))
        assertEquals(1, dao.rename(second.id, "Renamed"))
        assertEquals("Renamed", dao.get(second.id)?.title)
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
