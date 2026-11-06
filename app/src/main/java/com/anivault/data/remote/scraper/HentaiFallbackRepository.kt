package com.anivault.data.remote.scraper

import com.anivault.core.Sanitizer
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.ScrapedEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.provider.ProviderResult

/**
 * Orchestrates H-Vault data retrieval across three [HentaiSource] backends:
 *
 *   1. **Primary**   — [HentaiScraperRepository]    (hanime.tv)
 *   2. **Fallback 1** — [WatchHentaiScraperRepository] (watchhentai.net)
 *   3. **Fallback 2** — [NhentaiApiRepository]        (nhentai.net JSON API)
 *
 * Search strategy: attempt each source in order until one returns ≥ 1 result.
 */
@Singleton
class HentaiFallbackRepository @Inject constructor(
    private val primary:    HentaiScraperRepository,
    private val fallback:   WatchHentaiScraperRepository,
    private val nhentai:    NhentaiApiRepository,
    @Named("vault") private val vaultProviders: ProviderRepository,
    private val hVaultDao:  HVaultDao
) {
    // ── Vault observation (source-agnostic) ────────────────────────────────

    fun observeAll(): Flow<List<HVaultEntry>> = hVaultDao.observeAll()

    fun observeByStatus(status: WatchStatus): Flow<List<HVaultEntry>> =
        hVaultDao.observeByStatus(status)

    // ── Search with automatic three-tier fallback ──────────────────────────

    suspend fun search(query: String, page: Int = 1): SearchResult =
        withContext(Dispatchers.IO) {
            val failures = mutableListOf<String>()
            var answered = false
            // 1. The H-Vault API sources (Settings inside H-Vault): reliable APIs with uncensored covers.
            when (val r = vaultProviders.search(query, page)) {
                is ProviderResult.Success -> {
                    answered = true
                    if (r.items.isNotEmpty()) return@withContext SearchResult(r.items.map { it.toScraped("v-${r.source.id}") }, r.source.name, false)
                }
                is ProviderResult.Failed -> r.failures.forEach { failures += "${it.sourceName}: ${it.kind.label}" }
                ProviderResult.NoSources -> failures += "No H-Vault API source is enabled"
            }
            // 2. Scrapers, which sites often block with bot checks.
            val scrapers: List<HentaiSource> = listOf(primary, fallback, nhentai)
            for (src in scrapers) {
                try {
                    val results = src.search(query, page)
                    answered = true
                    if (results.isNotEmpty()) return@withContext SearchResult(results, src.sourceName, true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += "${src.sourceName}: ${Sanitizer.clean(error.message, 100).ifBlank { "request failed" }}"
                }
            }
            if (!answered) {
                throw IllegalStateException("H-Vault search could not reach any source. ${failures.joinToString(" • ")}")
            }
            SearchResult(emptyList(), scrapers.last().sourceName, false)
        }

    // ── Detail fetch (tries all sources) ──────────────────────────────────

    suspend fun fetchDetail(detailUrl: String, preferFallback: Boolean = false): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            val sources = if (preferFallback)
                listOf(fallback, primary, nhentai)
            else
                listOf(primary, fallback, nhentai)

            sources.forEach { src ->
                val result = try {
                    src.fetchDetail(detailUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    null
                }
                if (result != null) return@withContext result
            }
            null
        }

    // ── Vault write-through (source-agnostic) ──────────────────────────────

    suspend fun saveToVault(entry: ScrapedEntry) = withContext(Dispatchers.IO) {
        val existing = hVaultDao.getById(entry.id)
        hVaultDao.upsert(
            HVaultEntry(
                id              = entry.id,
                title           = entry.title,
                imageUrl        = entry.coverUrl,
                totalEpisodes   = entry.episodeCount,
                watchedEpisodes = existing?.watchedEpisodes ?: 0,
                status          = existing?.status         ?: WatchStatus.PLAN_TO_WATCH,
                rating          = existing?.rating         ?: 0f,
                studio          = entry.studio,
                isCensored      = entry.isCensored,
                tags            = entry.tags,
                notes           = existing?.notes          ?: "",
                durationMinutes = entry.durationMinutes,
                updatedAt       = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateEntry(entry: HVaultEntry) = withContext(Dispatchers.IO) {
        hVaultDao.upsert(com.anivault.data.library.VaultRules.normalize(entry).copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        hVaultDao.deleteById(id)
    }

    suspend fun getAll(): List<HVaultEntry> = withContext(Dispatchers.IO) {
        hVaultDao.getAll()
    }

    suspend fun getById(id: String): HVaultEntry? = withContext(Dispatchers.IO) {
        hVaultDao.getById(id)
    }

    suspend fun upsertAll(entries: List<HVaultEntry>) = withContext(Dispatchers.IO) {
        hVaultDao.upsertAll(entries)
    }

    // ── Result wrapper ─────────────────────────────────────────────────────

    data class SearchResult(
        val entries: List<ScrapedEntry>,
        val sourceName: String,
        val usedFallback: Boolean
    )
}

internal fun com.anivault.data.provider.NormalizedAnime.toScraped(prefix: String) = ScrapedEntry(
    id = "$prefix:$providerId",
    title = title,
    coverUrl = coverUrl,
    tags = genres,
    studio = null,
    isCensored = false,
    episodeCount = episodes,
    durationMinutes = durationMinutes,
    detailUrl = "catalog:$prefix:$providerId"
)
