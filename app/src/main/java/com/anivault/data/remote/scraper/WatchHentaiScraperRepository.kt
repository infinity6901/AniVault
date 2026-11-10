package com.anivault.data.remote.scraper

import com.anivault.core.AppPrefs
import com.anivault.core.Sanitizer
import com.anivault.data.provider.SourceValidator
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.model.VaultEpisodePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class MirrorTestResult(
    val ok: Boolean,
    val message: String,
    val durationMs: Long = 0L
)

/**
 * Pure, JVM-testable helper for managing, normalizing, and validating custom scraper mirror URLs.
 */
object CustomMirrorUrls {

    fun parseList(raw: String): List<String> =
        raw.split(",", ";", "\n", " ")
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("https://") && it.toHttpUrlOrNull() != null }
            .distinct()

    fun encodeList(urls: List<String>): String =
        urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    fun normalizeAndValidate(input: String): Result<String> {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return Result.failure(IllegalArgumentException("Enter a mirror URL"))
        if (raw.startsWith("http://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Mirror URL must use https://"))
        }
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val parsed = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid URL format"))
        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Mirror URL must use https://"))
        }
        if (parsed.host.isBlank() || !parsed.host.contains(".")) {
            return Result.failure(IllegalArgumentException("Enter a valid domain name"))
        }
        if (SourceValidator.isPrivateHost(parsed.host)) {
            return Result.failure(IllegalArgumentException("Private or local network addresses are not allowed"))
        }
        val cleanPath = parsed.encodedPath.trimEnd('/')
        val normalized = "https://${parsed.host.lowercase()}${if (parsed.port != 443) ":${parsed.port}" else ""}$cleanPath"
        return Result.success(normalized)
    }
}

/**
 * Self-healing multi-engine scraper modelled on the Aniyomi community extension architecture
 * (`eu.kanade.tachiyomi.animeextension.all.watchhentai` / `hentaihaven` / `dooplay` / `madara` / `wpjson`).
 */
