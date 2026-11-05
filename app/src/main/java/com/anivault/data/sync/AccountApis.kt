package com.anivault.data.sync

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.AniListAdapter
import com.anivault.data.provider.Dates
import com.anivault.data.provider.FailureKind
import com.anivault.data.provider.HttpErrors
import com.anivault.data.provider.HttpRequestSpec
import com.anivault.data.provider.HttpTransport
import com.anivault.data.provider.KitsuAdapter
import com.anivault.data.provider.MalAdapter
import com.anivault.data.provider.ProviderException
import com.anivault.data.provider.arr
import com.anivault.data.provider.asObjOrNull
import com.anivault.data.provider.dbl
import com.anivault.data.provider.int
import com.anivault.data.provider.obj
import com.anivault.data.provider.objects
import com.anivault.data.provider.str
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.net.URLEncoder

data class Identity(val accountId: String, val name: String)
data class PushResult(val remoteEntryId: String?)

/** One signed-in list service. All calls take the access token explicitly; nothing is logged. */
interface AccountApi {
    val provider: AccountProvider
    suspend fun identify(token: String): Identity
    suspend fun fetchList(token: String, who: Identity): List<RemoteListEntry>
    /** Sends [entry]'s tracking fields. Throws [ProviderException] (kind AUTH = sign in again). */
    suspend fun push(token: String, who: Identity, entry: AnimeEntry): PushResult
}

internal fun form(vararg pairs: Pair<String, String?>): String =
    pairs.filter { it.second != null }.joinToString("&") {
        URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second!!, "UTF-8")
    }

private const val FORM = "application/x-www-form-urlencoded"

// ── AniList ─────────────────────────────────────────────────────────────────

class AniListAccountApi(private val http: HttpTransport) : AccountApi {
    override val provider = AccountProvider.ANILIST
    private val url = "https://graphql.anilist.co"

    private suspend fun gql(token: String, query: String, vars: JsonObject): JsonObject {
        val body = JsonObject().apply { addProperty("query", AniListAdapter.gql(query)); add("variables", vars) }
        val res = http.execute(
            HttpRequestSpec(url, "POST", mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"), body.toString())
        )
        HttpErrors.check(res, "AniList")
        val root = HttpErrors.parse(res.body, "AniList").asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "AniList returned an unexpected response")
        root.arr("errors")?.objects()?.firstOrNull()?.let {
            throw ProviderException(FailureKind.CLIENT, "AniList: ${it.str("message") ?: "request failed"}")
        }
        return root.obj("data") ?: throw ProviderException(FailureKind.MALFORMED, "AniList response has no data")
    }

    override suspend fun identify(token: String): Identity {
        val v = gql(token, "query { Viewer { id name } }", JsonObject()).obj("Viewer")
            ?: throw ProviderException(FailureKind.AUTH, "AniList did not accept this sign-in")
        return Identity(v.str("id") ?: throw ProviderException(FailureKind.MALFORMED, "AniList: no user id"), v.str("name") ?: "AniList user")
    }

    override suspend fun fetchList(token: String, who: Identity): List<RemoteListEntry> {
        val q = "query (§userId: Int) { MediaListCollection(userId: §userId, type: ANIME) { lists { entries { " +
            "id status progress score(format: POINT_10_DECIMAL) startedAt { year month day } completedAt { year month day } updatedAt " +
            "media { ${AniListAdapter.MEDIA_FIELDS} } } } } }"
        val vars = JsonObject().apply { addProperty("userId", who.accountId.toIntOrNull() ?: 0) }
        val lists = gql(token, q, vars).obj("MediaListCollection")?.arr("lists")?.objects().orEmpty()
        val out = LinkedHashMap<String, RemoteListEntry>()
        for (list in lists) for (e in list.arr("entries")?.objects().orEmpty()) {
            val media = e.obj("media")?.let { AniListAdapter.parseMedia("anilist", "AniList", it) } ?: continue
            out[media.providerId] = RemoteListEntry(
                remoteMediaId = media.providerId,
                remoteEntryId = null,
                status = statusFrom(e.str("status")),
                progress = e.int("progress") ?: 0,
                score10 = (e.dbl("score") ?: 0.0).toFloat(),
                startedAt = fuzzy(e.obj("startedAt")),
                completedAt = fuzzy(e.obj("completedAt")),
                updatedAt = (e.str("updatedAt")?.toLongOrNull() ?: 0L) * 1000L,
                media = media
            )
        }
        return out.values.toList()
    }

