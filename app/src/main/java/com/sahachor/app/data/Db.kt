package com.sahachor.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Entity(tableName = "conversations") data class Conversation(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val createdAt: Long)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class Message(@PrimaryKey(autoGenerate = true) val id: Long = 0, val conversationId: Long, val role: String, val content: String, val createdAt: Long)

/** type: profile | preference | fact | habit — Phase 2 fills this; schema is fixed now so no migration pain later. */
@Entity(tableName = "memories", indices = [Index(value = ["type", "key"], unique = true)])
data class Memory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val type: String, val key: String, val value: String, val importance: Int = 1, val updatedAt: Long)

@Entity(tableName = "tasks") data class TaskItem(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val note: String = "", val dueAt: Long? = null, val done: Boolean = false, val createdAt: Long)

@Entity(tableName = "audit_log") data class AuditLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val action: String, val detail: String)

@Dao interface ChatDao {
    @Insert suspend fun insertConversation(c: Conversation): Long
    @Query("SELECT id FROM conversations ORDER BY id LIMIT 1") suspend fun firstConversationId(): Long?
    @Insert suspend fun insertMessage(m: Message): Long
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id") fun messages(cid: Long): Flow<List<Message>>
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id DESC LIMIT :n") suspend fun lastMessages(cid: Long, n: Int): List<Message>
}

@Dao interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: Memory): Long
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC") fun all(): Flow<List<Memory>>
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC LIMIT :n") suspend fun top(n: Int): List<Memory>
    @Query("SELECT * FROM memories WHERE `key` LIKE :q OR value LIKE :q ORDER BY importance DESC, updatedAt DESC LIMIT 10") suspend fun search(q: String): List<Memory>
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: Long)
}

@Dao interface TaskDao {
    @Insert suspend fun insert(t: TaskItem): Long
    @Update suspend fun update(t: TaskItem)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun byId(id: Long): TaskItem?
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY COALESCE(dueAt, 9223372036854775807) LIMIT 50") suspend fun open(): List<TaskItem>
    @Query("SELECT * FROM tasks WHERE done = 0 AND dueAt IS NOT NULL AND dueAt > :now") suspend fun upcoming(now: Long): List<TaskItem>
    @Query("SELECT * FROM tasks ORDER BY done, COALESCE(dueAt, 9223372036854775807)") fun all(): Flow<List<TaskItem>>
}

@Dao interface AuditDao {
    @Insert suspend fun insert(a: AuditLog)
    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :n") suspend fun recent(n: Int): List<AuditLog>
}

/** A website the user asked the agent to watch. goal is plain words; keywords (comma separated) are optional extra filters. */
@Entity(tableName = "watch_sites")
data class WatchSite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val name: String, val goal: String, val keywords: String,
    val intervalMin: Int, val lastChecked: Long?, val active: Boolean, val baselined: Boolean, val createdAt: Long,
)

/** A document or link found on a watched site. baseline = it was already there when watching began (never announced). */
@Entity(tableName = "watch_items", indices = [Index(value = ["siteId", "url"], unique = true)])
data class WatchItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val siteId: Long, val url: String, val title: String,
    val subject: String?, val itemDate: String?, val summary: String?, val discoveredAt: Long, val notified: Boolean, val baseline: Boolean,
)

@Dao interface WatchDao {
    @Insert suspend fun insertSite(s: WatchSite): Long
    @Update suspend fun updateSite(s: WatchSite)
    @Query("DELETE FROM watch_sites WHERE id = :id") suspend fun deleteSite(id: Long)
    @Query("DELETE FROM watch_items WHERE siteId = :id") suspend fun deleteItemsOf(id: Long)
    @Query("SELECT * FROM watch_sites ORDER BY id DESC") fun sites(): Flow<List<WatchSite>>
    @Query("SELECT * FROM watch_sites WHERE active = 1") suspend fun activeSites(): List<WatchSite>
    @Query("SELECT * FROM watch_sites ORDER BY id") suspend fun allSites(): List<WatchSite>
    @Query("SELECT * FROM watch_sites WHERE id = :id") suspend fun siteById(id: Long): WatchSite?
    @Query("SELECT * FROM watch_sites WHERE url = :url LIMIT 1") suspend fun siteByUrl(url: String): WatchSite?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertItem(i: WatchItem): Long // -1 when the link is already known
    @Query("SELECT url FROM watch_items WHERE siteId = :id") suspend fun knownUrls(id: Long): List<String>
    @Query("SELECT * FROM watch_items WHERE baseline = 0 ORDER BY discoveredAt DESC LIMIT :n") fun recentItems(n: Int): Flow<List<WatchItem>>
}

/** Adds the watcher tables without touching chats, tasks, memories or the audit log. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `watch_sites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `name` TEXT NOT NULL, `goal` TEXT NOT NULL, `keywords` TEXT NOT NULL, `intervalMin` INTEGER NOT NULL, `lastChecked` INTEGER, `active` INTEGER NOT NULL, `baselined` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `watch_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `siteId` INTEGER NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `subject` TEXT, `itemDate` TEXT, `summary` TEXT, `discoveredAt` INTEGER NOT NULL, `notified` INTEGER NOT NULL, `baseline` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_items_siteId_url` ON `watch_items` (`siteId`, `url`)")
    }
}

@Database(entities = [Conversation::class, Message::class, Memory::class, TaskItem::class, AuditLog::class, WatchSite::class, WatchItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chat(): ChatDao
    abstract fun memory(): MemoryDao
    abstract fun tasks(): TaskDao
    abstract fun audit(): AuditDao
    abstract fun watch(): WatchDao

    companion object {
        private const val NAME = "sahachor.db"

        /** Opens the SQLCipher-encrypted DB. Returns null (never crashes) if the passphrase is unavailable or the file is unreadable. */
        fun open(context: Context, passphrase: ByteArray?): AppDatabase? {
            if (passphrase == null) return null
            return try {
                System.loadLibrary("sqlcipher")
                val db = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(MIGRATION_1_2)
                    .build()
                db.openHelper.writableDatabase // force open now so a wrong key fails here, not later in the UI
                db
            } catch (e: Exception) {
                // Keep the unreadable file (never silently delete user data) so it can be recovered or exported later.
                val f = context.getDatabasePath(NAME)
                if (f.exists()) f.renameTo(File(f.parentFile, "$NAME.unreadable-${System.currentTimeMillis()}"))
                null
            }
        }
    }
}