@Singleton
class WatchHentaiScraperRepository @Inject constructor(
    @Named("watchhentai") private val okHttpClient: OkHttpClient,
    private val prefs: AppPrefs
) : HentaiSource {

    override val sourceName: String = "watchhentai.net"

    companion object {
        const val BASE_URL = "https://watchhentai.net"

        val WATCHHENTAI_MIRRORS = listOf(
            "https://watchhentai.net",
            "https://watchhentai.org"
        )
        val HENTAIHAVEN_MIRRORS = listOf(
            "https://hentaihaven.xxx"
        )
        val HSTREAM_MIRRORS = listOf(
            "https://hstream.moe"
        )
        val DEFAULT_MIRRORS = WATCHHENTAI_MIRRORS + HENTAIHAVEN_MIRRORS + HSTREAM_MIRRORS

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    private fun activeMirrors(): List<String> {
        val custom = runCatching { CustomMirrorUrls.parseList(prefs.vaultCustomMirrors) }.getOrDefault(emptyList())
        return (custom + DEFAULT_MIRRORS).distinct()
    }

    /**
     * Tests whether the Aniyomi multi-engine scraper works on [rawMirrorUrl] by probing
     * WP-JSON REST API, DooPlay Search API, and Adaptive HTML extraction.
     */
    suspend fun testMirror(rawMirrorUrl: String): MirrorTestResult = withContext(Dispatchers.IO) {
        val normalized = CustomMirrorUrls.normalizeAndValidate(rawMirrorUrl).getOrElse { err ->
            return@withContext MirrorTestResult(false, err.message ?: "Invalid URL", 0L)
        }
        val t0 = System.currentTimeMillis()
        var lastError: String? = null

        try {
            // 1. Probe Adaptive Multi-Theme HTML search & homepage
            for (probeUrl in listOf("$normalized/?s=a", "$normalized/")) {
                val htmlResults = runCatching {
                    val html = fetchRaw(probeUrl, normalized, acceptJson = false)
                    AniyomiAdaptiveParser.parseAdaptiveHtml(html, normalized)
                }.onFailure { lastError = it.message }.getOrDefault(emptyList())
                if (htmlResults.isNotEmpty()) {
                    val dt = System.currentTimeMillis() - t0
                    return@withContext MirrorTestResult(true, "Working · ${htmlResults.size} covers via Adaptive HTML (${dt} ms)", dt)
                }
            }

            // 2. Probe WordPress Core REST API
            val wpResults = runCatching {
                val wpUrl = "$normalized/wp-json/wp/v2/posts?per_page=5&_embed=1"
                val body = fetchRaw(wpUrl, normalized, acceptJson = true)
                AniyomiAdaptiveParser.parseWpJsonPosts(body, normalized)
            }.onFailure { if (lastError == null) lastError = it.message }.getOrDefault(emptyList())
            if (wpResults.isNotEmpty()) {
                val dt = System.currentTimeMillis() - t0
                return@withContext MirrorTestResult(true, "Working · ${wpResults.size} covers via WP-JSON (${dt} ms)", dt)
            }

            // 3. Probe DooPlay AJAX Search API
            val dpResults = runCatching {
                val dpUrl = "$normalized/wp-json/dooplay/search/?keyword=a&nonce=ani"
                val body = fetchRaw(dpUrl, normalized, acceptJson = true)
                AniyomiAdaptiveParser.parseDooPlaySearchJson(body, normalized)
            }.onFailure { if (lastError == null) lastError = it.message }.getOrDefault(emptyList())
            if (dpResults.isNotEmpty()) {
                val dt = System.currentTimeMillis() - t0
                return@withContext MirrorTestResult(true, "Working · ${dpResults.size} covers via DooPlay API (${dt} ms)", dt)
            }

            val dt = System.currentTimeMillis() - t0
            val reason = Sanitizer.clean(lastError, 80).ifBlank { "No scraper covers found at this URL" }
            MirrorTestResult(false, reason, dt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - t0
            MirrorTestResult(false, Sanitizer.clean(e.message, 80).ifBlank { "Connection failed" }, dt)
        }
    }

    fun customMirrors(): List<String> =
        runCatching { CustomMirrorUrls.parseList(prefs.vaultCustomMirrors) }.getOrDefault(emptyList())

    /**
     * Searches a specific group of mirror base URLs (e.g. WatchHentai only, HentaiHaven only, HStream only,
     * or user custom mirrors) and fetches the rich detail page (including 16:9 scene preview images).
     */
    suspend fun fetchBestDetailFromMirrors(
        mirrors: List<String>,
        candidateQueries: List<String>,
        targetTitles: List<String>
    ): ScrapedEntry? = withContext(Dispatchers.IO) {
        var bestFallbackDetail: ScrapedEntry? = null
        for (mirror in mirrors.distinct()) {
            for (q in candidateQueries.take(4)) {
                val hits = runCatching { searchSingleMirror(mirror, q, 1) }.getOrDefault(emptyList())
                if (hits.isEmpty()) continue
                val matching = hits.filter { hit ->
                    targetTitles.any { t -> UncensoredCoverMatcher.similarity(t, hit.title) >= 0.32 }
                }
                val pool = if (matching.isNotEmpty()) matching else continue
                val seriesPool = pool.filter {
                    it.detailUrl.contains("/series/", ignoreCase = true) ||
                        it.detailUrl.contains("/hentai/", ignoreCase = true)
                }
                val candidatesToTry = (seriesPool + pool).distinctBy { it.detailUrl.trimEnd('/') }.take(2)
                for (chosen in candidatesToTry) {
                    val detail = runCatching { fetchDetail(chosen.detailUrl) }.getOrNull() ?: chosen
                    if (bestFallbackDetail == null) bestFallbackDetail = detail
                    if (detail.episodePreviews.isNotEmpty()) return@withContext detail
                }
            }
        }
        bestFallbackDetail
    }

    private fun searchSingleMirror(mirror: String, q: String, page: Int): List<ScrapedEntry> {
        val encoded = URLEncoder.encode(q.trim(), "UTF-8")
        val isHstream = mirror.contains("hstream", ignoreCase = true)

        // 1. Adaptive HTML search (`/?s=` and `/search?q=` for HStream/Laravel)
        val searchUrls = if (isHstream) {
            listOf("$mirror/search?q=$encoded", "$mirror/?s=$encoded")
        } else {
            listOf(if (page <= 1) "$mirror/?s=$encoded" else "$mirror/page/$page/?s=$encoded")
        }
        val htmlResults = mutableListOf<ScrapedEntry>()
        for (u in searchUrls) {
            val parsed = runCatching {
                val html = fetchRaw(u, mirror, acceptJson = false)
                AniyomiAdaptiveParser.parseAdaptiveHtml(html, mirror)
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) {
                htmlResults += parsed
                break
            }
        }

        // 2. DooPlay REST Search API
        val dooPlayResults = if (page == 1 && !isHstream) {
            runCatching {
                val dpUrl = "$mirror/wp-json/dooplay/search/?keyword=$encoded&nonce=ani"
                val body = fetchRaw(dpUrl, mirror, acceptJson = true)
                AniyomiAdaptiveParser.parseDooPlaySearchJson(body, mirror)
            }.getOrDefault(emptyList())
        } else emptyList()

        // 3. WordPress Core REST API (`/wp-json/wp/v2/posts`)
        val wpJsonResults = if (!isHstream) {
            runCatching {
                val wpUrl = "$mirror/wp-json/wp/v2/posts?search=$encoded&per_page=20&page=$page&_embed=1"
                val body = fetchRaw(wpUrl, mirror, acceptJson = true)
                AniyomiAdaptiveParser.parseWpJsonPosts(body, mirror)
            }.getOrDefault(emptyList())
        } else emptyList()

        return (htmlResults + dooPlayResults + wpJsonResults)
            .distinctBy { it.detailUrl.trimEnd('/') }
            .sortedByDescending {
                it.detailUrl.contains("/series/", ignoreCase = true) ||
                    it.detailUrl.contains("/hentai/", ignoreCase = true)
            }
    }

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        withContext(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            val words = trimmedQuery.split(Regex("""\s+""")).filter { it.length >= 2 }
            val shortQuery = if (words.size > 4) words.take(4).joinToString(" ") else null
            val queriesToTry = listOfNotNull(trimmedQuery, shortQuery).distinct()
            var lastError: Exception? = null

            for (mirror in activeMirrors()) {
                for (q in queriesToTry) {
                    val encoded = URLEncoder.encode(q, "UTF-8")

                    // 1. Try Adaptive Multi-Theme HTML search first so we get `/series/<slug>/` entries (which host the preview gallery!)
                    val htmlResults = try {
                        val htmlUrl = if (page <= 1) "$mirror/?s=$encoded" else "$mirror/page/$page/?s=$encoded"
                        val html = fetchRaw(htmlUrl, mirror, acceptJson = false)
                        AniyomiAdaptiveParser.parseAdaptiveHtml(html, mirror)
                    } catch (e: Exception) {
                        lastError = e
                        emptyList()
                    }

                    // 2. Try DooPlay REST Search API
                    val dooPlayResults = if (page == 1) {
                        runCatching {
                            val dpUrl = "$mirror/wp-json/dooplay/search/?keyword=$encoded&nonce=ani"
                            val body = fetchRaw(dpUrl, mirror, acceptJson = true)
                            AniyomiAdaptiveParser.parseDooPlaySearchJson(body, mirror)
                        }.getOrDefault(emptyList())
                    } else emptyList()

                    // 3. Try WordPress Core REST API (`/wp-json/wp/v2/series`, `/wp-json/wp/v2/tvshows`, `/wp-json/wp/v2/posts`)
                    val wpJsonResults = runCatching {
                        val wpUrl = "$mirror/wp-json/wp/v2/posts?search=$encoded&per_page=20&page=$page&_embed=1"
                        val body = fetchRaw(wpUrl, mirror, acceptJson = true)
                        AniyomiAdaptiveParser.parseWpJsonPosts(body, mirror)
                    }.getOrDefault(emptyList())

                    // Combine and prioritize `/series/` URLs ahead of individual `/videos/` episode URLs
                    val combined = (htmlResults + dooPlayResults + wpJsonResults)
                        .distinctBy { it.detailUrl.trimEnd('/') }
                        .sortedByDescending { it.detailUrl.contains("/series/", ignoreCase = true) }

                    if (combined.isNotEmpty()) return@withContext combined
                }
            }

            if (lastError != null) throw lastError
            emptyList()
        }

    override suspend fun fetchDetail(detailUrl: String): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                val origin = detailUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: BASE_URL
                val html = fetchRaw(detailUrl, origin, acceptJson = false)
                val initial = AniyomiAdaptiveParser.parseAdaptiveDetailHtml(html, detailUrl)

                // If detailUrl was a `/videos/` episode page and had no gallery previews, follow the parent `/series/` link!
                if (initial.episodePreviews.isEmpty() && !detailUrl.contains("/series/", ignoreCase = true)) {
                    val doc = Jsoup.parse(html, detailUrl)
                    val seriesHref = doc.selectFirst("a[href*='/series/']")?.attr("href")?.trim().orEmpty()
                        .let {
                            when {
                                it.startsWith("http") -> it
                                it.startsWith("/") -> "${origin.trimEnd('/')}$it"
                                else -> ""
                            }
                        }
                    if (seriesHref.startsWith("https://")) {
                        val seriesHtml = runCatching { fetchRaw(seriesHref, origin, acceptJson = false) }.getOrNull()
                        if (!seriesHtml.isNullOrBlank()) {
                            val seriesEntry = AniyomiAdaptiveParser.parseAdaptiveDetailHtml(seriesHtml, seriesHref)
                            if (seriesEntry.episodePreviews.isNotEmpty() || seriesEntry.synopsis.length > initial.synopsis.length) {
                                return@runCatching initial.copy(
                                    coverUrl = seriesEntry.coverUrl.ifBlank { initial.coverUrl },
                                    synopsis = seriesEntry.synopsis.ifBlank { initial.synopsis },
                                    studio = seriesEntry.studio ?: initial.studio,
                                    tags = (initial.tags + seriesEntry.tags).distinct(),
                                    episodeCount = maxOf(initial.episodeCount, seriesEntry.episodeCount),
                                    episodePreviews = seriesEntry.episodePreviews
                                )
                            }
                        }
                    }
                }
                initial
            }.getOrNull()
        }

    private fun fetchRaw(url: String, refererBase: String, acceptJson: Boolean): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                if (acceptJson) "application/json,text/plain,*/*"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$refererBase/")
            .get()
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful)
                throw IOException("Unexpected HTTP ${response.code} from $url")
            response.body?.string()
                ?: throw IOException("Empty response body from $url")
        }
    }
}