    override suspend fun push(token: String, who: Identity, entry: AnimeEntry): PushResult {
        val mediaId = RemoteIds.parse(entry.remoteIds)["anilist"]?.toIntOrNull()
            ?: throw ProviderException(FailureKind.CLIENT, "This title has no AniList id")
        val q = "mutation (§mediaId: Int, §status: MediaListStatus, §progress: Int, §score: Int, §started: FuzzyDateInput, §completed: FuzzyDateInput) { " +
            "SaveMediaListEntry(mediaId: §mediaId, status: §status, progress: §progress, scoreRaw: §score, startedAt: §started, completedAt: §completed) { id } }"
        val vars = JsonObject().apply {
            addProperty("mediaId", mediaId)
            addProperty("status", statusTo(entry.status))
            addProperty("progress", entry.watchedEpisodes)
            addProperty("score", Math.round(entry.rating * 10f))
            add("started", fuzzyInput(entry.startDate))
            add("completed", fuzzyInput(entry.endDate))
        }
        gql(token, q, vars)
        return PushResult(null)
    }

    private fun fuzzyInput(ms: Long?): JsonElement = if (ms == null) {
        JsonObject().apply { add("year", JsonNull.INSTANCE); add("month", JsonNull.INSTANCE); add("day", JsonNull.INSTANCE) }
    } else {
        val (y, m, d) = Dates.toParts(ms)
        JsonObject().apply { addProperty("year", y); addProperty("month", m); addProperty("day", d) }
    }

    private fun fuzzy(o: JsonObject?): Long? = if (o == null) null else Dates.fromParts(o.int("year"), o.int("month"), o.int("day"))

    companion object {
        fun statusFrom(s: String?) = when (s) {
            "CURRENT", "REPEATING" -> WatchStatus.WATCHING
            "COMPLETED" -> WatchStatus.COMPLETED
            "PAUSED" -> WatchStatus.ON_HOLD
            "DROPPED" -> WatchStatus.DROPPED
            else -> WatchStatus.PLAN_TO_WATCH
        }
        fun statusTo(s: WatchStatus) = when (s) {
            WatchStatus.WATCHING -> "CURRENT"
            WatchStatus.COMPLETED -> "COMPLETED"
            WatchStatus.ON_HOLD -> "PAUSED"
            WatchStatus.DROPPED -> "DROPPED"
            WatchStatus.PLAN_TO_WATCH -> "PLANNING"
        }
    }
}

// ── MyAnimeList ─────────────────────────────────────────────────────────────

