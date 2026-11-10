package com.anivault.data.remote.scraper

import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.model.VaultEpisodePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Uses the nhentai JSON API as a reliable fallback H-Vault search source.
 *
 * No scraping required — nhentai exposes a public search endpoint:
 *   GET https://nhentai.net/api/galleries/search?query=<q>&page=<n>
 *
 * Implements [HentaiSource].
 */
@Singleton
class NhentaiApiRepository @Inject constructor(
    @Named("watchhentai") private val okHttpClient: OkHttpClient // plain client, no CF bypass
) : HentaiSource {

    override val sourceName: String = "nhentai.net"

    companion object {
        private const val BASE_URL   = "https://nhentai.net"
        private const val SEARCH_URL = "$BASE_URL/api/galleries/search?query=%s&page=%d"
        private const val COVER_URL  = "https://t.nhentai.net/galleries/%s/cover.%s"
    }

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        withContext(Dispatchers.IO) {
            // Failures propagate so the caller can tell "source down" from "no results".
            val url  = SEARCH_URL.format(URLEncoder.encode(query, "UTF-8"), page)
            val body = fetch(url) ?: return@withContext emptyList()
            parseSearchResults(body)
        }

    override suspend fun fetchDetail(detailUrl: String): ScrapedEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                val id   = detailUrl.trimEnd('/').substringAfterLast("/").removePrefix("nhentai:")
                val url  = "$BASE_URL/api/gallery/$id"
                val body = fetch(url) ?: return@runCatching null
                parseSingleEntry(JSONObject(body), detailUrl)
            }.getOrNull()
        }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun fetch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$BASE_URL/")
            .get()
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code}")
            } else {
                response.body?.string()
            }
        }
    }

    private fun parseSearchResults(json: String): List<ScrapedEntry> {
        val root   = JSONObject(json)
        val result = root.optJSONArray("result") ?: return emptyList()
        val list   = mutableListOf<ScrapedEntry>()
        for (i in 0 until result.length()) {
            runCatching {
                list += parseSingleEntry(result.getJSONObject(i))
            }
        }
        return list
    }

    private fun parseSingleEntry(obj: JSONObject, knownDetailUrl: String? = null): ScrapedEntry {
        val rawId   = obj.optInt("id", 0).toString()
        val id      = "nhentai:$rawId"
        val mediaId = obj.optString("media_id", "")

        // Title: prefer English, fallback to Japanese
        val titles = obj.optJSONObject("title")
        val title  = titles?.optString("english")?.ifBlank { null }
            ?: titles?.optString("japanese")?.ifBlank { null }
            ?: "Unknown"

        // Cover image — tries "cover" image first
        val images    = obj.optJSONObject("images")
        val coverObj  = images?.optJSONObject("cover")
        val coverExt  = extFromType(coverObj?.optString("t") ?: "j")
        val coverUrl  = if (mediaId.isNotEmpty()) COVER_URL.format(mediaId, coverExt) else ""

        // Tags
        val tagsArr  = obj.optJSONArray("tags")
        val tags     = mutableListOf<String>()
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val tag = tagsArr.getJSONObject(i)
                if (tag.optString("type") == "tag") {
                    tags += tag.optString("name")
                }
            }
        }

        // Artists as "studio"
        val artists = mutableListOf<String>()
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val tag = tagsArr.getJSONObject(i)
                if (tag.optString("type") == "artist") {
                    artists += tag.optString("name")
                }
            }
        }

        val numPages     = obj.optInt("num_pages", 1)
        val detailUrl    = knownDetailUrl ?: "$BASE_URL/g/$rawId/"

        val previews = mutableListOf<VaultEpisodePreview>()
        val pagesArr = images?.optJSONArray("pages")
        if (mediaId.isNotEmpty() && pagesArr != null) {
            val count = minOf(pagesArr.length(), 12)
            for (p in 0 until count) {
                val pObj = pagesArr.optJSONObject(p)
                val pExt = extFromType(pObj?.optString("t") ?: "j")
                val thumbUrl = "https://t.nhentai.net/galleries/$mediaId/${p + 1}t.$pExt"
                previews += VaultEpisodePreview(
                    episodeNumber = p + 1,
                    title = "Page ${p + 1}",
                    previewImageUrl = thumbUrl,
                    durationMinutes = 0,
                    releaseDate = "",
                    detailUrl = "$detailUrl${p + 1}/",
                    sourceName = "nhentai.net"
                )
            }
        }

        return ScrapedEntry(
            id              = id,
            title           = title,
            coverUrl        = coverUrl,
            tags            = tags,
            studio          = artists.joinToString(", ").ifBlank { null },
            isCensored      = false,
            episodeCount    = 1,           // nhentai is per-volume, not episodic
            durationMinutes = numPages,    // store page count as proxy
            detailUrl       = detailUrl,
            sourceName      = "nhentai.net",
            previewUrl      = previews.firstOrNull()?.previewImageUrl ?: coverUrl,
            episodePreviews = previews
        )
    }

    private fun extFromType(t: String): String = when (t) {
        "p" -> "png"
        "g" -> "gif"
        else -> "jpg"
    }
}
