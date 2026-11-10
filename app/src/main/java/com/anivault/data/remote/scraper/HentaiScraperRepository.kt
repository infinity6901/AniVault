package com.anivault.data.remote.scraper

import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.cloudflare.CloudflareBypassException
import com.anivault.data.remote.cloudflare.CloudflareInterceptor
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.model.VaultEpisodePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Pure, JVM-testable parser for the Aniyomi `Hanime` extension JSON protocol (`search.htv-services.com`
 * and `hanime.tv/api/v8/video`), including 16:9 episode preview thumbnails, synopsis, community rating,
 * and multi-generation HTML/OpenGraph fallback.
 */
object HanimeAniyomiCodec {

    private val EP_NUM_REGEX = Regex("""(?:episode|ep|\b)\s*(\d{1,3})\s*$""", RegexOption.IGNORE_CASE)

    fun extractEpisodeNumber(title: String, fallbackIndex: Int): Int =
        EP_NUM_REGEX.find(title.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallbackIndex

    fun buildSearchPayload(query: String, page: Int): String =
        JSONObject()
            .put("search_text", query.trim())
            .put("tags", JSONArray())
            .put("tags_mode", "AND")
            .put("brands", JSONArray())
            .put("blacklist", JSONArray())
            .put("order_by", "created_at_unix")
            .put("ordering", "desc")
            .put("page", (page - 1).coerceAtLeast(0))
            .toString()

    /**
     * Parses the JSON response from `https://search.htv-services.com/`.
     */
    fun parseSearchJson(rawJson: String): List<ScrapedEntry> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val hitsArray: JSONArray = when (val h = root.opt("hits")) {
            is JSONArray -> h
            is String -> runCatching { JSONArray(h) }.getOrNull() ?: return emptyList()
            else -> return emptyList()
        }
        val out = ArrayList<ScrapedEntry>(hitsArray.length())
        for (i in 0 until hitsArray.length()) {
            val obj = hitsArray.optJSONObject(i) ?: continue
            val slug = obj.optString("slug").trim().ifBlank { obj.optInt("id", 0).takeIf { it > 0 }?.toString().orEmpty() }
            if (slug.isBlank()) continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            val posterUrl = obj.optString("poster_url").trim()
            val cover = obj.optString("cover_url").trim().ifBlank { posterUrl }
            val preview = posterUrl.ifBlank { cover }
            val brand = obj.optString("brand").trim().ifBlank { null }
            val isCensored = obj.optBoolean("is_censored", false)
            val rawDesc = obj.optString("description").trim()
            val synopsis = if (rawDesc.isNotEmpty()) Jsoup.parse(rawDesc).text().trim() else ""
            val likes = obj.optLong("likes", 0L)
            val dislikes = obj.optLong("dislikes", 0L)
            val score = if (likes + dislikes > 0L) (10f * likes.toFloat() / (likes + dislikes).toFloat()) else 0f
            val releasedUnix = obj.optLong("released_at_unix", obj.optLong("created_at_unix", 0L))
            val releaseYear = if (releasedUnix > 0L) {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = releasedUnix * 1000L
                cal.get(Calendar.YEAR)
            } else null

            val tags = ArrayList<String>()
            val tagsArr = obj.optJSONArray("tags")
            if (tagsArr != null) {
                for (t in 0 until tagsArr.length()) {
                    val tag = tagsArr.optString(t).trim()
                    if (tag.isNotEmpty()) tags += tag
                }
            }
            val durationMs = obj.optLong("duration_in_ms", 0L)
            val durationMin = if (durationMs > 0L) (durationMs / 60_000L).toInt().coerceAtLeast(1) else 24
            val epNum = extractEpisodeNumber(name, i + 1)
            val detailUrl = "https://hanime.tv/videos/hentai/$slug"
            val epPreview = if (preview.isNotEmpty()) {
                listOf(
                    VaultEpisodePreview(
                        episodeNumber = epNum,
                        title = name,
                        previewImageUrl = preview,
                        durationMinutes = durationMin,
                        releaseDate = releaseYear?.toString().orEmpty(),
                        detailUrl = detailUrl,
                        sourceName = "hanime.tv"
                    )
                )
            } else emptyList()

            out += ScrapedEntry(
                id = "hanime:$slug",
                title = name,
                coverUrl = cover,
                tags = tags,
                studio = brand,
                isCensored = isCensored,
                episodeCount = 1,
                durationMinutes = durationMin,
                detailUrl = detailUrl,
                sourceName = "hanime.tv",
                synopsis = synopsis,
                previewUrl = preview,
                releaseYear = releaseYear,
                ratingScore = score,
                episodePreviews = epPreview
            )
        }
        return out
    }

