package com.anivault.data.local.dao

import androidx.room.*
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimeDao {

    // ── Observe ────────────────────────────────────────────────────────────
    @Query("SELECT * FROM anime_entries ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AnimeEntry>>

    @Query("SELECT * FROM anime_entries WHERE status = :status ORDER BY updatedAt DESC")
    fun observeByStatus(status: WatchStatus): Flow<List<AnimeEntry>>

    // ── Fetch ──────────────────────────────────────────────────────────────
    @Query("SELECT * FROM anime_entries WHERE id = :id")
    suspend fun getById(id: String): AnimeEntry?

    // ── Write ──────────────────────────────────────────────────────────────
    @Upsert
    suspend fun upsert(entry: AnimeEntry)

    @Upsert
    suspend fun upsertAll(entries: List<AnimeEntry>)

    @Delete
    suspend fun delete(entry: AnimeEntry)

    @Query("DELETE FROM anime_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM anime_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE anime_entries SET status = :status, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateStatusForIds(ids: List<String>, status: WatchStatus, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM anime_entries")
    suspend fun getAll(): List<AnimeEntry>

    // ── Stats ──────────────────────────────────────────────────────────────
    @Query("SELECT COUNT(*) FROM anime_entries")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(watchedEpisodes * durationMinutes), 0) FROM anime_entries")
    fun observeTotalWatchMinutes(): Flow<Long>

    @Query("SELECT COALESCE(AVG(rating), 0.0) FROM anime_entries WHERE rating > 0")
    fun observeAverageRating(): Flow<Float>
}
