package com.anivault.data.remote.scraper

import com.anivault.core.Sanitizer
import com.anivault.data.library.Franchise
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.provider.ProviderResult
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.provider.AniListLookup
import com.anivault.data.provider.StreamingLink
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.model.VaultEpisodePreview
import com.anivault.data.remote.model.VaultRichDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates H-Vault data retrieval and uncensored cover resolution across:
 *   1. **Primary Scraper**   — [HentaiScraperRepository]    (Aniyomi Hanime JSON API + fallback)
 *   2. **Fallback Scraper 1** — [WatchHentaiScraperRepository] (Aniyomi WP-JSON / DooPlay / Multi-Theme / Multi-Mirror)
 *   3. **Fallback Scraper 2** — [NhentaiApiRepository]        (nhentai.net JSON API)
 *   4. **Catalog Providers**  — [ProviderRepository]          (AniList 18+, Kitsu 18+, Tenrai/MAL)
 *
 * When [com.anivault.core.AppPrefs.vaultScraperPriority] is enabled (default `true`), uncensored
 * scrapers are queried **first** so H-Vault never defaults to heavily censored/blurred AniList or MAL
 * cover images. Additionally, when [com.anivault.core.AppPrefs.vaultAutoUncensorCovers] is enabled,
 * any catalog result or library entry moved into H-Vault with a censored catalog cover URL is
 * automatically upgraded using the best matching uncensored cover from the scrapers.
 */