    /** Parses the JSON response from `https://hanime.tv/api/v8/video?id=<slug>`. */
    fun parseDetailJson(rawJson: String, fallbackDetailUrl: String): ScrapedEntry? {
        if (rawJson.isBlank()) return null
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val video = root.optJSONObject("hentai_video") ?: return null
        val slug = video.optString("slug").trim().ifBlank {
            fallbackDetailUrl.trimEnd('/').substringAfterLast("/")
        }
        val title = video.optString("name").trim().ifBlank { return null }
        val posterUrl = video.optString("poster_url").trim()
        val cover = video.optString("cover_url").trim().ifBlank { posterUrl }
        val studio = video.optString("brand").trim().ifBlank { null }
        val isCensored = video.optBoolean("is_censored", false)
        val rawDesc = video.optString("description").trim()
        val synopsis = if (rawDesc.isNotEmpty()) Jsoup.parse(rawDesc).text().trim() else ""
        val likes = video.optLong("likes", 0L)
        val dislikes = video.optLong("dislikes", 0L)
        val score = if (likes + dislikes > 0L) (10f * likes.toFloat() / (likes + dislikes).toFloat()) else 0f
        val releasedAt = video.optString("released_at").trim().take(10)
        val releaseYear = releasedAt.take(4).toIntOrNull()

        val tags = ArrayList<String>()
        val tagsArr = video.optJSONArray("hentai_tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val tObj = tagsArr.optJSONObject(i)
                val tText = tObj?.optString("text")?.trim() ?: tagsArr.optString(i).trim()
                if (tText.isNotEmpty()) tags += tText
            }
        }
        val durationMs = video.optLong("duration_in_ms", 0L)
        val durationMin = if (durationMs > 0L) (durationMs / 60_000L).toInt().coerceAtLeast(1) else 24

        val previews = ArrayList<VaultEpisodePreview>()
        val franchiseArr = root.optJSONArray("hentai_franchise_hentai_videos")
        if (franchiseArr != null && franchiseArr.length() > 0) {
            for (i in 0 until franchiseArr.length()) {
                val epObj = franchiseArr.optJSONObject(i) ?: continue
                val epSlug = epObj.optString("slug").trim()
                val epName = epObj.optString("name").trim().ifBlank { "Episode ${i + 1}" }
                val epPoster = epObj.optString("poster_url").trim()
                    .ifBlank { epObj.optString("cover_url").trim() }
                if (epPoster.isEmpty()) continue
                val epDurMs = epObj.optLong("duration_in_ms", 0L)
                val epDurMin = if (epDurMs > 0L) (epDurMs / 60_000L).toInt().coerceAtLeast(1) else durationMin
                val epDate = epObj.optString("released_at").trim().take(10)
                val epNum = extractEpisodeNumber(epName, i + 1)
                previews += VaultEpisodePreview(
                    episodeNumber = epNum,
                    title = epName,
                    previewImageUrl = epPoster,
                    durationMinutes = epDurMin,
                    releaseDate = epDate,
                    detailUrl = if (epSlug.isNotEmpty()) "https://hanime.tv/videos/hentai/$epSlug" else fallbackDetailUrl,
                    sourceName = "hanime.tv"
                )
            }
        } else if (posterUrl.isNotEmpty() || cover.isNotEmpty()) {
            previews += VaultEpisodePreview(
                episodeNumber = extractEpisodeNumber(title, 1),
                title = title,
                previewImageUrl = posterUrl.ifBlank { cover },
                durationMinutes = durationMin,
                releaseDate = releasedAt,
                detailUrl = "https://hanime.tv/videos/hentai/$slug",
                sourceName = "hanime.tv"
            )
        }

