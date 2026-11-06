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

    @Query("SELECT * FROM anime_entries WHERE status = :status ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentByStatus(status: WatchStatus, limit: Int): Flow<List<AnimeEntry>>

    @Query("SELECT COUNT(*) FROM anime_entries WHERE status = :status")
    fun observeCountByStatus(status: WatchStatus): Flow<Int>

    @Query("SELECT * FROM anime_entries WHERE id = :id")
    fun observeById(id: String): Flow<AnimeEntry?>

    // ── Fetch ──────────────────────────────────────────────────────────────
    @Query("SELECT * FROM anime_entries WHERE id = :id")
    suspend fun getById(id: String): AnimeEntry?

    @Query("SELECT * FROM anime_entries")
    suspend fun getAll(): List<AnimeEntry>

    @Query("SELECT COUNT(*) FROM anime_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM anime_entries WHERE pendingSync = 1")
    suspend fun getPendingSync(): List<AnimeEntry>

    // ── Write ──────────────────────────────────────────────────────────────
    @Upsert
    suspend fun upsert(entry: AnimeEntry)

    @Upsert
    suspend fun upsertAll(entries: List<AnimeEntry>)

    /** Used by the legacy migration: never overwrites rows that already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entries: List<AnimeEntry>): List<Long>

    @Delete
    suspend fun delete(entry: AnimeEntry)

    @Query("DELETE FROM anime_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM anime_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM anime_entries")
    suspend fun deleteAll()

    @Query("UPDATE anime_entries SET status = :status, updatedAt = :now, pendingSync = 1 WHERE id IN (:ids)")
    suspend fun updateStatusForIds(ids: List<String>, status: WatchStatus, now: Long = System.currentTimeMillis())

    /** Idempotent progress write: the same call twice yields the same row. */
    @Query("UPDATE anime_entries SET watchedEpisodes = :watched, status = :status, updatedAt = :now, pendingSync = 1 WHERE id = :id")
    suspend fun setProgress(id: String, watched: Int, status: WatchStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE anime_entries SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPending(ids: List<String>)

    // ── Stats ──────────────────────────────────────────────────────────────
    @Query("SELECT COUNT(*) FROM anime_entries")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(watchedEpisodes * durationMinutes), 0) FROM anime_entries")
    fun observeTotalWatchMinutes(): Flow<Long>

    /** Average of PERSONAL ratings, rated entries only. */
    @Query("SELECT COALESCE(AVG(rating), 0.0) FROM anime_entries WHERE rating > 0")
    fun observeAverageRating(): Flow<Float>
}