class MalAccountApi(private val http: HttpTransport) : AccountApi {
    override val provider = AccountProvider.MAL
    private val base = "https://api.myanimelist.net/v2"

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    override suspend fun identify(token: String): Identity {
        val res = http.execute(HttpRequestSpec("$base/users/@me", headers = auth(token)))
        HttpErrors.check(res, "MyAnimeList")
        val o = HttpErrors.parse(res.body, "MyAnimeList").asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "MyAnimeList returned an unexpected response")
        val name = o.str("name") ?: throw ProviderException(FailureKind.MALFORMED, "MyAnimeList: no user name")
        return Identity(o.str("id") ?: name, name)
    }

    override suspend fun fetchList(token: String, who: Identity): List<RemoteListEntry> {
        val out = ArrayList<RemoteListEntry>()
        var offset = 0
        repeat(MAX_PAGES) {
            val url = "$base/users/@me/animelist?fields=list_status,${MalAdapter.FIELDS}&limit=$PAGE&offset=$offset&nsfw=1"
            val res = http.execute(HttpRequestSpec(url, headers = auth(token)))
            HttpErrors.check(res, "MyAnimeList")
            val root = HttpErrors.parse(res.body, "MyAnimeList").asObjOrNull()
                ?: throw ProviderException(FailureKind.MALFORMED, "MyAnimeList returned an unexpected response")
            val data = root.arr("data") ?: throw ProviderException(FailureKind.MALFORMED, "MyAnimeList: response has no list")
            val items = data.objects()
            for (item in items) {
                val node = item.obj("node") ?: continue
                val media = MalAdapter.parseNode("mal", "MyAnimeList", node) ?: continue
                val ls = item.obj("list_status") ?: continue
                out += RemoteListEntry(
                    remoteMediaId = media.providerId,
                    remoteEntryId = null,
                    status = statusFrom(ls.str("status")),
                    progress = ls.int("num_episodes_watched") ?: 0,
                    score10 = (ls.int("score") ?: 0).toFloat(),
                    startedAt = Dates.parseToMillis(ls.str("start_date")),
                    completedAt = Dates.parseToMillis(ls.str("finish_date")),
                    updatedAt = Dates.parseInstant(ls.str("updated_at")) ?: 0L,
                    media = media
                )
            }
            if (root.obj("paging")?.str("next").isNullOrBlank() || items.isEmpty()) return out
            offset += PAGE
        }
        return out
    }

    override suspend fun push(token: String, who: Identity, entry: AnimeEntry): PushResult {
        val id = RemoteIds.parse(entry.remoteIds)["mal"]?.toLongOrNull()
            ?: throw ProviderException(FailureKind.CLIENT, "This title has no MyAnimeList id")
        val body = form(
            "status" to statusTo(entry.status),
            "score" to Math.round(entry.rating).coerceIn(0, 10).toString(),
            "num_watched_episodes" to entry.watchedEpisodes.toString(),
            "start_date" to entry.startDate?.let { Dates.toIsoDate(it) },
            "finish_date" to entry.endDate?.let { Dates.toIsoDate(it) }
        )
        val res = http.execute(HttpRequestSpec("$base/anime/$id/my_list_status", "PATCH", auth(token), body, FORM))
        HttpErrors.check(res, "MyAnimeList")
        return PushResult(null)
    }

    companion object {
        private const val PAGE = 100
        private const val MAX_PAGES = 60
        fun statusFrom(s: String?) = when (s) {
            "watching" -> WatchStatus.WATCHING
            "completed" -> WatchStatus.COMPLETED
            "on_hold" -> WatchStatus.ON_HOLD
            "dropped" -> WatchStatus.DROPPED
            else -> WatchStatus.PLAN_TO_WATCH
        }
        fun statusTo(s: WatchStatus) = when (s) {
            WatchStatus.WATCHING -> "watching"
            WatchStatus.COMPLETED -> "completed"
            WatchStatus.ON_HOLD -> "on_hold"
            WatchStatus.DROPPED -> "dropped"
            WatchStatus.PLAN_TO_WATCH -> "plan_to_watch"
        }
    }
}

// ── Kitsu ───────────────────────────────────────────────────────────────────