        val franchiseEps = (franchiseArr?.length() ?: 1).coerceAtLeast(previews.size.coerceAtLeast(1))

        return ScrapedEntry(
            id = "hanime:$slug",
            title = title,
            coverUrl = cover,
            tags = tags,
            studio = studio,
            isCensored = isCensored,
            episodeCount = franchiseEps,
            durationMinutes = durationMin,
            detailUrl = "https://hanime.tv/videos/hentai/$slug",
            sourceName = "hanime.tv",
            synopsis = synopsis,
            previewUrl = posterUrl.ifBlank { cover },
            releaseYear = releaseYear,
            ratingScore = score,
            episodePreviews = previews
        )
    }
}

/**
 * Primary H-Vault source for uncensored covers, episode previews, and metadata (`hanime.tv`).
 */
@Singleton
class HentaiScraperRepository @Inject constructor(
    @Named("scraper") private val okHttpClient: OkHttpClient,
    @Named("watchhentai") private val apiClient: OkHttpClient,
    private val cfInterceptor: CloudflareInterceptor,
    private val hVaultDao: HVaultDao
) : HentaiSource {

    override val sourceName: String = "hanime.tv"

    companion object {
        const val BASE_URL = "https://hanime.tv"
        const val SEARCH_API_URL = "https://search.htv-services.com/"
        const val VIDEO_API_URL = "https://hanime.tv/api/v8/video?id=%s"
        const val SEARCH_PATH = "/search?query=%s&page=%d"

        private const val SEL_CARD = "div.search-results-row div.search-result, a[href*='/videos/hentai/']"
        private const val SEL_TITLE = "div.search-result-title, div.card-mobile-title, h3, h2"
        private const val SEL_IMAGE = "img.search-result-image, img[data-src], img"
        private const val SEL_LINK = "a.search-result-cover-wrap, a[href*='/videos/hentai/']"
        private const val SEL_EP_BADGE = "span.episode-count"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }

    init {
        cfInterceptor.targetHost = BASE_URL.removePrefix("https://").removePrefix("http://")
    }

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        withContext(Dispatchers.IO) {
            val apiResults = runCatching { searchViaAniyomiApi(query, page) }.getOrNull()
            if (!apiResults.isNullOrEmpty()) return@withContext apiResults

            cfInterceptor.prewarm(BASE_URL)
            val url = BASE_URL + SEARCH_PATH.format(query.urlEncode(), page)
            val doc = fetchDocument(url)
            parseSearchResults(doc)
        }

    override suspend fun fetchDetail(detailUrl: String): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            val slug = detailUrl.trimEnd('/').substringAfterLast("/").removePrefix("hanime:")
            val fromApi = runCatching {
                val req = Request.Builder()
                    .url(VIDEO_API_URL.format(slug.urlEncode()))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                apiClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null
                    else HanimeAniyomiCodec.parseDetailJson(res.body?.string().orEmpty(), detailUrl)
                }
            }.getOrNull()
            if (fromApi != null) return@withContext fromApi

            runCatching {
                cfInterceptor.prewarm(BASE_URL)
                parseDetailPage(fetchDocument(detailUrl), detailUrl)
            }.getOrNull()
        }

    private fun searchViaAniyomiApi(query: String, page: Int): List<ScrapedEntry> {
        val payload = HanimeAniyomiCodec.buildSearchPayload(query, page)
        val req = Request.Builder()
            .url(SEARCH_API_URL)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/")
            .header("User-Agent", USER_AGENT)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        apiClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Hanime API HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            return HanimeAniyomiCodec.parseSearchJson(body)
        }
    }

    fun observeAll(): Flow<List<HVaultEntry>> = hVaultDao.observeAll()

    fun observeByStatus(status: WatchStatus): Flow<List<HVaultEntry>> =
        hVaultDao.observeByStatus(status)

    suspend fun saveToVault(entry: ScrapedEntry) = withContext(Dispatchers.IO) {
        val existing = hVaultDao.getById(entry.id)
        hVaultDao.upsert(
            HVaultEntry(
                id = entry.id,
                title = entry.title,
                imageUrl = entry.coverUrl,
                totalEpisodes = entry.episodeCount,
                watchedEpisodes = existing?.watchedEpisodes ?: 0,
                status = existing?.status ?: WatchStatus.PLAN_TO_WATCH,
                rating = existing?.rating ?: 0f,
                studio = entry.studio,
                isCensored = entry.isCensored,
                tags = entry.tags,
                notes = existing?.notes ?: "",
                durationMinutes = entry.durationMinutes,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        hVaultDao.deleteById(id)
    }

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
        val body = response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} from: $url")
            it.body?.string()
        } ?: throw IOException("Empty response body from: $url")
        return Jsoup.parse(body, url)
    }

    private fun parseSearchResults(doc: Document): List<ScrapedEntry> =
        doc.select(SEL_CARD).mapNotNull { card ->
            runCatching {
                val anchor = if (card.tagName() == "a") card else (card.selectFirst(SEL_LINK) ?: return@runCatching null)
                val href = anchor.attr("href").ensureLeadingSlash()
                val detailUrl = if (href.startsWith("http")) href else "$BASE_URL$href"
                val rawSlug = detailUrl.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() } ?: return@runCatching null
                val id = "hanime:$rawSlug"
                val imgEl = card.selectFirst(SEL_IMAGE)
                val coverUrl = imgEl?.attr("data-src")?.ifBlank { null }
                    ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                    ?: imgEl?.attr("src") ?: ""
                val title = card.selectFirst(SEL_TITLE)?.text()?.trim().orEmpty()
                    .ifBlank { imgEl?.attr("alt")?.trim().orEmpty() }
                    .ifBlank { return@runCatching null }
                val epCount = card.selectFirst(SEL_EP_BADGE)
                    ?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                ScrapedEntry(
                    id = id,
                    title = title,
                    coverUrl = coverUrl,
                    tags = emptyList(),
                    studio = null,
                    isCensored = false,
                    episodeCount = epCount,
                    durationMinutes = 24,
                    detailUrl = detailUrl,
                    sourceName = "hanime.tv",
                    previewUrl = coverUrl
                )
            }.getOrNull()
        }.distinctBy { it.id }

    private fun parseDetailPage(doc: Document, detailUrl: String): ScrapedEntry {
        val rawSlug = detailUrl.trimEnd('/').substringAfterLast("/")
        val id = "hanime:$rawSlug"
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val title = doc.selectFirst("h1.hvp-title, h1.video-title, h1")?.text()?.trim().orEmpty()
            .ifBlank { ogTitle.orEmpty() }
            .ifBlank { "Unknown" }
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val imgEl = doc.selectFirst("img.hvp-cover, img.video-cover")
        val cover = imgEl?.attr("data-src")?.ifBlank { null }
            ?: imgEl?.attr("src")?.ifBlank { null }
            ?: ogImage.orEmpty()
        val synopsis = doc.selectFirst("div.hvp-description, meta[property=og:description], meta[name=description]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.trim().orEmpty()
        val studio = doc.selectFirst("div.hvpi-studio a")?.text()?.trim()?.ifBlank { null }
        val tags = doc.select("div.hvpi-tags span.tag").map { it.text().trim() }.filter { it.isNotBlank() }
        val isCens = doc.selectFirst("div.hvpi-censored") != null
        return ScrapedEntry(
            id = id,
            title = title,
            coverUrl = cover,
            tags = tags,
            studio = studio,
            isCensored = isCens,
            episodeCount = 1,
            durationMinutes = 24,
            detailUrl = detailUrl,
            sourceName = "hanime.tv",
            synopsis = synopsis,
            previewUrl = ogImage ?: cover
        )
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"
}
