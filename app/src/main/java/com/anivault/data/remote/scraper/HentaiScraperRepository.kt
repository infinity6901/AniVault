package com.anivault.data.remote.scraper

import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.cloudflare.CloudflareBypassException
import com.anivault.data.remote.cloudflare.CloudflareInterceptor
import com.anivault.data.remote.model.ScrapedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Scrapes hanime.tv as the **primary** H-Vault source.
 *
 * Implements [HentaiSource] so it can be swapped in/out by [HentaiFallbackRepository].
 *
 * All CSS selector constants are grouped in the companion object so they can be
 * updated quickly when the target site's markup changes.
 */
@Singleton
class HentaiScraperRepository @Inject constructor(
    @Named("scraper")   private val okHttpClient: OkHttpClient,
    private val cfInterceptor: CloudflareInterceptor,
    private val hVaultDao: HVaultDao
) : HentaiSource {

    override val sourceName: String = "hanime.tv"

    companion object {
        // ── Target site ────────────────────────────────────────────────────
        const val BASE_URL    = "https://hanime.tv"
        const val SEARCH_PATH = "/search?query=%s&page=%d"

        // ── JSoup CSS selectors ────────────────────────────────────────────
        // Search result page
        private const val SEL_CARD      = "div.search-results-row div.search-result"
        private const val SEL_TITLE     = "div.search-result-title"
        private const val SEL_IMAGE     = "img.search-result-image"
        private const val SEL_LINK      = "a.search-result-cover-wrap"
        private const val SEL_EP_BADGE  = "span.episode-count"

        // Detail page
        private const val SEL_D_TITLE    = "h1.hvp-title, h1.video-title"
        private const val SEL_D_COVER    = "img.hvp-cover, img.video-cover"
        private const val SEL_D_STUDIO   = "div.hvpi-studio a"
        private const val SEL_D_TAGS     = "div.hvpi-tags span.tag"
        private const val SEL_D_CENSORED = "div.hvpi-censored"
        private const val SEL_D_EP_COUNT = "div.hvpi-episodes, span.episode-count"
        private const val SEL_D_DURATION = "div.hvpi-duration, span.video-duration"
    }

    init {
        cfInterceptor.targetHost =
            BASE_URL.removePrefix("https://").removePrefix("http://")
    }

    // ── HentaiSource impl ──────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        withContext(Dispatchers.IO) {
            cfInterceptor.prewarm(BASE_URL)
            val url = BASE_URL + SEARCH_PATH.format(query.urlEncode(), page)
            val doc = fetchDocument(url)
            parseSearchResults(doc)
        }

    override suspend fun fetchDetail(detailUrl: String): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                cfInterceptor.prewarm(BASE_URL)
                parseDetailPage(fetchDocument(detailUrl), detailUrl)
            }.getOrNull()
        }

    // ── Vault helpers (kept here for backward-compat; prefer HentaiFallbackRepository) ──

    fun observeAll(): Flow<List<HVaultEntry>> = hVaultDao.observeAll()

    fun observeByStatus(status: WatchStatus): Flow<List<HVaultEntry>> =
        hVaultDao.observeByStatus(status)

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

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        hVaultDao.deleteById(id)
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun fetchDocument(url: String, isRetry: Boolean = false): Document {
        val request = Request.Builder().url(url).get().build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: CloudflareBypassException) {
            if (!isRetry) return fetchDocument(url, isRetry = true)
            throw e
        }

        if (!response.isSuccessful && !isRetry) {
            response.close()
            cfInterceptor.invalidate()
            return fetchDocument(url, isRetry = true)
        }

        val body = response.use { it.body?.string() }
            ?: throw IOException("Empty response body from: $url")

        return Jsoup.parse(body, url)
    }

    private fun parseSearchResults(doc: Document): List<ScrapedEntry> =
        doc.select(SEL_CARD).mapNotNull { card ->
            runCatching {
                val anchor = card.selectFirst(SEL_LINK) ?: return@runCatching null
                val href   = anchor.attr("href").ensureLeadingSlash()
                val detailUrl = "$BASE_URL$href"
                val id        = href.trimEnd('/').substringAfterLast("/")

                val imgEl    = card.selectFirst(SEL_IMAGE)
                val coverUrl = imgEl?.attr("data-src")?.ifBlank { null }
                    ?: imgEl?.attr("src") ?: ""

                val title   = card.selectFirst(SEL_TITLE)?.text()?.trim().orEmpty()
                    .ifBlank { "Unknown" }
                val epCount = card.selectFirst(SEL_EP_BADGE)
                    ?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1

                ScrapedEntry(
                    id              = id,
                    title           = title,
                    coverUrl        = coverUrl,
                    tags            = emptyList(),
                    studio          = null,
                    isCensored      = false,
                    episodeCount    = epCount,
                    durationMinutes = 0,
                    detailUrl       = detailUrl
                )
            }.getOrNull()
        }

    private fun parseDetailPage(doc: Document, detailUrl: String): ScrapedEntry {
        val id     = detailUrl.trimEnd('/').substringAfterLast("/")
        val title  = doc.selectFirst(SEL_D_TITLE)?.text()?.trim().orEmpty().ifBlank { "Unknown" }

        val imgEl  = doc.selectFirst(SEL_D_COVER)
        val cover  = imgEl?.attr("data-src")?.ifBlank { null } ?: imgEl?.attr("src") ?: ""

        val studio = doc.selectFirst(SEL_D_STUDIO)?.text()?.trim()?.ifBlank { null }
        val tags   = doc.select(SEL_D_TAGS)
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val isCens = doc.selectFirst(SEL_D_CENSORED) != null

        val epCount  = doc.selectFirst(SEL_D_EP_COUNT)
            ?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
        val duration = parseDuration(doc.selectFirst(SEL_D_DURATION)?.text() ?: "")

        return ScrapedEntry(
            id              = id,
            title           = title,
            coverUrl        = cover,
            tags            = tags,
            studio          = studio,
            isCensored      = isCens,
            episodeCount    = epCount,
            durationMinutes = duration,
            detailUrl       = detailUrl
        )
    }

    private fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        return when {
            text.contains(":") -> {
                val parts = text.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
                when (parts.size) {
                    3    -> parts[0] * 60 + parts[1]
                    2    -> parts[0]
                    else -> 0
                }
            }
            text.contains("min", ignoreCase = true) ->
                text.filter { it.isDigit() }.toIntOrNull() ?: 0
            else ->
                text.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"
}
