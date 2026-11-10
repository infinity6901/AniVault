package com.anivault.data.remote.scraper

import com.anivault.core.AppPrefs
import com.anivault.data.remote.model.ScrapedEntry
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

/**
 * Self-healing multi-engine scraper modelled on the Aniyomi community extension architecture
 * (`eu.kanade.tachiyomi.animeextension.all.watchhentai` / `hentaihaven` / `dooplay` / `madara` / `wpjson`).
 */
@Singleton
class WatchHentaiScraperRepository @Inject constructor(
    @Named("watchhentai") private val okHttpClient: OkHttpClient,
    private val prefs: AppPrefs
) : HentaiSource {

    override val sourceName: String = "Aniyomi Multi-Mirror (WatchHentai / HentaiHaven)"

    companion object {
        const val BASE_URL = "https://watchhentai.net"

        val DEFAULT_MIRRORS = listOf(
            "https://watchhentai.net",
            "https://watchhentai.org",
            "https://hentaihaven.xxx",
            "https://hstream.moe"
        )

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    private fun activeMirrors(): List<String> {
        val custom = runCatching {
            prefs.vaultCustomMirrors
                .split(",", ";", "\n", " ")
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("https://") && it.toHttpUrlOrNull() != null }
        }.getOrDefault(emptyList())
        return (custom + DEFAULT_MIRRORS).distinct()
    }

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            var lastError: Exception? = null

            for (mirror in activeMirrors()) {
                // 1. Try WordPress Core REST API (_embed=1 gives original full-res uncensored featured media)
                val wpJsonResults = runCatching {
                    val wpUrl = "$mirror/wp-json/wp/v2/posts?search=$encoded&per_page=20&page=$page&_embed=1"
                    val body = fetchRaw(wpUrl, mirror, acceptJson = true)
                    AniyomiAdaptiveParser.parseWpJsonPosts(body, mirror)
                }.getOrDefault(emptyList())
                if (wpJsonResults.isNotEmpty()) return@withContext wpJsonResults

                // 2. Try DooPlay REST Search API (used by WatchHentai / DooPlay adult themes)
                if (page == 1) {
                    val dooPlayResults = runCatching {
                        val dpUrl = "$mirror/wp-json/dooplay/search/?keyword=$encoded&nonce=ani"
                        val body = fetchRaw(dpUrl, mirror, acceptJson = true)
                        AniyomiAdaptiveParser.parseDooPlaySearchJson(body, mirror)
                    }.getOrDefault(emptyList())
                    if (dooPlayResults.isNotEmpty()) return@withContext dooPlayResults
                }

                // 3. Try Adaptive Multi-Theme HTML + JSON-LD + Semantic Card extraction
                try {
                    val htmlUrl = if (page <= 1) "$mirror/?s=$encoded" else "$mirror/page/$page/?s=$encoded"
                    val html = fetchRaw(htmlUrl, mirror, acceptJson = false)
                    val parsed = AniyomiAdaptiveParser.parseAdaptiveHtml(html, mirror)
                    if (parsed.isNotEmpty()) return@withContext parsed
                } catch (e: Exception) {
                    lastError = e
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
                AniyomiAdaptiveParser.parseAdaptiveDetailHtml(html, detailUrl)
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
 *  - Multi-theme CSS + JSON-LD + OpenGraph + structural semantic fallback
 *  - WordPress thumbnail URL resolution (`-150x150`, `-185x278`, `-300x420` -> full-res original)
 */
object AniyomiAdaptiveParser {

    // WordPress dimension suffix pattern: e.g. "poster-185x278.jpg" -> "poster.jpg"
    private val WP_THUMB_REGEX = Regex("""-\d{2,4}x\d{2,4}(?=\.(?:jpe?g|png|webp|avif)(?:\?|$))""", RegexOption.IGNORE_CASE)

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
        "h3.film-name, h2.name, div.title, div.post-title h3, div.post-title h4, div.data h3, h2.entry-title, div.tt, h3, h2"

    private val LINK_SELECTORS =
        "a.film-poster, a.dynamic-name, div.image a, div.post-title a, div.thumbnail a, div.bsx a, a[href]"

    private val IMG_ATTR_PRIORITY = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "data-cfsrc",
        "data-bg",
        "srcset",
        "src"
    )

    /**
     * Strips WordPress auto-generated thumbnail size suffixes (`-150x150`, `-185x278`, `-300x420`)
     * and upgrades protocol-relative URLs to `https://` so H-Vault always gets crisp, full-resolution
     * uncensored cover art.
     */
    fun upgradeWordPressCoverUrl(rawUrl: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): String {
        val trimmed = rawUrl.trim()
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
     */
    fun parseWpJsonPosts(json: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): List<ScrapedEntry> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ScrapedEntry>(arr.length())

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
                durationMinutes = 0,
                detailUrl = link
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
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = root.optJSONObject(key) ?: continue
            val rawTitle = item.optString("title").trim()
            if (rawTitle.isBlank()) continue
            val cleanTitle = Jsoup.parse(rawTitle).text().trim()
            val url = item.optString("url").trim()
            if (cleanTitle.isEmpty()) continue
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
                detailUrl = url
            )
        }
        return out
    }

    /**
     * Multi-theme adaptive HTML parser supporting DooPlay, Madara, AnimeStream, Flix, JSON-LD,
     * and structural semantic card fallback.
     */
    fun parseAdaptiveHtml(html: String, baseUrl: String = WatchHentaiScraperRepository.BASE_URL): List<ScrapedEntry> {
        val doc = Jsoup.parse(html, baseUrl)

        val cards = doc.select(MULTI_THEME_CARD_SELECTORS)
        val pass1 = cards.mapNotNull { parseCardElement(it, baseUrl) }.distinctBy { it.id }
        if (pass1.isNotEmpty()) return pass1

        val jsonLd = parseJsonLdItems(doc, baseUrl)
        if (jsonLd.isNotEmpty()) return jsonLd

        val semanticCandidates = doc.select("article, div.post, div.item, li.post-item, div.entry")
        return semanticCandidates.mapNotNull { parseCardElement(it, baseUrl) }.distinctBy { it.id }
    }

    private fun parseCardElement(card: Element, baseUrl: String): ScrapedEntry? {
        return runCatching {
            val anchor = card.selectFirst(LINK_SELECTORS) ?: return@runCatching null
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

            val imgEl = card.selectFirst("img")
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
                detailUrl = href
            )
        }.getOrNull()
    }

    private fun parseJsonLdItems(doc: Document, baseUrl: String): List<ScrapedEntry> {
        val scripts = doc.select("script[type=application/ld+json]")
        val results = mutableListOf<ScrapedEntry>()
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
            if (cleanTitle.isEmpty()) continue
                        val name = item.optString("name").trim()
            if (cleanTitle.isEmpty()) continue
                        val image = item.optString("image").trim()
            if (cleanTitle.isEmpty()) continue
                        val slug = url.trimEnd('/').substringAfterLast("/")
            if (cleanTitle.isEmpty()) continue
                        results += ScrapedEntry(
                            id = "watchhentai:$slug",
                            title = name,
                            coverUrl = upgradeWordPressCoverUrl(image, baseUrl),
                            tags = emptyList(),
                            studio = null,
                            isCensored = false,
                            episodeCount = 1,
                            durationMinutes = 0,
                            detailUrl = url
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

        val title = doc.selectFirst("h2.film-name, h1.entry-title, h1.title, div.post-title h1, div.data h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        val imgEl = doc.selectFirst("img.film-poster-img, img.cover, div.film-poster img, div.poster img, div.summary_image img, div.thumb img")
        val cover = (if (imgEl != null) extractImageFromElement(imgEl, baseUrl) else "")
            .ifBlank {
                val og = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content").orEmpty()
                upgradeWordPressCoverUrl(og, baseUrl)
            }

        val studio = doc.selectFirst("div.item:contains(Studio) a, div.elements a[href*='/studio/'], div.author-content a, span.studio a")
            ?.text()?.trim()?.ifBlank { null }

        val tags = doc.select("div.item:contains(Tags) a, div.genres a, div.genres-content a, div.mgen a, a[href*='/tag/'], a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val isCens = doc.selectFirst("div.item:contains(Censored)")
            ?.text()
            ?.contains("yes", ignoreCase = true) == true

        val epCount = doc.selectFirst("div.item:contains(Episodes), span.tick-eps")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: doc.select("ul.episodios li, li.wp-manga-chapter, div.eplister ul li").size.takeIf { it > 0 }
            ?: 1

        return ScrapedEntry(
            id = id,
            title = title,
            coverUrl = cover,
            tags = tags,
            studio = studio,
            isCensored = isCens,
            episodeCount = epCount,
            durationMinutes = 24,
            detailUrl = detailUrl
        )
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
