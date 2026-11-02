package com.anivault.data.remote.scraper

import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeRepository @Inject constructor(
    private val apiSources: AnimeApiSources,
    private val animeDao: AnimeDao
) {
    @Volatile var lastSearchSource: String? = null
        private set
    fun observeAll(): Flow<List<AnimeEntry>> = animeDao.observeAll()
    fun observeByStatus(status: WatchStatus): Flow<List<AnimeEntry>> = animeDao.observeByStatus(status)
    suspend fun upsert(entry: AnimeEntry) = withContext(Dispatchers.IO) { animeDao.upsert(entry) }
    suspend fun updateEntry(entry: AnimeEntry) = withContext(Dispatchers.IO) { animeDao.upsert(entry.copy(updatedAt = System.currentTimeMillis())) }
    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) { animeDao.deleteById(id) }
    suspend fun getAll(): List<AnimeEntry> = withContext(Dispatchers.IO) { animeDao.getAll() }
    suspend fun getById(id: String): AnimeEntry? = withContext(Dispatchers.IO) { animeDao.getById(id) }
    suspend fun upsertAll(entries: List<AnimeEntry>) = withContext(Dispatchers.IO) { animeDao.upsertAll(entries) }

    suspend fun search(query: String, page: Int = 1): List<AnimeEntry> {
        val result = apiSources.search(query, page)
        lastSearchSource = result.sourceName
        return result.entries
    }
}
