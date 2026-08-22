package io.github.lamppkk.xanhbrowser

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tabs")
data class BrowserTab(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val position: Int,
    val url: String,
    val title: String = "",
    val selected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "history",
    indices = [Index(value = ["url"]), Index(value = ["url", "syncTimestampMillis"])],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
    val syncTimestampMillis: Long = 0,
    val syncIsRemote: Boolean = false,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"]), Index(value = ["syncGuid"])],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val syncGuid: String = "",
)

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: Long,
    val url: String,
    val fileName: String,
    val status: String = "queued",
    val destination: String = "",
    val reason: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position ASC")
    fun observeAll(): Flow<List<BrowserTab>>

    @Query("SELECT * FROM tabs ORDER BY position ASC")
    suspend fun getAll(): List<BrowserTab>

    @Query("SELECT * FROM tabs WHERE selected = 1 LIMIT 1")
    suspend fun getSelected(): BrowserTab?

    @Query("SELECT COALESCE(MAX(position), -1) FROM tabs")
    suspend fun maxPosition(): Int

    @Insert
    suspend fun insert(tab: BrowserTab): Long

    @Query("UPDATE tabs SET selected = 0")
    suspend fun clearSelection()

    @Query("UPDATE tabs SET selected = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun select(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE tabs SET url = :url, title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePage(id: Long, url: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tabs")
    suspend fun clear()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    suspend fun getAll(): List<HistoryEntry>

    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): HistoryEntry?

    @Query("SELECT * FROM history WHERE url = :url AND syncTimestampMillis = :timestamp LIMIT 1")
    suspend fun getBySyncIdentity(url: String, timestamp: Long): HistoryEntry?

    @Query("SELECT * FROM history WHERE url = :url AND visitedAt = :visitedAt AND syncTimestampMillis = 0 LIMIT 1")
    suspend fun getPending(url: String, visitedAt: Long): HistoryEntry?

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Update
    suspend fun update(entry: HistoryEntry)

    @Transaction
    suspend fun record(entry: HistoryEntry) {
        val existing = if (entry.syncTimestampMillis > 0) {
            getBySyncIdentity(entry.url, entry.syncTimestampMillis)
        } else {
            getPending(entry.url, entry.visitedAt)
        }
        if (existing == null) insert(entry) else update(entry.copy(id = existing.id))
    }

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAll(): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE syncGuid = :syncGuid LIMIT 1")
    suspend fun getBySyncGuid(syncGuid: String): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE url = :url AND syncGuid = '' LIMIT 1")
    suspend fun getPending(url: String): Bookmark?

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark)

    @Transaction
    suspend fun save(bookmark: Bookmark) {
        val existing = if (bookmark.syncGuid.isNotEmpty()) {
            getBySyncGuid(bookmark.syncGuid)
        } else {
            getPending(bookmark.url)
        }
        if (existing == null) insert(bookmark) else update(bookmark.copy(id = existing.id))
    }

    @Query("UPDATE bookmarks SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String): Int

    @Query("DELETE FROM bookmarks")
    suspend fun clear()

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: DownloadRecord)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): DownloadRecord?

    @Query("UPDATE downloads SET status = :status, destination = :destination, reason = :reason WHERE id = :id")
    suspend fun update(id: Long, status: String, destination: String, reason: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}

@Database(
    entities = [BrowserTab::class, HistoryEntry::class, Bookmark::class, DownloadRecord::class],
    version = 2,
    exportSchema = true,
)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun tabs(): TabDao
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: BrowserDatabase? = null

        fun get(context: Context): BrowserDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BrowserDatabase::class.java,
                "xanh-browser.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history_v2` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `visitedAt` INTEGER NOT NULL,
                        `syncTimestampMillis` INTEGER NOT NULL,
                        `syncIsRemote` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO `history_v2` " +
                        "(`id`, `url`, `title`, `visitedAt`, `syncTimestampMillis`, `syncIsRemote`) " +
                        "SELECT `id`, `url`, `title`, `visitedAt`, 0, 0 FROM `history`",
                )
                db.execSQL("DROP TABLE `history`")
                db.execSQL("ALTER TABLE `history_v2` RENAME TO `history`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_history_url_syncTimestampMillis` " +
                        "ON `history` (`url`, `syncTimestampMillis`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks_v2` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `syncGuid` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO `bookmarks_v2` " +
                        "(`id`, `url`, `title`, `createdAt`, `syncGuid`) " +
                        "SELECT `id`, `url`, `title`, `createdAt`, '' FROM `bookmarks`",
                )
                db.execSQL("DROP TABLE `bookmarks`")
                db.execSQL("ALTER TABLE `bookmarks_v2` RENAME TO `bookmarks`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_url` ON `bookmarks` (`url`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_syncGuid` " +
                        "ON `bookmarks` (`syncGuid`)",
                )
            }
        }
    }
}