class KitsuAccountApi(private val http: HttpTransport) : AccountApi {
    override val provider = AccountProvider.KITSU
    private val base = "https://kitsu.io/api/edge"
    private val json = "application/vnd.api+json"

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to json)

    override suspend fun identify(token: String): Identity {
        val res = http.execute(HttpRequestSpec("$base/users?filter[self]=true", headers = auth(token)))
        HttpErrors.check(res, "Kitsu")
        val first = HttpErrors.parse(res.body, "Kitsu").asObjOrNull()?.arr("data")?.objects()?.firstOrNull()
            ?: throw ProviderException(FailureKind.AUTH, "Kitsu did not accept this sign-in")
        val id = first.str("id") ?: throw ProviderException(FailureKind.MALFORMED, "Kitsu: no user id")
        return Identity(id, first.obj("attributes")?.str("name") ?: "Kitsu user")
    }

    override suspend fun fetchList(token: String, who: Identity): List<RemoteListEntry> {
        val out = ArrayList<RemoteListEntry>()
        var next: String? = "$base/library-entries?filter[user_id]=${who.accountId}&filter[kind]=anime&include=anime&page[limit]=100"
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val res = http.execute(HttpRequestSpec(next, headers = auth(token)))
            HttpErrors.check(res, "Kitsu")
            val root = HttpErrors.parse(res.body, "Kitsu").asObjOrNull()
                ?: throw ProviderException(FailureKind.MALFORMED, "Kitsu returned an unexpected response")
            val data = root.arr("data") ?: throw ProviderException(FailureKind.MALFORMED, "Kitsu: response has no list")
            val included = root.arr("included")?.objects().orEmpty()
                .filter { it.str("type") == "anime" }.associateBy { it.str("id") }
            for (e in data.objects()) {
                val a = e.obj("attributes") ?: continue
                val animeId = e.obj("relationships")?.obj("anime")?.obj("data")?.str("id") ?: continue
                val media = included[animeId]?.let { KitsuAdapter.parseItem("kitsu", "Kitsu", it) } ?: continue
                out += RemoteListEntry(
                    remoteMediaId = animeId,
                    remoteEntryId = e.str("id"),
                    status = statusFrom(a.str("status")),
                    progress = a.int("progress") ?: 0,
                    score10 = (a.int("ratingTwenty") ?: 0) / 2f,
                    startedAt = Dates.parseInstant(a.str("startedAt")),
                    completedAt = Dates.parseInstant(a.str("finishedAt")),
                    updatedAt = Dates.parseInstant(a.str("updatedAt")) ?: 0L,
                    media = media
                )
            }
            next = root.obj("links")?.str("next")?.takeIf { it.startsWith("https://kitsu.io/") }
        }
        return out
    }

    override suspend fun push(token: String, who: Identity, entry: AnimeEntry): PushResult {
        val ids = RemoteIds.parse(entry.remoteIds)
        val animeId = ids["kitsu"] ?: throw ProviderException(FailureKind.CLIENT, "This title has no Kitsu id")
        val entryId = ids["kitsuEntry"]
        val attrs = JsonObject().apply {
            addProperty("status", statusTo(entry.status))
            addProperty("progress", entry.watchedEpisodes)
            val twenty = Math.round(entry.rating * 2f)
            if (twenty >= 2) addProperty("ratingTwenty", twenty.coerceAtMost(20)) else add("ratingTwenty", JsonNull.INSTANCE)
            entry.startDate?.let { addProperty("startedAt", Dates.toIsoDate(it) + "T00:00:00.000Z") }
            entry.endDate?.let { addProperty("finishedAt", Dates.toIsoDate(it) + "T00:00:00.000Z") }
        }
        val data = JsonObject().apply {
            addProperty("type", "libraryEntries")
            if (entryId != null) addProperty("id", entryId)
            add("attributes", attrs)
            if (entryId == null) {
                val rel = JsonObject()
                rel.add("user", ref("users", who.accountId))
                rel.add("anime", ref("anime", animeId))
                add("relationships", rel)
            }
        }
        val body = JsonObject().apply { add("data", data) }.toString()
        val res = if (entryId != null) {
            http.execute(HttpRequestSpec("$base/library-entries/$entryId", "PATCH", auth(token), body, json))
        } else {
            http.execute(HttpRequestSpec("$base/library-entries", "POST", auth(token), body, json))
        }
        HttpErrors.check(res, "Kitsu")
        val newId = if (entryId == null) {
            HttpErrors.parse(res.body, "Kitsu").asObjOrNull()?.obj("data")?.str("id")
        } else null
        return PushResult(newId)
    }

    private fun ref(type: String, id: String) = JsonObject().apply {
        add("data", JsonObject().apply { addProperty("type", type); addProperty("id", id) })
    }

    companion object {
        private const val MAX_PAGES = 100
        fun statusFrom(s: String?) = when (s) {
            "current" -> WatchStatus.WATCHING
            "completed" -> WatchStatus.COMPLETED
            "on_hold" -> WatchStatus.ON_HOLD
            "dropped" -> WatchStatus.DROPPED
            else -> WatchStatus.PLAN_TO_WATCH
        }
        fun statusTo(s: WatchStatus) = when (s) {
            WatchStatus.WATCHING -> "current"
            WatchStatus.COMPLETED -> "completed"
            WatchStatus.ON_HOLD -> "on_hold"
            WatchStatus.DROPPED -> "dropped"
            WatchStatus.PLAN_TO_WATCH -> "planned"
        }
    }
}
