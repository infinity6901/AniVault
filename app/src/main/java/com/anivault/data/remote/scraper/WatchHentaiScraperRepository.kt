package com.anivault.data.remote.scraper

import com.anivault.data.remote.model.ScrapedEntry
import kotlinx.coroutines.Dispatchers
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
 * Scrapes [watchhentai.net](https://watchhentai.net) as a fallback source when
 * hanime.tv is slow to upload new episodes.
 *
 * ── Selector maintenance ────────────────────────────────────────────────────
 * All CSS selectors and URL constants are grouped in the companion object.
 * If the site's markup changes, only that block needs updating.
 *
 * NOTE: Selectors were written against watchhentai.net's markup as of early 2025.
 *       Verify them in DevTools → Elements if results look empty after a site redesign.
 * ────────────────────────────────────────────────────────────────────────────
 */
@Singleton
class WatchHentaiScraperRepository @Inject constructor(
    @Named("watchhentai") private val okHttpClient: OkHttpClient
) : HentaiSource {

    override val sourceName: String = "watchhentai.net"

    companion object {
        // ── Target site ────────────────────────────────────────────────────
        const val BASE_URL    = "https://watchhentai.net"
        // watchhentai uses a standard WordPress/custom search endpoint
        const val SEARCH_PATH = "/?s=%s&page=%d"

        // ── Search results page selectors ──────────────────────────────────
        // Each result card — adjust if the grid class changes
        private const val SEL_CARD      = "div.videos-list article, div.film_list-wrap div.flw-item"
        // Title text within the card
        private const val SEL_TITLE     = "h3.film-name, h2.name, div.title"
        // Thumbnail image
        private const val SEL_IMAGE     = "img.film-poster-img, img.lazyload, img.thumb"
        // Anchor wrapping the card (href leads to detail page)
        private const val SEL_LINK      = "a.film-poster, a.dynamic-name, a[href*='/hentai/']"
        // Episode count badge (optional — not all cards show it)
        private const val SEL_EP_BADGE  = "span.fdi-item:contains(Eps), div.tick-eps"

        // ── Detail page selectors ──────────────────────────────────────────
        private const val SEL_D_TITLE    = "h2.film-name, h1.title"
        private const val SEL_D_COVER    = "img.film-poster-img, img.cover, div.film-poster img"
        private const val SEL_D_STUDIO   = "div.item:contains(Studio) a, div.elements a[href*='/studio/']"
        private const val SEL_D_TAGS     = "div.item:contains(Tags) a, div.genres a, a[href*='/tag/']"
        private const val SEL_D_CENSORED = "div.item:contains(Censored)"
        private const val SEL_D_EP_COUNT = "div.item:contains(Episodes), span.tick-eps"
        private const val SEL_D_DURATION = "div.item:contains(Duration), span.fdi-duration"
    }

    // ── HentaiSource impl ──────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = BASE_URL + SEARCH_PATH.format(query.urlEncode(), page)
                parseSearchResults(fetchDocument(url))
            }.getOrElse { emptyList() }
        }

    override suspend fun fetchDetail(detailUrl: String): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                parseDetailPage(fetchDocument(detailUrl), detailUrl)
            }.getOrNull()
        }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$BASE_URL/")
            .get()
            .build()

        val body = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful)
                throw IOException("Unexpected HTTP ${response.code} from $url")
            response.body?.string()
                ?: throw IOException("Empty response body from $url")
        }

        return Jsoup.parse(body, url)
    }

    private fun parseSearchResults(doc: Document): List<ScrapedEntry> =
        doc.select(SEL_CARD).mapNotNull { card ->
            runCatching {
                // Find the primary anchor — used for the detail URL and ID
                val anchor = card.selectFirst(SEL_LINK) ?: return@runCatching null
                val href   = anchor.attr("href").let {
                    if (it.startsWith("http")) it else "$BASE_URL$it"
                }
                val id = href.trimEnd('/').substringAfterLast("/")

                val imgEl    = card.selectFirst(SEL_IMAGE)
                val coverUrl = imgEl?.attr("data-src")?.ifBlank { null }
                    ?: imgEl?.attr("src") ?: ""

                val title = card.selectFirst(SEL_TITLE)?.text()?.trim()
                    ?.ifBlank { null }
                    ?: anchor.attr("title").ifBlank { "Unknown" }

                val epCount = card.selectFirst(SEL_EP_BADGE)
                    ?.text()
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull() ?: 1

                ScrapedEntry(
                    id              = id,
                    title           = title,
                    coverUrl        = coverUrl,
                    tags            = emptyList(),   // enriched by fetchDetail
                    studio          = null,
                    isCensored      = false,
                    episodeCount    = epCount,
                    durationMinutes = 0,
                    detailUrl       = href
                )
            }.getOrNull()
        }

    private fun parseDetailPage(doc: Document, detailUrl: String): ScrapedEntry {
        val id    = detailUrl.trimEnd('/').substringAfterLast("/")
        val title = doc.selectFirst(SEL_D_TITLE)?.text()?.trim().orEmpty().ifBlank { "Unknown" }

        val imgEl = doc.selectFirst(SEL_D_COVER)
        val cover = imgEl?.attr("data-src")?.ifBlank { null } ?: imgEl?.attr("src") ?: ""

        val studio = doc.selectFirst(SEL_D_STUDIO)?.text()?.trim()?.ifBlank { null }
        val tags   = doc.select(SEL_D_TAGS)
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // watchhentai.net typically shows "Censored: Yes / No" as text
        val isCens = doc.selectFirst(SEL_D_CENSORED)
            ?.text()
            ?.contains("yes", ignoreCase = true) == true

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

    /** Handles "1:23:45" (h:mm:ss), "45:30" (mm:ss), "30 min", bare numbers. */
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
}
