package com.anivault.data.provider

import com.anivault.core.AppPrefs
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** One title as AniList describes it, for features that look titles up by id (airing, related titles, imports). */
data class LookupMedia(
    val anilistId: Int,
    val malId: Int?,
    val title: String,
    val coverUrl: String,
    val genres: List<String>,
    val episodes: Int,
    val durationMinutes: Int,
    val year: Int?,
    val season: String,
    val format: String,
    val adult: Boolean,
    val synopsis: String = "",
    val nextEpisode: Int? = null,
    /** Epoch millis of the next episode's air time. */
    val nextAiringAt: Long? = null,
    val airingStatus: String = ""
)

data class RelatedTitle(val relation: String, val media: LookupMedia)

/** Public, key-less AniList queries used by airing reminders, the related-titles row and the MAL import. */
@Singleton
class AniListLookup @Inject constructor(@Named("api") client: OkHttpClient, prefs: AppPrefs) {
    private val http: HttpTransport = OkHttpTransport(client) { prefs.networkTimeoutSec }
    private val url = "https://graphql.anilist.co"

    private val FIELDS = "id idMal title { romaji english } coverImage { large } genres episodes duration seasonYear season format isAdult " +
        "status description(asHtml: false) nextAiringEpisode { episode airingAt }"

    private suspend fun run(query: String, vars: JsonObject): JsonObject {
        val body = JsonObject().apply { addProperty("query", AniListAdapter.gql(query)); add("variables", vars) }
        val res = http.execute(HttpRequestSpec(url, "POST", mapOf("Accept" to "application/json"), body.toString()))
        HttpErrors.check(res, "AniList")
        val root = HttpErrors.parse(res.body, "AniList").asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "AniList returned an unexpected response")
        root.arr("errors")?.objects()?.firstOrNull()?.let {
            throw ProviderException(FailureKind.CLIENT, "AniList: ${it.str("message") ?: "request failed"}")
        }
        return root.obj("data") ?: throw ProviderException(FailureKind.MALFORMED, "AniList response has no data")
    }

    /** Looks up many titles at once (50 per request). [byMal] switches the ids from AniList ids to MyAnimeList ids. */
    suspend fun byIds(ids: Collection<Int>, byMal: Boolean = false): List<LookupMedia> {
        val out = ArrayList<LookupMedia>()
        for (chunk in ids.distinct().chunked(50)) {
            val arg = if (byMal) "idMal_in" else "id_in"
            val q = "query (§ids: [Int]) { Page(perPage: 50) { media($arg: §ids, type: ANIME) { $FIELDS } } }"
            val vars = JsonObject().apply { add("ids", com.google.gson.JsonArray().also { a -> chunk.forEach { a.add(it) } }) }
            val data = run(q, vars)
            data.obj("Page")?.arr("media")?.objects()?.mapNotNull(::parse)?.let { out += it }
        }
        return out
    }

    /** Sequels, prequels and other related anime for one title, adult titles left out. */
    suspend fun relations(id: Int, byMal: Boolean = false): List<RelatedTitle> {
        val idArg = if (byMal) "idMal: §id" else "id: §id"
        val q = "query (§id: Int) { Media($idArg, type: ANIME) { relations { edges { relationType node { $FIELDS type } } } } }"
        val data = run(q, JsonObject().apply { addProperty("id", id) })
        val edges = data.obj("Media")?.obj("relations")?.arr("edges")?.objects().orEmpty()
        val wanted = setOf("SEQUEL", "PREQUEL", "PARENT", "SIDE_STORY", "SPIN_OFF", "ALTERNATIVE", "SUMMARY", "COMPILATION")
        return edges.mapNotNull { e ->
            val rel = e.str("relationType") ?: return@mapNotNull null
            val node = e.obj("node") ?: return@mapNotNull null
            if (rel !in wanted || node.str("type") != "ANIME") return@mapNotNull null
            parse(node)?.takeIf { !it.adult }?.let { RelatedTitle(rel, it) }
        }.sortedBy { listOf("PREQUEL", "PARENT", "SEQUEL").indexOf(it.relation).let { i -> if (i < 0) 9 else i } }
    }

    private fun parse(o: JsonObject): LookupMedia? {
        val id = o.int("id") ?: return null
        val t = o.obj("title")
        val title = t?.str("english")?.takeIf { it.isNotBlank() } ?: t?.str("romaji") ?: return null
        val next = o.obj("nextAiringEpisode")
        return LookupMedia(
            anilistId = id, malId = o.int("idMal"), title = title,
            coverUrl = o.obj("coverImage")?.str("large").orEmpty(),
            genres = o.arr("genres")?.strings().orEmpty(),
            episodes = o.int("episodes") ?: 0, durationMinutes = o.int("duration") ?: 0,
            year = o.int("seasonYear"), season = o.str("season").orEmpty().lowercase(),
            format = o.str("format").orEmpty(), adult = o.get("isAdult")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            synopsis = o.str("description").orEmpty(),
            nextEpisode = next?.int("episode"), nextAiringAt = next?.int("airingAt")?.let { it * 1000L },
            airingStatus = o.str("status").orEmpty()
        )
    }
}
