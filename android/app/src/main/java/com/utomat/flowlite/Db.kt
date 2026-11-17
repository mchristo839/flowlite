package com.utomat.flowlite

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey val id: String,
    val text: String,
    val rawText: String,
    val device: String,
    val appName: String?,
    val engine: String?,
    val model: String?,
    val language: String?,
    val durationMs: Long,
    val transcribeMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "dictionary")
data class DictWord(
    @PrimaryKey val id: String,
    val word: String,
    val replacement: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "settings")
data class SharedSetting(
    @PrimaryKey val id: String,
    val key: String,
    val valueJson: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Dao
interface TranscriptDao {
    @Upsert suspend fun upsert(t: Transcript)
    @Query("SELECT * FROM transcripts WHERE id = :id") suspend fun get(id: String): Transcript?
    @Query("SELECT * FROM transcripts WHERE dirty = 1") suspend fun dirty(): List<Transcript>
    @Query("UPDATE transcripts SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
    @Query("SELECT * FROM transcripts WHERE deleted = 0 AND text LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 500")
    fun search(q: String): Flow<List<Transcript>>
    @Query("SELECT * FROM transcripts WHERE deleted = 0 ORDER BY createdAt DESC LIMIT 1") suspend fun latest(): Transcript?
    /** Soft-deletes and blanks the text so the server keeps no copy. */
    @Query("UPDATE transcripts SET deleted = 1, text = '', rawText = '', dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun delete(id: String, now: Long = System.currentTimeMillis())
    @Query("UPDATE transcripts SET deleted = 1, text = '', rawText = '', dirty = 1, updatedAt = :now WHERE deleted = 0 AND createdAt < :cutoff")
    suspend fun purgeBefore(cutoff: Long, now: Long = System.currentTimeMillis()): Int
}

@Dao
interface DictDao {
    @Upsert suspend fun upsert(w: DictWord)
    @Query("SELECT * FROM dictionary WHERE id = :id") suspend fun get(id: String): DictWord?
    @Query("SELECT * FROM dictionary WHERE dirty = 1") suspend fun dirty(): List<DictWord>
    @Query("UPDATE dictionary SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
    @Query("SELECT * FROM dictionary WHERE deleted = 0 ORDER BY word COLLATE NOCASE")
    fun observe(): Flow<List<DictWord>>
    @Query("SELECT * FROM dictionary WHERE deleted = 0") suspend fun active(): List<DictWord>
}

@Dao
interface SettingDao {
    @Upsert suspend fun upsert(s: SharedSetting)
    @Query("SELECT * FROM settings WHERE id = :id") suspend fun get(id: String): SharedSetting?
    @Query("SELECT * FROM settings WHERE dirty = 1") suspend fun dirty(): List<SharedSetting>
    @Query("UPDATE settings SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)
    @Query("SELECT * FROM settings WHERE `key` = :key AND deleted = 0") fun observe(key: String): Flow<SharedSetting?>
}

@Database(entities = [Transcript::class, DictWord::class, SharedSetting::class], version = 1, exportSchema = false)
abstract class FlowDb : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun dictionary(): DictDao
    abstract fun settings(): SettingDao

    /** True if any row was synced with a server (dirty = 0), i.e. it belongs to some account. */
    fun hasServerRows(): Boolean = openHelper.readableDatabase.query(
        "SELECT EXISTS(SELECT 1 FROM transcripts WHERE dirty = 0) OR EXISTS(SELECT 1 FROM dictionary WHERE dirty = 0) " +
            "OR EXISTS(SELECT 1 FROM settings WHERE dirty = 0)").use { it.moveToFirst() && it.getInt(0) == 1 }

    companion object {
        @Volatile private var instance: FlowDb? = null
        fun get(ctx: Context): FlowDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, FlowDb::class.java, "flowlite.db").build()
                .also { instance = it }
        }
    }
}
