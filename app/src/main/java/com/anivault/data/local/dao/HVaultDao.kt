package com.anivault.data.local.dao

import androidx.room.*
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface HVaultDao {

    // ── Observe ────────────────────────────────────────────────────────────
    @Query("SELECT * FROM hvault_entries ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HVaultEntry>>

    @Query("SELECT * FROM hvault_entries WHERE status = :status ORDER BY updatedAt DESC")
    fun observeByStatus(status: WatchStatus): Flow<List<HVaultEntry>>

    // ── Fetch ──────────────────────────────────────────────────────────────
    @Query("SELECT * FROM hvault_entries WHERE id = :id")
    suspend fun getById(id: String): HVaultEntry?

    @Query("SELECT * FROM hvault_entries")
    suspend fun getAll(): List<HVaultEntry>

    @Query("SELECT COUNT(*) FROM hvault_entries")
    suspend fun count(): Int

    // ── Write ──────────────────────────────────────────────────────────────
    @Upsert
    suspend fun upsert(entry: HVaultEntry)

    @Upsert
    suspend fun upsertAll(entries: List<HVaultEntry>)

    /** Used by the legacy migration: never overwrites rows that already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entries: List<HVaultEntry>): List<Long>

    @Delete
    suspend fun delete(entry: HVaultEntry)

    @Query("DELETE FROM hvault_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM hvault_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM hvault_entries")
    suspend fun deleteAll()

    @Query("UPDATE hvault_entries SET status = :status, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateStatusForIds(ids: List<String>, status: WatchStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE hvault_entries SET watchedEpisodes = :watched, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: String, watched: Int, status: WatchStatus, now: Long = System.currentTimeMillis())

    // ── Stats ──────────────────────────────────────────────────────────────
    @Query("SELECT COUNT(*) FROM hvault_entries")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(watchedEpisodes * durationMinutes), 0) FROM hvault_entries")
    fun observeTotalWatchMinutes(): Flow<Long>

    @Query("SELECT COALESCE(AVG(rating), 0.0) FROM hvault_entries WHERE rating > 0")
    fun observeAverageRating(): Flow<Float>
}
