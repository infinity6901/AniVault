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
    val airingStatus: String = "",
    val averageScore: Float = 0f,
    val altTitles: List<String> = emptyList()
)

data class RelatedTitle(val relation: String, val media: LookupMedia)

data class CharacterCredit(
    val id: Int,
    val name: String,
    val role: String,
    val imageUrl: String,
    val voiceActor: String? = null
)

data class StreamingLink(val site: String, val url: String)

data class MediaExtras(
    val studios: List<String> = emptyList(),
    val characters: List<CharacterCredit> = emptyList(),
    val links: List<StreamingLink> = emptyList(),
    val relations: List<RelatedTitle> = emptyList()
)

data class AiringScheduleSlot(
    val episode: Int,
    val airingAt: Long,
    val media: LookupMedia
)

/** Public, key-less AniList queries used by airing reminders, calendar, characters/studios/links, related titles and MAL import. */
@Singleton
class AniListLookup @Inject constructor(@Named("api") client: OkHttpClient, prefs: AppPrefs) {
    private val http: HttpTransport = OkHttpTransport(client) { prefs.networkTimeoutSec }
    private val url = "https://graphql.anilist.co"

    private val FIELDS = "id idMal title { romaji english native } synonyms coverImage { extraLarge large } genres episodes duration seasonYear season format isAdult " +
        "status averageScore description(asHtml: false) nextAiringEpisode { episode airingAt }"

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

    /** Sequels, prequels and other related anime for one title, adult titles left out unless [includeAdult] is true. */
    suspend fun relations(id: Int, byMal: Boolean = false, includeAdult: Boolean = false): List<RelatedTitle> =
        extras(id, byMal, includeAdult).relations

    /** Studios, top characters with voice actors, official streaming links, and related franchise titles in one call. */
    suspend fun extras(id: Int, byMal: Boolean = false, includeAdult: Boolean = false): MediaExtras {
        val idArg = if (byMal) "idMal: §id" else "id: §id"
        val q = "query (§id: Int) { Media($idArg, type: ANIME) { " +
            "studios(isMain: true) { nodes { name } } " +
            "characters(sort: [ROLE, RELEVANCE], perPage: 10) { edges { role node { id name { full } image { medium } } voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full } } } } " +
            "externalLinks { site url type } " +
            "relations { edges { relationType node { $FIELDS type } } } " +
            "} }"
        val media = run(q, JsonObject().apply { addProperty("id", id) }).obj("Media") ?: return MediaExtras()
        return parseExtrasFromMediaObj(media, includeAdult)
    }

    /**
     * Looks up both core [LookupMedia] (synopsis, format, year, status, score, genres) AND [MediaExtras]
     * (studios, characters & voice actors, franchise relations including 18+, external links) for an H-Vault entry.
     * Uses [anilistId] or [malId] when known, or searches AniList by [title] (including 18+ adult entries).
     */
    suspend fun lookupForVault(title: String, anilistId: Int? = null, malId: Int? = null): Pair<LookupMedia?, MediaExtras> {
        val extraFields = "$FIELDS " +
            "studios(isMain: true) { nodes { name } } " +
            "characters(sort: [ROLE, RELEVANCE], perPage: 10) { edges { role node { id name { full } image { medium } } voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full } } } } " +
            "externalLinks { site url type } " +
            "relations { edges { relationType node { $FIELDS type } } }"

        val mediaObj: JsonObject? = when {
            anilistId != null -> {
                val q = "query (§id: Int) { Media(id: §id, type: ANIME) { $extraFields } }"
                runCatching { run(q, JsonObject().apply { addProperty("id", anilistId) }).obj("Media") }.getOrNull()
            }
            malId != null -> {
                val q = "query (§id: Int) { Media(idMal: §id, type: ANIME) { $extraFields } }"
                runCatching { run(q, JsonObject().apply { addProperty("id", malId) }).obj("Media") }.getOrNull()
            }
            else -> null
        } ?: run {
            val clean = com.anivault.data.library.Franchise.cleanTitle(title).ifBlank { title }.trim()
            if (clean.isEmpty()) return null to MediaExtras()
            // Try adult search first on AniList, then general ANIME search
            val qAdult = "query (§s: String) { Page(page: 1, perPage: 5) { media(search: §s, type: ANIME, isAdult: true, sort: [SEARCH_MATCH]) { $extraFields } } }"
            val adultHits = runCatching {
                run(qAdult, JsonObject().apply { addProperty("s", clean) }).obj("Page")?.arr("media")?.objects().orEmpty()
            }.getOrDefault(emptyList())
            if (adultHits.isNotEmpty()) {
                adultHits.first()
            } else {
                val qAny = "query (§s: String) { Page(page: 1, perPage: 5) { media(search: §s, type: ANIME, sort: [SEARCH_MATCH]) { $extraFields } } }"
                runCatching {
                    run(qAny, JsonObject().apply { addProperty("s", clean) }).obj("Page")?.arr("media")?.objects()?.firstOrNull()
                }.getOrNull()
            }
        }

        if (mediaObj == null) return null to MediaExtras()
        val parsedMedia = parse(mediaObj)
        val parsedExtras = parseExtrasFromMediaObj(mediaObj, includeAdult = true)
        return parsedMedia to parsedExtras
    }

