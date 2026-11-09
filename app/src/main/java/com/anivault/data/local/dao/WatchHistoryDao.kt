package com.anivault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anivault.data.local.entity.WatchHistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<WatchHistoryEntry>>

    @Query("SELECT * FROM watch_history WHERE entryId = :entryId ORDER BY watchedAt DESC LIMIT :limit")
    fun observeForEntry(entryId: String, limit: Int = 20): Flow<List<WatchHistoryEntry>>

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC")
    suspend fun getAll(): List<WatchHistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WatchHistoryEntry): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entries: List<WatchHistoryEntry>): List<Long>

    @Query("DELETE FROM watch_history WHERE entryId = :entryId")
    suspend fun deleteByEntryId(entryId: String)

    @Query("DELETE FROM watch_history")
    suspend fun deleteAll()
}