/**
 * Pure, JVM-testable multi-engine parser implementing Aniyomi extension extraction strategies:
 *  - WordPress REST API (`/wp-json/wp/v2/posts?_embed=1`)
 *  - DooPlay REST Search (`/wp-json/dooplay/search/`)
 *  - Multi-theme CSS + Tailwind link-card heuristic + 3-layer series gallery preview extractor
 *  - WordPress thumbnail URL resolution (`-150x150`, `-185x278`, `-300x420` -> full-res original)
 */
object AniyomiAdaptiveParser {

    // WordPress dimension suffix pattern: e.g. "poster-185x278.jpg" -> "poster.jpg"
    private val WP_THUMB_REGEX = Regex("""-\d{2,4}x\d{2,4}(?=\.(?:jpe?g|png|webp|avif)(?:\?|$))""", RegexOption.IGNORE_CASE)

    // Raw HTML image URL pattern for catching Alpine.js / Swiper / script-hydrated gallery image arrays
    private val RAW_UPLOAD_IMG_REGEX = Regex(
        """https?:\\?/\\?/[^\s"'<>\\]+?/wp-content/uploads/[^\s"'<>\\]+?\.(?:jpe?g|png|webp|avif)""",
        RegexOption.IGNORE_CASE
    )

    private val MULTI_THEME_CARD_SELECTORS = listOf(
        // DooPlay / WatchHentai / ToroPlay
        "div.videos-list article",
        "div.result-item article",
        "div.search-page div.result-item",
        "article.item",
        "article.TPost",
        // Madara (HentaiHaven / WP-Manga)
        "div.c-tabs-item__content",
        "div.page-item-detail",
        "div.manga__item",
        // AnimeStream / Themesia (HStream / MuchoHentai)
        "div.listupd article.bs",
        "div.bsx",
        // Flix / Zoro
        "div.film_list-wrap div.flw-item",
        "div.items article"
    ).joinToString(", ")