@Singleton
class HentaiFallbackRepository @Inject constructor(
    private val primary:    HentaiScraperRepository,
    private val fallback:   WatchHentaiScraperRepository,
    private val nhentai:    NhentaiApiRepository,
    @Named("vault") private val vaultProviders: ProviderRepository,
    private val hVaultDao:  HVaultDao,
    private val remoteOps:  com.anivault.data.sync.RemoteOps,
    private val prefs:      com.anivault.core.AppPrefs,
    private val aniListLookup: AniListLookup
) {
    // ── Vault observation (source-agnostic) ────────────────────────────────

    fun observeAll(): Flow<List<HVaultEntry>> = hVaultDao.observeAll()

    fun observeByStatus(status: WatchStatus): Flow<List<HVaultEntry>> =
        hVaultDao.observeByStatus(status)

    // ── Search with Scraper Priority & Auto-Uncensoring ────────────────────

    suspend fun search(query: String, page: Int = 1): SearchResult =
        withContext(Dispatchers.IO) {
            val failures = mutableListOf<String>()
            var answered = false
            var firstAnsweredSource = ""
            val scrapers: List<HentaiSource> = listOf(primary, fallback, nhentai)

            if (prefs.vaultScraperPriority) {
                // 1. Scrapers FIRST: deliver true uncensored covers directly from Aniyomi-compatible engines.
                for (src in scrapers) {
                    try {
                        val results = src.search(query, page)
                        if (!answered) firstAnsweredSource = src.sourceName
                        answered = true
                        if (results.isNotEmpty() || !prefs.fallbackOnEmpty) {
                            return@withContext SearchResult(results, src.sourceName, false)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures += "${src.sourceName}: ${Sanitizer.clean(error.message, 100).ifBlank { "request failed" }}"
                    }
                }

                // 2. Fallback to catalog API sources if all scrapers returned empty or failed.
                when (val r = vaultProviders.search(query, page)) {
                    is ProviderResult.Success -> {
                        if (!answered) firstAnsweredSource = r.source.name
                        answered = true
                        val mapped = r.items.map { it.toScraped("v-${r.source.id}") }
                        val enriched = if (prefs.vaultAutoUncensorCovers) enrichCatalogCovers(query, mapped) else mapped
                        return@withContext SearchResult(enriched, r.source.name, true)
                    }
                    is ProviderResult.Failed -> r.failures.forEach { failures += "${it.sourceName}: ${it.kind.label}" }
                    ProviderResult.NoSources -> failures += "No H-Vault API source is enabled"
                }
            } else {
                // Legacy order: Catalog API sources first, but automatically upgrade censored covers if enabled.
                when (val r = vaultProviders.search(query, page)) {
                    is ProviderResult.Success -> {
                        answered = true
                        firstAnsweredSource = r.source.name
                        if (r.items.isNotEmpty() || !prefs.fallbackOnEmpty) {
                            val mapped = r.items.map { it.toScraped("v-${r.source.id}") }
                            val enriched = if (prefs.vaultAutoUncensorCovers) enrichCatalogCovers(query, mapped) else mapped
                            return@withContext SearchResult(enriched, r.source.name, false)
                        }
                    }
                    is ProviderResult.Failed -> r.failures.forEach { failures += "${it.sourceName}: ${it.kind.label}" }
                    ProviderResult.NoSources -> failures += "No H-Vault API source is enabled"
                }

                for (src in scrapers) {
                    try {
                        val results = src.search(query, page)
                        if (!answered) firstAnsweredSource = src.sourceName
                        answered = true
                        if (results.isNotEmpty() || !prefs.fallbackOnEmpty) {
                            return@withContext SearchResult(results, src.sourceName, true)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures += "${src.sourceName}: ${Sanitizer.clean(error.message, 100).ifBlank { "request failed" }}"
                    }
                }
            }

            if (!answered) {
                throw IllegalStateException("H-Vault search could not reach any source. ${failures.joinToString(" • ")}")
            }
            SearchResult(emptyList(), firstAnsweredSource.ifBlank { scrapers.first().sourceName }, false)
        }

    /**
     * Replaces censored AniList/MAL/Kitsu covers in catalog search results with uncensored covers
     * fetched from the scraper pool.
     */
    private suspend fun enrichCatalogCovers(query: String, items: List<ScrapedEntry>): List<ScrapedEntry> {
        if (items.isEmpty() || items.none { UncensoredCoverMatcher.isCensoredCatalogCover(it.coverUrl) }) {
            return items
        }
        val pool = fetchUncensoredCandidates(query)
        if (pool.isEmpty()) return items
        return items.map { item ->
            if (!UncensoredCoverMatcher.isCensoredCatalogCover(item.coverUrl)) return@map item
            val match = UncensoredCoverMatcher.bestMatch(item.title, emptyList(), pool)
            if (match != null && match.coverUrl.isNotBlank()) {
                item.copy(
                    coverUrl = match.coverUrl,
                    studio = item.studio ?: match.studio,
                    isCensored = match.isCensored,
                    tags = (item.tags + match.tags).distinct()
                )
            } else item
        }
    }

    /**
     * Queries the uncensored scrapers (`hanime.tv`, `watchhentai` multi-mirror, `nhentai`) concurrently
     * for [title] (and its base franchise title if different) and returns deduplicated candidate
     * entries with valid uncensored covers ranked by title similarity.
     */
    suspend fun fetchUncensoredCandidates(title: String): List<ScrapedEntry> = withContext(Dispatchers.IO) {
        val cleanQuery = Franchise.cleanTitle(title).ifBlank { title }.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val candidates = coroutineScope {
            val d1 = async {
                runCatching { primary.search(cleanQuery, 1) }
                    .getOrDefault(emptyList())
                    .map { if (it.sourceName.isNotBlank()) it else it.copy(sourceName = "hanime.tv") }
            }
            val d2 = async {
                runCatching { fallback.search(cleanQuery, 1) }
                    .getOrDefault(emptyList())
                    .map { if (it.sourceName.isNotBlank()) it else it.copy(sourceName = it.displaySource) }
            }
            val d3 = async {
                runCatching { nhentai.search(cleanQuery, 1) }
                    .getOrDefault(emptyList())
                    .map { if (it.sourceName.isNotBlank()) it else it.copy(sourceName = "nhentai.net") }
            }
            d1.await() + d2.await() + d3.await()
        }

        candidates
            .filter { it.coverUrl.isNotBlank() && !UncensoredCoverMatcher.isCensoredCatalogCover(it.coverUrl) }
            .distinctBy { it.coverUrl }
            .sortedByDescending { UncensoredCoverMatcher.similarity(title, it.title) }
    }

    /**
     * Finds the single best matching uncensored [ScrapedEntry] for [title] / [altTitles].
     */
    suspend fun resolveUncensoredCover(title: String, altTitles: List<String> = emptyList()): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            val queries = (listOf(title, Franchise.cleanTitle(title)) + altTitles)
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()

            for (q in queries.take(3)) {
                val pool = fetchUncensoredCandidates(q)
                val match = UncensoredCoverMatcher.bestMatch(title, altTitles, pool)
                if (match != null) return@withContext match
            }
            null
        }

    /**
     * Scans all entries currently stored in H-Vault and upgrades any blank or censored catalog covers
     * (`s4.anilist.co`, `cdn.myanimelist.net`, `media.kitsu.io`, etc.) using the uncensored scrapers.
     * Returns the number of entries whose cover art was upgraded.
     */
    suspend fun uncensorAllVaultCovers(): Int = withContext(Dispatchers.IO) {
        val all = hVaultDao.getAll()
        val targets = all.filter { UncensoredCoverMatcher.isCensoredCatalogCover(it.imageUrl) }
        var upgradedCount = 0
        val updatedEntries = mutableListOf<HVaultEntry>()

        for (entry in targets) {
            val match = runCatching { resolveUncensoredCover(entry.title) }.getOrNull()
            if (match != null && match.coverUrl.isNotBlank() && match.coverUrl != entry.imageUrl) {
                upgradedCount++
                updatedEntries += entry.copy(
                    imageUrl = match.coverUrl,
                    studio = entry.studio?.takeIf { it.isNotBlank() } ?: match.studio,
                    isCensored = match.isCensored,
                    tags = if (entry.tags.isEmpty()) match.tags else (entry.tags + match.tags).distinct(),
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        if (updatedEntries.isNotEmpty()) {
            hVaultDao.upsertAll(updatedEntries)
        }
        upgradedCount
    }

    /**
     * Fetches comprehensive metadata and episode preview thumbnails for [entry] by combining:
     *  1. Stashed library metadata (if moved from the normal library)
     *  2. Uncensored scrapers (`hanime.tv` v8 API & search, `watchhentai` multi-mirror, `nhentai.net`)
     *  3. Catalog lookup (`AniList 18+` GraphQL) for Characters & Voice Actors, Franchise Watch Order & Related,
     *     Studios, Format/Year/Status, Community Score, and Synopsis.
     */
    suspend fun fetchRichDetails(entry: HVaultEntry): VaultRichDetails = withContext(Dispatchers.IO) {
        val stashed = remoteOps.stash(entry.id)
        val linkedMap = (stashed?.let { RemoteIds.parse(it.remoteIds) } ?: emptyMap()) +
            RemoteIds.parse(remoteOps.link(entry.id))

        val idParts = entry.id.split(":")
        val anilistId: Int? = linkedMap["anilist"]?.toIntOrNull()
            ?: when {
                entry.id.startsWith("sync:anilist:") -> idParts.getOrNull(2)?.toIntOrNull()
                entry.id.contains("anilist") -> idParts.lastOrNull()?.toIntOrNull()
                else -> null
            }
        val malId: Int? = linkedMap["mal"]?.toIntOrNull()
            ?: when {
                entry.id.startsWith("sync:mal:") -> idParts.getOrNull(2)?.toIntOrNull()
                entry.id.contains("mal") || entry.id.contains("jikan") -> idParts.lastOrNull()?.toIntOrNull()
                else -> null
            }

        val cleanTitle = Franchise.cleanTitle(entry.title).ifBlank { entry.title }.trim()

        coroutineScope {
            val catalogDeferred = async {
                runCatching { aniListLookup.lookupForVault(entry.title, anilistId, malId) }
                    .getOrDefault(null to com.anivault.data.provider.MediaExtras())
            }

            val hanimeDeferred = async {
                runCatching {
                    val directDetail = if (entry.id.startsWith("hanime:")) {
                        val slug = entry.id.removePrefix("hanime:")
                        primary.fetchDetail("https://hanime.tv/videos/hentai/$slug")
                    } else null

                    val searchHits = if (cleanTitle.isNotBlank()) {
                        runCatching { primary.search(cleanTitle, 1) }.getOrDefault(emptyList())
                            .filter { UncensoredCoverMatcher.similarity(entry.title, it.title) >= 0.35 }
                    } else emptyList()

                    val bestHit = directDetail
                        ?: searchHits.firstOrNull()?.let { hit ->
                            runCatching { primary.fetchDetail(hit.detailUrl) }.getOrNull() ?: hit
                        }

                    val searchPreviews = searchHits.mapIndexedNotNull { idx, hit ->
                        val img = hit.previewUrl.ifBlank { hit.coverUrl }
                        if (img.isBlank()) null
                        else VaultEpisodePreview(
                            episodeNumber = HanimeAniyomiCodec.extractEpisodeNumber(hit.title, idx + 1),
                            title = hit.title,
                            previewImageUrl = img,
                            durationMinutes = hit.durationMinutes,
                            releaseDate = hit.releaseYear?.toString().orEmpty(),
                            detailUrl = hit.detailUrl,
                            sourceName = "hanime.tv"
                        )
                    }
                    bestHit to searchPreviews
                }.getOrDefault(null to emptyList())
            }

            val watchHentaiDeferred = async {
                runCatching {
                    val searchHits = if (cleanTitle.isNotBlank()) {
                        runCatching { fallback.search(cleanTitle, 1) }.getOrDefault(emptyList())
                            .filter { UncensoredCoverMatcher.similarity(entry.title, it.title) >= 0.35 }
                    } else emptyList()

                    val bestMatch = UncensoredCoverMatcher.bestMatch(entry.title, stashed?.altTitles.orEmpty(), searchHits, minScore = 0.35)
                    val detail = bestMatch?.let { m ->
                        runCatching { fallback.fetchDetail(m.detailUrl) }.getOrNull() ?: m
                    }

                    val searchPreviews = searchHits.mapIndexedNotNull { idx, hit ->
                        val img = hit.previewUrl.ifBlank { hit.coverUrl }
                        if (img.isBlank()) null
                        else VaultEpisodePreview(
                            episodeNumber = HanimeAniyomiCodec.extractEpisodeNumber(hit.title, idx + 1),
                            title = hit.title,
                            previewImageUrl = img,
                            durationMinutes = if (hit.durationMinutes > 0) hit.durationMinutes else 24,
                            releaseDate = hit.releaseYear?.toString().orEmpty(),
                            detailUrl = hit.detailUrl,
                            sourceName = hit.displaySource
                        )
                    }
                    detail to searchPreviews
                }.getOrDefault(null to emptyList())
            }

            val nhentaiDeferred = async {
                runCatching {
                    if (entry.id.startsWith("nhentai:")) {
                        nhentai.fetchDetail(entry.id)
                    } else null
                }.getOrNull()
            }

            val (catalogMedia, catalogExtras) = catalogDeferred.await()
            val (hanimeDetail, hanimeSearchPreviews) = hanimeDeferred.await()
            val (whDetail, whSearchPreviews) = watchHentaiDeferred.await()
            val nhDetail = nhentaiDeferred.await()

            // Combine episode previews (preferring structured detail episode lists, then search episode hits)
            val combinedPreviews = (
                hanimeDetail?.episodePreviews.orEmpty() +
                    whDetail?.episodePreviews.orEmpty() +
                    hanimeSearchPreviews +
                    whSearchPreviews +
                    nhDetail?.episodePreviews.orEmpty()
                )
                .filter { it.previewImageUrl.isNotBlank() }
                .distinctBy { it.previewImageUrl }
                .sortedWith(compareBy<VaultEpisodePreview> { it.episodeNumber }.thenBy { it.title })

            val synopsis = listOfNotNull(
                hanimeDetail?.synopsis?.takeIf { it.isNotBlank() },
                whDetail?.synopsis?.takeIf { it.isNotBlank() },
                stashed?.synopsis?.takeIf { it.isNotBlank() },
                catalogMedia?.synopsis?.takeIf { it.isNotBlank() }
            ).maxByOrNull { it.length }.orEmpty()

            val format = stashed?.format?.takeIf { it.isNotBlank() }
                ?: catalogMedia?.format?.takeIf { it.isNotBlank() }
                ?: "OVA"

            val year = stashed?.year
                ?: catalogMedia?.year
                ?: hanimeDetail?.releaseYear
                ?: whDetail?.releaseYear

            val season = stashed?.season?.takeIf { it.isNotBlank() }
                ?: catalogMedia?.season?.takeIf { it.isNotBlank() }
                .orEmpty()

            val airingStatus = stashed?.airingStatus?.takeIf { it.isNotBlank() }
                ?: catalogMedia?.airingStatus?.takeIf { it.isNotBlank() }
                .orEmpty()

            val communityScore = listOfNotNull(
                stashed?.providerScore?.takeIf { it > 0f },
                catalogMedia?.averageScore?.takeIf { it > 0f },
                hanimeDetail?.ratingScore?.takeIf { it > 0f },
                whDetail?.ratingScore?.takeIf { it > 0f }
            ).firstOrNull() ?: 0f

            val studios = (
                listOfNotNull(
                    entry.studio?.takeIf { it.isNotBlank() },
                    hanimeDetail?.studio?.takeIf { it.isNotBlank() },
                    whDetail?.studio?.takeIf { it.isNotBlank() },
                    nhDetail?.studio?.takeIf { it.isNotBlank() }
                ) + catalogExtras.studios
                ).distinct()

            val genres = (
                catalogMedia?.genres.orEmpty() +
                    stashed?.genres.orEmpty() +
                    entry.tags +
                    hanimeDetail?.tags.orEmpty() +
                    whDetail?.tags.orEmpty()
                ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

            val sourcesUsed = buildList {
                if (hanimeDetail != null || hanimeSearchPreviews.isNotEmpty()) add("hanime.tv")
                if (whDetail != null || whSearchPreviews.isNotEmpty()) add(whDetail?.displaySource ?: "watchhentai.net")
                if (nhDetail != null) add("nhentai.net")
                if (catalogMedia != null) add("AniList")
                else if (stashed?.sourceName?.isNotBlank() == true) add(stashed.sourceName)
            }.distinct()

            val scraperLinks = buildList {
                hanimeDetail?.detailUrl?.takeIf { it.startsWith("https://") }?.let { add(StreamingLink("hanime.tv", it)) }
                whDetail?.detailUrl?.takeIf { it.startsWith("https://") }?.let { add(StreamingLink(whDetail.displaySource, it)) }
                nhDetail?.detailUrl?.takeIf { it.startsWith("https://") }?.let { add(StreamingLink("nhentai.net", it)) }
            }
            val allLinks = (scraperLinks + catalogExtras.links).distinctBy { it.url }

            val resolvedEps = maxOf(
                entry.totalEpisodes,
                catalogMedia?.episodes ?: 0,
                hanimeDetail?.episodeCount ?: 0,
                whDetail?.episodeCount ?: 0,
                combinedPreviews.size
            )
            val resolvedDur = when {
                entry.durationMinutes > 0 -> entry.durationMinutes
                (hanimeDetail?.durationMinutes ?: 0) > 0 -> hanimeDetail!!.durationMinutes
                (catalogMedia?.durationMinutes ?: 0) > 0 -> catalogMedia!!.durationMinutes
                else -> 24
            }

            // Backfill missing studio / totalEpisodes / durationMinutes / uncensored cover onto the HVaultEntry
            val bestUncensoredCover = if (prefs.vaultAutoUncensorCovers && UncensoredCoverMatcher.isCensoredCatalogCover(entry.imageUrl)) {
                hanimeDetail?.coverUrl?.takeIf { it.isNotBlank() }
                    ?: whDetail?.coverUrl?.takeIf { it.isNotBlank() }
                    ?: entry.imageUrl
            } else entry.imageUrl

            val currentInDb = hVaultDao.getById(entry.id)
            if (currentInDb != null) {
                val newStudio = currentInDb.studio?.takeIf { it.isNotBlank() } ?: studios.firstOrNull()
                val newEps = if (currentInDb.totalEpisodes > 0) currentInDb.totalEpisodes else resolvedEps
                val newDur = if (currentInDb.durationMinutes > 0) currentInDb.durationMinutes else resolvedDur
                val newImg = if (UncensoredCoverMatcher.isCensoredCatalogCover(currentInDb.imageUrl) && bestUncensoredCover.isNotBlank()) bestUncensoredCover else currentInDb.imageUrl
                if (newStudio != currentInDb.studio || newEps != currentInDb.totalEpisodes || newDur != currentInDb.durationMinutes || newImg != currentInDb.imageUrl) {
                    hVaultDao.upsert(
                        currentInDb.copy(
                            studio = newStudio,
                            totalEpisodes = newEps,
                            durationMinutes = newDur,
                            imageUrl = newImg
                        )
                    )
                }
            }

            VaultRichDetails(
                synopsis = synopsis,
                format = format,
                year = year,
                season = season,
                airingStatus = airingStatus,
                communityScore = communityScore,
                sourceLabel = sourcesUsed.joinToString(" · "),
                studios = studios,
                genres = genres,
                altTitles = stashed?.altTitles.orEmpty(),
                episodeCount = resolvedEps,
                durationMinutes = resolvedDur,
                episodePreviews = combinedPreviews,
                galleryImages = combinedPreviews.map { it.previewImageUrl }.distinct(),
                characters = catalogExtras.characters,
                relations = catalogExtras.relations,
                links = allLinks
            )
        }
    }

    suspend fun testCustomMirror(url: String): MirrorTestResult =
        fallback.testMirror(url)

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
        var resolvedCover = entry.coverUrl
        var resolvedStudio = entry.studio
        var resolvedCensored = entry.isCensored
        var resolvedTags = entry.tags

        if (prefs.vaultAutoUncensorCovers && UncensoredCoverMatcher.isCensoredCatalogCover(resolvedCover)) {
            val match = runCatching { resolveUncensoredCover(entry.title) }.getOrNull()
            if (match != null && match.coverUrl.isNotBlank()) {
                resolvedCover = match.coverUrl
                resolvedStudio = resolvedStudio ?: match.studio
                resolvedCensored = match.isCensored
                resolvedTags = (resolvedTags + match.tags).distinct()
            }
        }

        hVaultDao.upsert(
            HVaultEntry(
                id              = entry.id,
                title           = entry.title,
                imageUrl        = resolvedCover,
                totalEpisodes   = entry.episodeCount,
                watchedEpisodes = existing?.watchedEpisodes ?: 0,
                status          = existing?.status         ?: WatchStatus.PLAN_TO_WATCH,
                rating          = existing?.rating         ?: 0f,
                studio          = resolvedStudio,
                isCensored      = resolvedCensored,
                tags            = resolvedTags,
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
        val ids = com.anivault.data.sync.VaultOwnership.keysOfVaultId(id) + com.anivault.data.sync.VaultOwnership.keysOf(remoteOps.link(id))
        if (prefs.deleteOnAccounts) ids.forEach { k -> val (s, m) = k.split(":", limit = 2); remoteOps.enqueue(com.anivault.data.sync.RemoteOp(com.anivault.data.sync.OpType.DELETE, s, m)) }
        remoteOps.setLink(id, "")
        remoteOps.setStash(id, null)
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

/**
 * Pure, JVM-testable matcher that detects censored catalog cover URLs (AniList, MAL, Kitsu, etc.)
 * and ranks uncensored scraper candidates against an anime's canonical and alternate titles.
 */
object UncensoredCoverMatcher {

    private val CENSORED_CATALOG_HOSTS = listOf(
        "anilist.co",
        "myanimelist.net",
        "kitsu.io",
        "kitsu.app",
        "shikimori.one",
        "anidb.net",
        "simkl.in"
    )

    private val NOISE_TOKENS = setOf(
        "the", "animation", "ova", "ona", "special", "episode", "ep",
        "uncensored", "censored", "hd", "subbed", "raw", "eng", "sub"
    )

    /**
     * Returns `true` if [url] is blank or hosted on a mainstream anime catalog CDN
     * (which blurs, crops, or censors 18+ cover art).
     */
    fun isCensoredCatalogCover(url: String?): Boolean {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return true
        val lower = clean.lowercase()
        return CENSORED_CATALOG_HOSTS.any { host -> lower.contains(host) }
    }

    /**
     * Normalizes an anime/hentai title for cross-site comparison by stripping punctuation,
     * Episode/OVA suffixes, and common noise words (`The Animation`, `Uncensored`, `Episode 1`).
     */
    fun normalizeForMatch(title: String): String {
        val base = Franchise.cleanTitle(title)
            .lowercase()
            .replace(Regex("""\b(?:episode|ep)\s*\d+\b"""), " ")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
        val tokens = base.split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in NOISE_TOKENS }
        return if (tokens.isNotEmpty()) tokens.joinToString(" ") else base.trim()
    }

    /**
     * Computes a similarity score in `[0.0, 1.0]` combining token Jaccard overlap, prefix/containment,
     * and character Dice coefficient.
     */
    fun similarity(a: String, b: String): Double {
        val na = normalizeForMatch(a)
        val nb = normalizeForMatch(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val tokensA = na.split(" ").filter { it.isNotBlank() }.toSet()
        val tokensB = nb.split(" ").filter { it.isNotBlank() }.toSet()
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = max(1, tokensA.union(tokensB).size).toDouble()
        val tokenScore = intersection / union

        val containmentBonus = if (na.contains(nb) || nb.contains(na)) {
            min(na.length, nb.length).toDouble() / max(na.length, nb.length).toDouble()
        } else 0.0

        val bigramsA = if (na.length >= 2) (0 until na.length - 1).map { na.substring(it, it + 2) } else listOf(na)
        val bigramsB = if (nb.length >= 2) (0 until nb.length - 1).map { nb.substring(it, it + 2) } else listOf(nb)
        val setA = bigramsA.toSet()
        val setB = bigramsB.toSet()
        val dice = (2.0 * setA.intersect(setB).size) / max(1, setA.size + setB.size)

        return max(tokenScore * 0.65 + dice * 0.35, containmentBonus * 0.85 + dice * 0.15).coerceIn(0.0, 1.0)
    }

    /**
     * Selects the best uncensored cover candidate from [candidates] matching [targetTitle] or any
     * of [altTitles], requiring a minimum similarity threshold of `0.38` so unrelated results are never picked.
     */
    fun bestMatch(
        targetTitle: String,
        altTitles: List<String> = emptyList(),
        candidates: List<ScrapedEntry>,
        minScore: Double = 0.38
    ): ScrapedEntry? {
        val validCandidates = candidates.filter {
            it.coverUrl.isNotBlank() && !isCensoredCatalogCover(it.coverUrl)
        }
        if (validCandidates.isEmpty()) return null

        val allTargets = (listOf(targetTitle) + altTitles).map { it.trim() }.filter { it.isNotBlank() }
        if (allTargets.isEmpty()) return validCandidates.first()

        var bestCandidate: ScrapedEntry? = null
        var bestScore = -1.0

        for (candidate in validCandidates) {
            val rawScore = allTargets.maxOf { t -> similarity(t, candidate.title) }
            // Prefer uncensored entries when scores are close
            val uncensoredBonus = if (!candidate.isCensored) 0.04 else 0.0
            val totalScore = rawScore + uncensoredBonus
            if (rawScore >= minScore && totalScore > bestScore) {
                bestScore = totalScore
                bestCandidate = candidate
            }
        }
        return bestCandidate
    }
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
    detailUrl = "catalog:$prefix:$providerId",
    sourceName = providerName.ifBlank { prefix }
)
