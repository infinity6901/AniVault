package com.anivault.data.remote.scraper

import android.content.Context
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** User-configurable anime metadata providers. Provider types are explicit so an arbitrary URL
 * is never assumed to have a compatible response shape. */
enum class AnimeApiType { ANILIST_GRAPHQL, JIKAN_COMPATIBLE, MAL_V2 }

data class AnimeApiSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val type: AnimeApiType,
    val enabled: Boolean = true,
    val clientId: String = ""
)

data class AnimeSearchResponse(val entries: List<AnimeEntry>, val sourceName: String)

@Singleton
class AnimeApiSources @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("anime_api_sources", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Synchronized fun getSources(): List<AnimeApiSource> {
        val raw = prefs.getString("sources", null)
        if (raw == null) {
            val defaults = listOf(
                AnimeApiSource("anilist", "AniList", "https://graphql.anilist.co", AnimeApiType.ANILIST_GRAPHQL),
                AnimeApiSource("tenrai", "Tenrai API", "https://api.tenrai.org/v1", AnimeApiType.JIKAN_COMPATIBLE, false),
                AnimeApiSource("jikan-edge", "Jikan-Edge", "https://jikan.lucashdo.com/v4", AnimeApiType.JIKAN_COMPATIBLE, true),
                AnimeApiSource("mal", "MyAnimeList (MAL)", "https://api.myanimelist.net/v2", AnimeApiType.MAL_V2, false)
            )
            save(defaults)
            return defaults
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o ->
                AnimeApiSource(o.getString("id"), o.getString("name"), o.getString("baseUrl"),
                    AnimeApiType.valueOf(o.getString("type")), o.optBoolean("enabled", true), o.optString("clientId", ""))
            } }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun save(sources: List<AnimeApiSource>) {
        val arr = JSONArray()
        sources.forEach { s -> arr.put(JSONObject().put("id", s.id).put("name", s.name)
            .put("baseUrl", s.baseUrl).put("type", s.type.name).put("enabled", s.enabled).put("clientId", s.clientId)) }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    suspend fun search(query: String, page: Int = 1): AnimeSearchResponse = withContext(Dispatchers.IO) {
        val sources = getSources().filter { it.enabled }
        if (sources.isEmpty()) throw IllegalStateException("No anime API sources are enabled. Open Settings → API Sources.")
        val failures = mutableListOf<String>()
        for (source in sources) {
            try {
                val entries = when (source.type) {
                    AnimeApiType.ANILIST_GRAPHQL -> searchAniList(source, query)
                    AnimeApiType.JIKAN_COMPATIBLE -> searchJikanCompatible(source, query, page)
                    AnimeApiType.MAL_V2 -> searchMal(source, query, page)
                }
                if (entries.isNotEmpty()) return@withContext AnimeSearchResponse(entries, source.name)
                failures += "${source.name}: no results"
            } catch (e: Exception) {
                failures += "${source.name}: ${e.message?.take(100) ?: "request failed"}"
            }
        }
        throw IllegalStateException("All enabled anime sources failed. ${failures.joinToString(" • ")}")
    }

    private fun execute(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "AniVault/2.0 (Android)")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.post(body.toRequestBody(jsonType)) else builder.get()
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }

    private fun searchAniList(source: AnimeApiSource, q: String): List<AnimeEntry> {
        val query = "query (${'$'}search: String) { Page(page: 1, perPage: 20) { media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) { id title { romaji english } coverImage { extraLarge large } episodes seasonYear genres description averageScore } } }"
        val payload = JSONObject().put("query", query).put("variables", JSONObject().put("search", q)).toString()
        val root = JsonParser.parseString(execute(source.baseUrl, payload)).asJsonObject
        val list = root.getAsJsonObject("data")?.getAsJsonObject("Page")?.getAsJsonArray("media") ?: return emptyList()
        return list.map { el ->
            val o = el.asJsonObject; val title = o.getAsJsonObject("title")
            val cover = o.getAsJsonObject("coverImage")
            AnimeEntry(id = "anilist:${o.get("id").asString}", title = title?.get("english")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: title?.get("romaji")?.asString.orEmpty(),
                imageUrl = cover?.get("extraLarge")?.takeUnless { it.isJsonNull }?.asString ?: cover?.get("large")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                totalEpisodes = o.get("episodes")?.takeUnless { it.isJsonNull }?.asInt ?: 0, watchedEpisodes = 0, status = WatchStatus.PLAN_TO_WATCH, rating = (o.get("averageScore")?.takeUnless { it.isJsonNull }?.asFloat ?: 0f) / 10f,
                year = o.get("seasonYear")?.takeUnless { it.isJsonNull }?.asInt, season = o.get("season")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                genres = o.getAsJsonArray("genres")?.map { it.asString } ?: emptyList(), synopsis = o.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty().replace(Regex("<[^>]*>"), ""), notes = "", durationMinutes = 0)
        }
    }

    private fun searchJikanCompatible(source: AnimeApiSource, q: String, page: Int): List<AnimeEntry> {
        val base = source.baseUrl.trimEnd('/')
        val url = "$base/anime?q=${URLEncoder.encode(q, "UTF-8")}&page=$page&limit=20"
        val root = JsonParser.parseString(execute(url)).asJsonObject
        val list = root.getAsJsonArray("data") ?: return emptyList()
        return list.map { el ->
            val o = el.asJsonObject; val images = o.getAsJsonObject("images")?.getAsJsonObject("jpg")
            val title = o.get("title_english")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: o.get("title")?.asString.orEmpty()
            val id = o.get("mal_id")?.asString ?: o.get("id")?.asString ?: title.hashCode().toString()
            AnimeEntry(id = "${source.id}:$id", title = title, imageUrl = images?.get("large_image_url")?.takeUnless { it.isJsonNull }?.asString ?: images?.get("image_url")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                totalEpisodes = o.get("episodes")?.takeUnless { it.isJsonNull }?.asInt ?: 0, watchedEpisodes = 0, status = WatchStatus.PLAN_TO_WATCH,
                rating = o.get("score")?.takeUnless { it.isJsonNull }?.asFloat ?: 0f, year = o.get("year")?.takeUnless { it.isJsonNull }?.asInt,
                season = o.get("season")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), genres = o.getAsJsonArray("genres")?.mapNotNull { g -> g.asJsonObject.get("name")?.asString } ?: emptyList(),
                synopsis = o.get("synopsis")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), notes = "", durationMinutes = 0)
        }
    }

    private fun searchMal(source: AnimeApiSource, q: String, page: Int): List<AnimeEntry> {
        if (source.clientId.isBlank()) throw IllegalStateException("MAL requires a Client ID. Add one in API Sources.")
        val url = "${source.baseUrl.trimEnd('/')}/anime?q=${URLEncoder.encode(q, "UTF-8")}&limit=20&offset=${(page - 1) * 20}&fields=id,title,main_picture,num_episodes,mean,start_season,synopsis,genres"
        val root = JsonParser.parseString(execute(url, headers = mapOf("X-MAL-CLIENT-ID" to source.clientId))).asJsonObject
        val list = root.getAsJsonArray("data") ?: return emptyList()
        return list.map { el ->
            val o = el.asJsonObject; val node = o.getAsJsonObject("node") ?: o
            val picture = node.getAsJsonObject("main_picture")
            AnimeEntry(id = "mal:${node.get("id").asString}", title = node.get("title")?.asString.orEmpty(), imageUrl = picture?.get("large")?.asString ?: picture?.get("medium")?.asString.orEmpty(),
                totalEpisodes = node.get("num_episodes")?.takeUnless { it.isJsonNull }?.asInt ?: 0, watchedEpisodes = 0, status = WatchStatus.PLAN_TO_WATCH,
                rating = node.get("mean")?.takeUnless { it.isJsonNull }?.asFloat ?: 0f, year = node.getAsJsonObject("start_season")?.get("year")?.asInt, season = node.getAsJsonObject("start_season")?.get("season")?.asString.orEmpty(),
                genres = node.getAsJsonArray("genres")?.mapNotNull { it.asJsonObject.get("name")?.asString } ?: emptyList(), synopsis = node.get("synopsis")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), notes = "", durationMinutes = 0)
        }
    }

    fun newSource(name: String, url: String, type: AnimeApiType, clientId: String = "") = AnimeApiSource(UUID.randomUUID().toString(), name.trim(), url.trim().trimEnd('/'), type, true, clientId.trim())
}