    private val TITLE_SELECTORS =
        "h3.film-name, h2.name, div.title, div.post-title h3, div.post-title h4, div.data h3, h2.entry-title, div.tt, h3, h2, h4"

    private val LINK_SELECTORS =
        "a.film-poster, a.dynamic-name, div.image a, div.post-title a, div.thumbnail a, div.bsx a, a[href*='/series/'], a[href*='/videos/'], a[href*='/hentai/'], a[href]"

    private val IMG_ATTR_PRIORITY = listOf(
        "data-src",
        "data-lazy-src",
        "data-lazy",
        "data-original",
        "data-cfsrc",
        "data-splide-lazy",
        "data-flickity-lazyload",
        "data-large_image",
        "data-full-url",
        "data-bg",
        "data-srcset",
        "srcset",
        "src"
    )

    private val GALLERY_SELECTORS = listOf(
        "#dt_galery img",
        "#single_galeria img",
        "div.galeria img",
        "div.dt-galery img",
        "div.g-item img",
        "div.screenshots img",
        "div.swiper-wrapper img",
        "div.swiper-slide img",
        "div.owl-carousel img",
        "div.owl-item img",
        "div.splide__list img",
        "div.carousel-inner img",
        "[id*='galer'] img",
        "[class*='galer'] img",
        "[id*='gallery'] img",
        "[class*='gallery'] img",
        "[class*='screenshot'] img",
        "[class*='preview'] img",
        "figure.wp-block-gallery img",
        "div.wp-content img",
        "div.entry-content img"
    ).joinToString(", ")

