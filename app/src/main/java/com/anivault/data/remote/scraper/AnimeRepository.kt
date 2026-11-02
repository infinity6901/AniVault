package com.anivault.data.remote.scraper

import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.JikanAnime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeRepository @Inject constructor(
    private val jikanApi: JikanApiService,
    private val animeDao: AnimeDao
) {
    // ── Local ──────────────────────────────────────────────────────────────
    fun observeAll(): Flow<List<AnimeEntry>> = animeDao.observeAll()

    fun observeByStatus(status: WatchStatus): Flow<List<AnimeEntry>> =
        animeDao.observeByStatus(status)

    suspend fun upsert(entry: AnimeEntry) = withContext(Dispatchers.IO) {
        animeDao.upsert(entry)
    }

    suspend fun updateEntry(entry: AnimeEntry) = withContext(Dispatchers.IO) {
        animeDao.upsert(entry.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        animeDao.deleteById(id)
    }

    suspend fun getAll(): List<AnimeEntry> = withContext(Dispatchers.IO) {
        animeDao.getAll()
    }

    suspend fun getById(id: String): AnimeEntry? = withContext(Dispatchers.IO) {
        animeDao.getById(id)
    }

    suspend fun upsertAll(entries: List<AnimeEntry>) = withContext(Dispatchers.IO) {
        animeDao.upsertAll(entries)
    }

    // ── Remote search ──────────────────────────────────────────────────────
    /**
     * Search Jikan for [query] on [page] and return mapped domain objects.
     * Does NOT write to the database – the caller decides whether to persist.
     */
    suspend fun search(query: String, page: Int = 1): List<AnimeEntry> =
        withContext(Dispatchers.IO) {
            val response = jikanApi.searchAnime(q = query, page = page)
            response.data.map { it.toAnimeEntry() }
        }

    // ── Mapping ────────────────────────────────────────────────────────────
    private fun JikanAnime.toAnimeEntry(): AnimeEntry {
        val mins = parseDurationText(duration ?: "")
        return AnimeEntry(
            id              = malId.toString(),
            title           = titleEnglish?.ifBlank { null } ?: title,
            imageUrl        = images?.jpg?.largeImageUrl
                                ?: images?.jpg?.imageUrl ?: "",
            totalEpisodes   = episodes ?: 0,
            watchedEpisodes = 0,
            status          = WatchStatus.PLAN_TO_WATCH,
            rating          = 0f,
            year            = year,
            season          = season?.replaceFirstChar { it.uppercaseChar() } ?: "",
            genres          = genres?.map { it.name } ?: emptyList(),
            synopsis        = synopsis ?: "",
            notes           = "",
            durationMinutes = mins,
            updatedAt       = System.currentTimeMillis()
        )
    }

    /**
     * Parse Jikan duration strings like "24 min per ep", "1 hr 30 min", "Unknown".
     * Returns minutes as Int, or 0 if unparseable.
     */
    private fun parseDurationText(text: String): Int {
        if (text.isBlank()) return 0
        val hrMatch  = Regex("""(\d+)\s*hr""",  RegexOption.IGNORE_CASE).find(text)
        val minMatch = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(text)
        val hours   = hrMatch?.groupValues?.get(1)?.toIntOrNull()  ?: 0
        val minutes = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hours > 0 || minutes > 0) return hours * 60 + minutes
        // fallback: bare number
        return Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: 0
    }
}
