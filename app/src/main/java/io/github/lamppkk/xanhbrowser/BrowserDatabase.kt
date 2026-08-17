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

@Entity(tableName = "history", indices = [Index(value = ["url"], unique = true)])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entry: HistoryEntry)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(bookmark: Bookmark)

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
    version = 1,
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
            ).build().also { instance = it }
        }
    }
}