    fun sourceLabelFromUrl(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { "watchhentai.net" }

    /**
     * Returns `true` if [urlA] and [urlB] refer to the same underlying image file (ignoring
     * WordPress `-185x278` dimension suffixes, scheme differences, and query strings).
     */
    fun sameImageAsset(urlA: String?, urlB: String?): Boolean {
        val a = canonicalImageKey(urlA)
        val b = canonicalImageKey(urlB)
        return a.isNotEmpty() && a == b
    }

    private fun canonicalImageKey(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return ""
        val noQuery = clean.substringBefore("?").substringBefore("#")
        val upgraded = WP_THUMB_REGEX.replace(noQuery, "")
        return upgraded.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
    }

    /**
     * Strips WordPress auto-generated thumbnail size suffixes (`-150x150`, `-185x278`, `-300x420`)
     * and upgrades protocol-relative URLs to `https://` so H-Vault always gets crisp, full-resolution
     * uncensored cover art and 16:9 gallery screenshots.
     */
    fun upgradeWordPressCoverUrl(rawUrl: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): String {
        val trimmed = rawUrl.trim().replace("\\/", "/")
        if (trimmed.isEmpty() || trimmed.startsWith("data:")) return ""
        val firstInSrcset = if (trimmed.contains(" ") && (trimmed.contains(",") || trimmed.endsWith("w") || trimmed.endsWith("x"))) {
            trimmed.substringBefore(",").trim().substringBefore(" ").trim()
        } else trimmed

        val absolute = when {
            firstInSrcset.startsWith("https://") || firstInSrcset.startsWith("http://") -> firstInSrcset
            firstInSrcset.startsWith("//") -> "https:$firstInSrcset"
            firstInSrcset.startsWith("/") -> "${baseUrl.trimEnd('/')}$firstInSrcset"
            else -> "${baseUrl.trimEnd('/')}/$firstInSrcset"
        }
        return WP_THUMB_REGEX.replace(absolute, "")
    }

    /**
     * Parses WordPress Core REST API (`/wp-json/wp/v2/posts?search=...&_embed=1`) responses.
     * Note: Never puts [coverUrl] into `episodePreviews` — only genuine content screenshots!
     */
    fun parseWpJsonPosts(json: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): List<ScrapedEntry> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ScrapedEntry>(arr.length())
        val srcLabel = sourceLabelFromUrl(baseUrl)

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val link = obj.optString("link").trim()
            if (link.isEmpty()) continue
            val slug = obj.optString("slug").trim().ifBlank {
                link.trimEnd('/').substringAfterLast("/")
            }
            if (slug.isEmpty()) continue

            val rawTitle = obj.optJSONObject("title")?.optString("rendered")
                ?: obj.optString("title")
            val cleanTitle = Jsoup.parse(rawTitle).text().trim()
                .replace(Regex("""\s*[-–|]\s*Watch\s+Hentai.*$""", RegexOption.IGNORE_CASE), "")
            if (cleanTitle.isEmpty()) continue

            val coverUrl = extractWpFeaturedMedia(obj, baseUrl)
            if (coverUrl.isBlank()) continue

            val excerptHtml = obj.optJSONObject("excerpt")?.optString("rendered").orEmpty()
            val contentHtml = obj.optJSONObject("content")?.optString("rendered").orEmpty()
            val synopsis = Jsoup.parse(excerptHtml.ifBlank { contentHtml }).text().trim()
            val dateStr = obj.optString("date").trim().take(10)
            val releaseYear = dateStr.take(4).toIntOrNull()

            val contentImgs = if (contentHtml.isNotBlank()) {
                Jsoup.parse(contentHtml, baseUrl).select("img").mapNotNull { img ->
                    extractImageFromElement(img, baseUrl).takeIf { it.isNotBlank() && !sameImageAsset(it, coverUrl) }
                }.distinct()
            } else emptyList()

            val previews = contentImgs.take(15).mapIndexed { idx, imgUrl ->
                VaultEpisodePreview(
                    episodeNumber = idx + 1,
                    title = "Preview ${idx + 1}",
                    previewImageUrl = imgUrl,
                    durationMinutes = 24,
                    releaseDate = dateStr,
                    detailUrl = link,
                    sourceName = srcLabel
                )
            }

            val isCensored = cleanTitle.contains("censored", ignoreCase = true) &&
                !cleanTitle.contains("uncensored", ignoreCase = true)

            out += ScrapedEntry(
                id = "watchhentai:$slug",
                title = cleanTitle,
                coverUrl = coverUrl,
                tags = emptyList(),
                studio = null,
                isCensored = isCensored,
                episodeCount = 1,
                durationMinutes = 24,
                detailUrl = link,
                sourceName = srcLabel,
                synopsis = synopsis,
                previewUrl = contentImgs.firstOrNull().orEmpty(),
                releaseYear = releaseYear,
                episodePreviews = previews
            )
        }
        return out
    }

    private fun extractWpFeaturedMedia(post: JSONObject, baseUrl: String): String {
        for (field in listOf("jetpack_featured_media_url", "featured_image_src", "featured_image_url", "poster")) {
            val direct = post.optString(field).trim()
            if (direct.startsWith("http")) return upgradeWordPressCoverUrl(direct, baseUrl)
        }
        val embedded = post.optJSONObject("_embedded")
        val mediaArr = embedded?.optJSONArray("wp:featuredmedia")
        if (mediaArr != null && mediaArr.length() > 0) {
            val media = mediaArr.optJSONObject(0)
            if (media != null) {
                val fullUrl = media.optJSONObject("media_details")
                    ?.optJSONObject("sizes")
                    ?.optJSONObject("full")
                    ?.optString("source_url")
                    ?.takeIf { it.isNotBlank() }
                    ?: media.optString("source_url")
                if (fullUrl.isNotBlank()) return upgradeWordPressCoverUrl(fullUrl, baseUrl)
            }
        }
        val renderedHtml = post.optJSONObject("content")?.optString("rendered").orEmpty()
        if (renderedHtml.isNotBlank()) {
            val doc = Jsoup.parse(renderedHtml, baseUrl)
            val img = doc.selectFirst("img")
            if (img != null) {
                val extracted = extractImageFromElement(img, baseUrl)
                if (extracted.isNotBlank()) return extracted
            }
        }
        return ""
    }

    /**
     * Parses DooPlay AJAX search JSON (`/wp-json/dooplay/search/?keyword=...`).
     */
    fun parseDooPlaySearchJson(json: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): List<ScrapedEntry> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ScrapedEntry>()
        val srcLabel = sourceLabelFromUrl(baseUrl)
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = root.optJSONObject(key) ?: continue
            val rawTitle = item.optString("title").trim()
            if (rawTitle.isBlank()) continue
            val cleanTitle = Jsoup.parse(rawTitle).text().trim()
            val url = item.optString("url").trim()
            if (url.isEmpty()) continue
            val rawImg = item.optString("img").trim()
            val cover = upgradeWordPressCoverUrl(rawImg, baseUrl)
            if (cover.isBlank()) continue
            val slug = url.trimEnd('/').substringAfterLast("/").ifBlank { key }
            val isCensored = cleanTitle.contains("censored", ignoreCase = true) &&
                !cleanTitle.contains("uncensored", ignoreCase = true)
            out += ScrapedEntry(
                id = "watchhentai:$slug",
                title = cleanTitle,
                coverUrl = cover,
                tags = emptyList(),
                studio = null,
                isCensored = isCensored,
                episodeCount = 1,
                durationMinutes = 0,
                detailUrl = url,
                sourceName = srcLabel
            )
        }
        return out.sortedByDescending { it.detailUrl.contains("/series/", ignoreCase = true) }
    }

    /**
     * Multi-theme adaptive HTML parser supporting DooPlay, Madara, AnimeStream, Flix, JSON-LD,
     * and Tailwind / generic link-card structures.
     */
    fun parseAdaptiveHtml(html: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): List<ScrapedEntry> {
        val doc = Jsoup.parse(html, baseUrl)

        // Pass 1: Known Aniyomi multi-theme selectors
        val cards = doc.select(MULTI_THEME_CARD_SELECTORS)
        val pass1 = cards.mapNotNull { parseCardElement(it, baseUrl) }.distinctBy { it.detailUrl.trimEnd('/') }
        if (pass1.isNotEmpty()) {
            return pass1.sortedByDescending { it.detailUrl.contains("/series/", ignoreCase = true) }
        }

        // Pass 2: JSON-LD structured data
        val jsonLd = parseJsonLdItems(doc, baseUrl)
        if (jsonLd.isNotEmpty()) return jsonLd

        // Pass 3: Semantic containers (`article`, `div.post`, `div.item`, etc.)
        val semanticCandidates = doc.select("article, div.post, div.item, li.post-item, div.entry")
        val pass3 = semanticCandidates.mapNotNull { parseCardElement(it, baseUrl) }.distinctBy { it.detailUrl.trimEnd('/') }
        if (pass3.isNotEmpty()) {
            return pass3.sortedByDescending { it.detailUrl.contains("/series/", ignoreCase = true) }
        }

        // Pass 4: Tailwind / Modern utility-class link cards (`a[href*='/series/'], a[href*='/videos/'], a[href*='/hentai/']`)
        val contentLinks = doc.select("a[href*='/series/'], a[href*='/videos/'], a[href*='/hentai/']")
        val pass4 = contentLinks.mapNotNull { a ->
            val container = when {
                a.selectFirst("img") != null -> a.parent()?.takeIf { it.select("a").size <= 4 } ?: a
                a.parent()?.selectFirst("img") != null -> a.parent()!!
                a.parent()?.parent()?.selectFirst("img") != null -> a.parent()!!.parent()!!
                else -> a
            }
            parseCardElement(container, baseUrl, explicitAnchor = a)
        }.distinctBy { it.detailUrl.trimEnd('/') }
        return pass4.sortedByDescending { it.detailUrl.contains("/series/", ignoreCase = true) }
    }

    private fun parseCardElement(card: Element, baseUrl: String, explicitAnchor: Element? = null): ScrapedEntry? {
        return runCatching {
            val anchor = explicitAnchor ?: card.selectFirst(LINK_SELECTORS) ?: return@runCatching null
            val rawHref = anchor.attr("href").trim().ifBlank { return@runCatching null }
            val href = when {
                rawHref.startsWith("http") -> rawHref
                rawHref.startsWith("/") -> "${baseUrl.trimEnd('/')}$rawHref"
                else -> "${baseUrl.trimEnd('/')}/$rawHref"
            }
            if (href.contains("/tag/") || href.contains("/genre/") || href.contains("/category/") || href.contains("/page/")) {
                return@runCatching null
            }

            val rawSlug = href.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() } ?: return@runCatching null
            val id = "watchhentai:$rawSlug"

            val imgEl = card.selectFirst("img") ?: anchor.selectFirst("img")
            val coverUrl = if (imgEl != null) {
                extractImageFromElement(imgEl, baseUrl)
            } else {
                val styleEl = card.selectFirst("[style*='url(']")
                val bgUrl = styleEl?.attr("style")
                    ?.substringAfter("url(", "")
                    ?.substringBefore(")", "")
                    ?.trim('"', '\'', ' ')
                    .orEmpty()
                upgradeWordPressCoverUrl(bgUrl, baseUrl)
            }
            if (coverUrl.isBlank()) return@runCatching null

            val title = card.selectFirst(TITLE_SELECTORS)?.text()?.trim()?.ifBlank { null }
                ?: anchor.attr("title").trim().ifBlank { null }
                ?: imgEl?.attr("alt")?.trim()?.ifBlank { null }
                ?: anchor.text().trim().takeIf { it.length >= 2 }
                ?: return@runCatching null

            val epCount = card.selectFirst("span.fdi-item:contains(Eps), div.tick-eps, span.epx, span.episode")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
                ?.coerceAtLeast(1) ?: 1

            val isCensored = title.contains("censored", ignoreCase = true) &&
                !title.contains("uncensored", ignoreCase = true)

            ScrapedEntry(
                id = id,
                title = title,
                coverUrl = coverUrl,
                tags = emptyList(),
                studio = null,
                isCensored = isCensored,
                episodeCount = epCount,
                durationMinutes = 0,
                detailUrl = href,
                sourceName = sourceLabelFromUrl(baseUrl)
            )
        }.getOrNull()
    }

    private fun parseJsonLdItems(doc: Document, baseUrl: String): List<ScrapedEntry> {
        val scripts = doc.select("script[type=application/ld+json]")
        val results = mutableListOf<ScrapedEntry>()
        val srcLabel = sourceLabelFromUrl(baseUrl)
        for (script in scripts) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            runCatching {
                val root = if (raw.startsWith("{")) JSONObject(raw) else null
                val itemList = root?.optJSONArray("itemListElement")
                if (itemList != null) {
                    for (i in 0 until itemList.length()) {
                        val el = itemList.optJSONObject(i) ?: continue
                        val item = el.optJSONObject("item") ?: el
                        val url = item.optString("url").trim()
                        if (url.isEmpty()) continue
                        val name = item.optString("name").trim()
                        if (name.isEmpty()) continue
                        val image = item.optString("image").trim()
                        if (image.isEmpty()) continue
                        val slug = url.trimEnd('/').substringAfterLast("/")
                        if (slug.isEmpty()) continue
                        results += ScrapedEntry(
                            id = "watchhentai:$slug",
                            title = name,
                            coverUrl = upgradeWordPressCoverUrl(image, baseUrl),
                            tags = emptyList(),
                            studio = null,
                            isCensored = false,
                            episodeCount = 1,
                            durationMinutes = 0,
                            detailUrl = url,
                            sourceName = srcLabel
                        )
                    }
                }
            }
        }
        return results
    }

    fun parseAdaptiveDetailHtml(html: String, detailUrl: String): ScrapedEntry {
        val baseUrl = detailUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: WatchHentaiScraperRepository.BASE_URL
        val doc = Jsoup.parse(html, detailUrl)
        val rawSlug = detailUrl.trimEnd('/').substringAfterLast("/")
        val id = "watchhentai:$rawSlug"

        val title = doc.selectFirst("h2.film-name, h1.entry-title, h1.title, div.post-title h1, div.data h1, h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.replace(Regex("""\s*[-–|]\s*Watch\s+Hentai.*$""", RegexOption.IGNORE_CASE), "")
            ?: "Unknown"

        val imgEl = doc.selectFirst("img.film-poster-img, img.cover, div.film-poster img, div.poster img, div.summary_image img, div.thumb img")
        val cover = (if (imgEl != null) extractImageFromElement(imgEl, baseUrl) else "")
            .ifBlank {
                val og = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content").orEmpty()
                upgradeWordPressCoverUrl(og, baseUrl)
            }

        val studio = doc.selectFirst("div.item:contains(Studio) a, div.elements a[href*='/studio/'], div.author-content a, span.studio a, a[href*='/studio/']")
            ?.text()?.trim()?.ifBlank { null }

        val tags = doc.select("div.item:contains(Tags) a, div.genres a, div.genres-content a, div.mgen a, a[href*='/tag/'], a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val isCens = doc.selectFirst("div.item:contains(Censored)")
            ?.text()
            ?.contains("yes", ignoreCase = true) == true

        val srcLabel = sourceLabelFromUrl(baseUrl)
        val synopsis = doc.select("div.wp-content p, div.description p, div.summary__content p, div.entry-content p, div.synopsis p, p")
            .map { it.text().trim() }
            .filter {
                it.length > 35 &&
                    !it.contains("Watch Hentai is an English subtitled", ignoreCase = true) &&
                    !it.contains("All characters depicted are fictional", ignoreCase = true) &&
                    !it.contains("This website contains sexually explicit", ignoreCase = true)
            }
            .take(3)
            .joinToString("\n\n")
            .ifBlank {
                doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim().orEmpty()
            }

        val ratingScore = doc.selectFirst("span.dt_rating_vgs, span[itemprop=ratingValue], div.post-rating span.score")
            ?.text()?.trim()?.toFloatOrNull() ?: 0f

        val yearText = doc.selectFirst("span.date, div.extra span.date, div.item:contains(Release), div.post-content_item:contains(Release)")?.text().orEmpty()
        val releaseYear = Regex("""\b(19\d{2}|20\d{2})\b""").find(yearText)?.value?.toIntOrNull()

        // ── 3-Layer Scene Preview Gallery Carousel Extractor ──
        // Never includes the vertical series cover image!
        val galleryUrls = extractGalleryPreviewImages(doc, html, baseUrl, cover)

        val epPreviews = mutableListOf<VaultEpisodePreview>()
        galleryUrls.forEachIndexed { idx, imgUrl ->
            epPreviews += VaultEpisodePreview(
                episodeNumber = idx + 1,
                title = "Preview ${idx + 1}",
                previewImageUrl = imgUrl,
                durationMinutes = 24,
                releaseDate = releaseYear?.toString().orEmpty(),
                detailUrl = detailUrl,
                sourceName = srcLabel
            )
        }

        // Also check episode rows if they have distinct widescreen episode thumbnails (never falling back to `cover`)
        val epRows = doc.select("ul.episodios li, div.eplister ul li, li.wp-manga-chapter, div.episodes-list article")
        if (epPreviews.isEmpty()) {
            epRows.forEachIndexed { idx, row ->
                val epAnchor = row.selectFirst("div.episodiotitle a, a[href]")
                val epHref = epAnchor?.attr("href")?.trim().orEmpty().let {
                    when {
                        it.startsWith("http") -> it
                        it.startsWith("/") -> "${baseUrl.trimEnd('/')}$it"
                        it.isNotEmpty() -> "${baseUrl.trimEnd('/')}/$it"
                        else -> detailUrl
                    }
                }
                val epTitle = epAnchor?.text()?.trim()?.ifBlank { null }
                    ?: row.selectFirst("div.numerando, span.epnum")?.text()?.trim()
                    ?: "Episode ${idx + 1}"
                val numText = row.selectFirst("div.numerando, span.epnum")?.text().orEmpty()
                val epNum = Regex("""\d+""").findAll(numText).lastOrNull()?.value?.toIntOrNull()
                    ?: HanimeAniyomiCodec.extractEpisodeNumber(epTitle, idx + 1)
                val epDate = row.selectFirst("span.date, span.chapter-release-date")?.text()?.trim().orEmpty()
                val epImgEl = row.selectFirst("div.imagen img, img")
                val epImg = if (epImgEl != null) extractImageFromElement(epImgEl, baseUrl) else ""
                // Strictly exclude `cover`!
                if (epImg.isNotBlank() && !sameImageAsset(epImg, cover)) {
                    epPreviews += VaultEpisodePreview(
                        episodeNumber = epNum,
                        title = epTitle,
                        previewImageUrl = epImg,
                        durationMinutes = 24,
                        releaseDate = epDate,
                        detailUrl = epHref,
                        sourceName = srcLabel
                    )
                }
            }
        }

        val epCount = doc.selectFirst("div.item:contains(Episodes), span.tick-eps")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: epRows.size.takeIf { it > 0 }
            ?: 1

        return ScrapedEntry(
            id = id,
            title = title,
            coverUrl = cover,
            tags = tags,
            studio = studio,
            isCensored = isCens,
            episodeCount = epCount.coerceAtLeast(1),
            durationMinutes = 24,
            detailUrl = detailUrl,
            sourceName = srcLabel,
            synopsis = synopsis,
            previewUrl = epPreviews.firstOrNull()?.previewImageUrl.orEmpty(),
            releaseYear = releaseYear,
            ratingScore = ratingScore,
            episodePreviews = epPreviews
        )
    }

    /**
     * 3-layer extractor for the 16:9 scene preview gallery carousel on `watchhentai.net/series/...`
     * and other adult streaming sites:
     *  - **Layer 1**: Explicit gallery/slider containers (`#dt_galery`, `.galeria`, `.g-item`, `.swiper-slide`, `.owl-item`, `div.wp-content`, `<noscript>`, and `style="background-image:..."`).
     *  - **Layer 2**: Tailwind / structural DOM heuristic — any `<img>` on the page that is NOT inside `header/nav/footer/aside` and NOT inside a related-series or episode link card (`a[href*='/series/']`, `a[href*='/videos/']`), and is NOT the series cover poster.
     *  - **Layer 3**: Raw HTML / inline script / Alpine `x-data` / JSON array scanner for `/wp-content/uploads/` images not belonging to related series cards or the cover poster.
     */
    fun extractGalleryPreviewImages(doc: Document, rawHtml: String, baseUrl: String, coverUrl: String): List<String> {
        val results = LinkedHashSet<String>()
        val currentSlug = doc.location().trimEnd('/').substringAfterLast("/").lowercase()

        // Exclude: (a) the vertical cover image itself, (b) header/nav/footer/aside icons,
        // (c) explicit related-series sections, and (d) links to OTHER `/series/<other-slug>` pages.
        val excludedCardImages = HashSet<String>()
        if (coverUrl.isNotBlank()) excludedCardImages += canonicalImageKey(coverUrl)

        doc.select("header img, nav img, footer img, aside img, div.srelacionados img, div.related img, [class*='related'] img").forEach { img ->
            val u = extractImageFromElement(img, baseUrl)
            if (u.isNotBlank()) excludedCardImages += canonicalImageKey(u)
        }
        doc.select("a[href*='/series/'] img").forEach { img ->
            val href = img.closest("a")?.attr("href").orEmpty().trimEnd('/').lowercase()
            val linkedSlug = href.substringAfterLast("/")
            if (linkedSlug.isNotEmpty() && currentSlug.isNotEmpty() && linkedSlug != currentSlug) {
                val u = extractImageFromElement(img, baseUrl)
                if (u.isNotBlank()) excludedCardImages += canonicalImageKey(u)
            }
        }

        fun addCandidate(raw: String) {
            val upgraded = upgradeWordPressCoverUrl(raw, baseUrl)
            if (!isLikelyPreviewImage(upgraded)) return
            val key = canonicalImageKey(upgraded)
            if (key.isEmpty() || key in excludedCardImages) return
            results += upgraded
        }

        fun collectFromElements(root: Element) {
            root.select("img, source").forEach { el ->
                val u = extractImageFromElement(el, baseUrl)
                if (u.isNotBlank()) addCandidate(u)
            }
            root.select("noscript").forEach { ns ->
                Jsoup.parse(ns.html(), baseUrl).select("img, source").forEach { el ->
                    val u = extractImageFromElement(el, baseUrl)
                    if (u.isNotBlank()) addCandidate(u)
                }
            }
            root.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.matches(Regex(""".*\.(?:jpe?g|png|webp|avif)(?:\?.*)?$""", RegexOption.IGNORE_CASE))) {
                    addCandidate(href)
                }
            }
            root.select("[style*='url(']").forEach { el ->
                val bg = el.attr("style").substringAfter("url(", "").substringBefore(")", "").trim('"', '\'', ' ')
                if (bg.isNotBlank()) addCandidate(bg)
            }
        }

        // Layer 1: Known gallery / carousel / slider selectors (#dt_galery, .galeria, .g-item, .swiper, .owl-carousel, etc.)
        doc.select(GALLERY_SELECTORS).forEach { img ->
            val u = extractImageFromElement(img, baseUrl)
            if (u.isNotBlank()) addCandidate(u)
        }
        doc.select("#dt_galery, #single_galeria, div.galeria, div.dt-galery, div.g-item, div.screenshots, div.swiper, div.swiper-wrapper, div.owl-carousel, div.splide, div.carousel, div.slider, [id*='galer'], [class*='galer'], [id*='gallery'], [class*='gallery'], [class*='screenshot'], [class*='swiper'], [class*='owl-'], [class*='slider'], [class*='carousel'], div.wp-content, div.entry-content").forEach { container ->
            collectFromElements(container)
        }

        // Layer 2: Synopsis neighborhood (for Tailwind carousels placed right below the synopsis paragraph, as on watchhentai.net/series/...)
        if (results.isEmpty()) {
            val synopsisParas = doc.select("p").filter {
                val t = it.text().trim()
                t.length > 40 && !t.contains("Watch Hentai is an English subtitled", ignoreCase = true)
            }
            for (p in synopsisParas) {
                var anc: Element? = p.parent()
                repeat(3) {
                    if (anc != null && anc!!.tagName() != "body" && anc!!.tagName() != "html") {
                        collectFromElements(anc!!)
                        anc = anc!!.parent()
                    }
                }
            }
        }

        // Layer 3: All non-excluded images in main/article/body
        if (results.isEmpty()) {
            doc.select("main, article, body").firstOrNull()?.let { body ->
                body.select("img").forEach { img ->
                    if (img.closest("header, nav, footer, aside, div.srelacionados, div.related") != null) return@forEach
                    val u = extractImageFromElement(img, baseUrl)
                    if (u.isNotBlank()) addCandidate(u)
                }
            }
        }

        // Layer 4: Inline script / Alpine x-data / JSON array scanner for `/wp-content/uploads/` screenshots
        if (results.isEmpty()) {
            RAW_UPLOAD_IMG_REGEX.findAll(rawHtml).forEach { m ->
                addCandidate(m.value)
            }
        }

        return results.take(20).toList()
    }

    private fun isLikelyPreviewImage(url: String): Boolean {
        if (url.isBlank() || url.startsWith("data:")) return false
        val lower = url.lowercase()
        if (lower.endsWith(".svg") || lower.endsWith(".gif") || lower.endsWith(".ico")) return false
        val noise = listOf("logo", "avatar", "favicon", "icon", "placeholder", "spinner", "loading", "banner-ad", "sponsor", "gravatar", "emoji", "flags/")
        return noise.none { lower.contains(it) }
    }

    private fun extractImageFromElement(img: Element, baseUrl: String): String {
        for (attr in IMG_ATTR_PRIORITY) {
            val raw = img.attr(attr).trim()
            if (raw.isNotBlank() && !raw.startsWith("data:image/svg") && !raw.contains("placeholder", ignoreCase = true)) {
                val upgraded = upgradeWordPressCoverUrl(raw, baseUrl)
                if (upgraded.isNotBlank()) return upgraded
            }
        }
        return ""
    }
}