    private fun parseExtrasFromMediaObj(media: JsonObject, includeAdult: Boolean): MediaExtras {
        val studios = media.obj("studios")?.arr("nodes")?.objects()?.mapNotNull { it.str("name")?.takeIf(String::isNotBlank) }.orEmpty().distinct()
        val characters = media.obj("characters")?.arr("edges")?.objects()?.mapNotNull { edge ->
            val node = edge.obj("node") ?: return@mapNotNull null
            val cid = node.int("id") ?: return@mapNotNull null
            val name = node.obj("name")?.str("full")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val role = prettyEnum(edge.str("role"))
            val img = node.obj("image")?.str("medium").orEmpty()
            val va = edge.arr("voiceActors")?.objects()?.firstOrNull()?.obj("name")?.str("full")
            CharacterCredit(cid, name, role, img, va)
        }.orEmpty()
        val links = media.arr("externalLinks")?.objects()?.mapNotNull { l ->
            val site = l.str("site")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val u = l.str("url")?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return@mapNotNull null
            val type = l.str("type").orEmpty()
            if (type.isBlank() || type == "STREAMING" || type == "INFO") StreamingLink(site, u) else null
        }.orEmpty().distinctBy { it.site }
        val edges = media.obj("relations")?.arr("edges")?.objects().orEmpty()
        val wanted = setOf("SEQUEL", "PREQUEL", "PARENT", "SIDE_STORY", "SPIN_OFF", "ALTERNATIVE", "SUMMARY", "COMPILATION")
        val rels = edges.mapNotNull { e ->
            val rel = e.str("relationType") ?: return@mapNotNull null
            val node = e.obj("node") ?: return@mapNotNull null
            if (rel !in wanted || node.str("type") != "ANIME") return@mapNotNull null
            parse(node)?.takeIf { includeAdult || !it.adult }?.let { RelatedTitle(rel, it) }
        }.sortedBy { listOf("PREQUEL", "PARENT", "SEQUEL").indexOf(it.relation).let { i -> if (i < 0) 9 else i } }
        return MediaExtras(studios, characters, links, rels)
    }

    /** Weekly airing schedule from AniList between [startSec] and [endSec] (epoch seconds), adult titles excluded. */
    suspend fun weeklySchedule(startSec: Long, endSec: Long): List<AiringScheduleSlot> {
        val out = ArrayList<AiringScheduleSlot>()
        for (page in 1..2) {
            val q = "query (§page: Int, §s: Int, §e: Int) { Page(page: §page, perPage: 50) { " +
                "pageInfo { hasNextPage } " +
                "airingSchedules(airingAt_greater: §s, airingAt_lesser: §e, sort: [TIME]) { episode airingAt media { $FIELDS } } " +
                "} }"
            val vars = JsonObject().apply {
                addProperty("page", page)
                addProperty("s", startSec)
                addProperty("e", endSec)
            }
            val pageObj = run(q, vars).obj("Page") ?: break
            val items = pageObj.arr("airingSchedules")?.objects().orEmpty()
            for (item in items) {
                val ep = item.int("episode") ?: continue
                val atSec = item.int("airingAt")?.toLong() ?: continue
                val m = item.obj("media")?.let(::parse) ?: continue
                if (!m.adult) out += AiringScheduleSlot(ep, atSec * 1000L, m)
            }
            val hasNext = pageObj.obj("pageInfo")?.get("hasNextPage")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            if (!hasNext) break
        }
        return out
    }

    private fun parse(o: JsonObject): LookupMedia? {
        val id = o.int("id") ?: return null
        val t = o.obj("title")
        val english = t?.str("english")?.takeIf { it.isNotBlank() }
        val romaji = t?.str("romaji")?.takeIf { it.isNotBlank() }
        val native = t?.str("native")?.takeIf { it.isNotBlank() }
        val title = english ?: romaji ?: native ?: return null
        val alt = (listOfNotNull(romaji, english, native) + o.arr("synonyms")?.strings().orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            .distinct()
        val next = o.obj("nextAiringEpisode")
        val cover = o.obj("coverImage")
        return LookupMedia(
            anilistId = id, malId = o.int("idMal"), title = title,
            coverUrl = cover?.str("extraLarge")?.takeIf { it.isNotBlank() } ?: cover?.str("large").orEmpty(),
            genres = o.arr("genres")?.strings().orEmpty(),
            episodes = o.int("episodes") ?: 0, durationMinutes = o.int("duration") ?: 0,
            year = o.int("seasonYear"), season = o.str("season").orEmpty().lowercase(),
            format = prettyEnum(o.str("format")), adult = o.get("isAdult")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            synopsis = HtmlText.clean(o.str("description")),
            nextEpisode = next?.int("episode"), nextAiringAt = next?.int("airingAt")?.let { it * 1000L },
            airingStatus = prettyEnum(o.str("status")),
            averageScore = o.dbl("averageScore")?.let { (it / 10.0).toFloat() } ?: 0f,
            altTitles = alt
        )
    }
}
