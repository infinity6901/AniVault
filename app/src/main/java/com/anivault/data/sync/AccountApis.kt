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
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

data class Identity(val accountId: String, val name: String)
data class PushResult(val remoteEntryId: String?)
/** Optional extras sent with a push: [notes] replaces the entry's notes when non-null, [private] its visibility. */
data class PushExtra(val notes: String? = null, val private: Boolean? = null)
data class PushItem(val entry: AnimeEntry, val extra: PushExtra = PushExtra())
data class PushOutcome(val item: PushItem, val result: PushResult? = null, val error: ProviderException? = null)

/** One signed-in list service. All calls take the access token explicitly; nothing is logged. */
interface AccountApi {
    val provider: AccountProvider
    suspend fun identify(token: String): Identity
    suspend fun fetchList(token: String, who: Identity): List<RemoteListEntry>
    /** Sends [entry]'s tracking fields. Throws [ProviderException] (kind AUTH = sign in again). */
    suspend fun push(token: String, who: Identity, entry: AnimeEntry, extra: PushExtra = PushExtra()): PushResult

    /** Sends several entries. A rate limit stops the run: the remaining items come back with a RATE_LIMITED error. */
    suspend fun pushMany(token: String, who: Identity, items: List<PushItem>): List<PushOutcome> {
        val out = ArrayList<PushOutcome>(items.size)
        var limited: ProviderException? = null
        for (item in items) {
            if (limited != null) { out += PushOutcome(item, error = limited); continue }
            try {
                out += PushOutcome(item, result = push(token, who, item.entry, item.extra))
            } catch (e: ProviderException) {
                if (e.kind == FailureKind.AUTH) throw e
                if (e.kind == FailureKind.RATE_LIMITED) limited = e
                out += PushOutcome(item, error = e)
            }
        }
        return out
    }

    /** Removes the title from the account's list. A title that is already gone counts as success. */
    suspend fun delete(token: String, who: Identity, mediaId: String, entryId: String?)

    /** Writes or clears the H-Vault tag (and privacy where the service has it) on an existing list entry. */
    suspend fun mark(token: String, who: Identity, entry: RemoteListEntry, vault: Boolean)
}

/** Treats any 2xx (even with an empty body) and 404 as success for delete calls. */
internal fun checkDelete(res: com.anivault.data.provider.HttpResult, what: String) {
    if (res.code in 200..299 || res.code == 404) return
    HttpErrors.check(res, what)
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

    private val gate = kotlinx.coroutines.sync.Mutex()
    private var lastCall = 0L

    /** AniList allows ~90 requests a minute (30 when it is degraded), so calls are spaced out and 429s are waited out once or twice. */
    private suspend fun gql(token: String, query: String, vars: JsonObject): JsonObject {
        val body = JsonObject().apply { addProperty("query", AniListAdapter.gql(query)); add("variables", vars) }
        var attempt = 0
        while (true) {
            gate.withLock {
                val wait = lastCall + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                lastCall = System.currentTimeMillis()
            }
            val res = http.execute(
                HttpRequestSpec(url, "POST", mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"), body.toString())
            )
            if (res.code == 429 && attempt < 2) {
                val ra = res.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim()?.toLongOrNull()
                kotlinx.coroutines.delay(((ra ?: 60L).coerceIn(5L, 90L)) * 1000L)
                attempt++
                continue
            }
            HttpErrors.check(res, "AniList")
            val root = HttpErrors.parse(res.body, "AniList").asObjOrNull()
                ?: throw ProviderException(FailureKind.MALFORMED, "AniList returned an unexpected response")
            root.arr("errors")?.objects()?.firstOrNull()?.let {
                throw ProviderException(FailureKind.CLIENT, "AniList: ${it.str("message") ?: "request failed"}")
            }
            return root.obj("data") ?: throw ProviderException(FailureKind.MALFORMED, "AniList response has no data")
        }
    }

    override suspend fun identify(token: String): Identity {
        val v = gql(token, "query { Viewer { id name } }", JsonObject()).obj("Viewer")
            ?: throw ProviderException(FailureKind.AUTH, "AniList did not accept this sign-in")
        return Identity(v.str("id") ?: throw ProviderException(FailureKind.MALFORMED, "AniList: no user id"), v.str("name") ?: "AniList user")
    }

    override suspend fun fetchList(token: String, who: Identity): List<RemoteListEntry> {
        val q = "query (§userId: Int) { MediaListCollection(userId: §userId, type: ANIME) { lists { entries { " +
            "id status progress notes score(format: POINT_10_DECIMAL) startedAt { year month day } completedAt { year month day } updatedAt " +
            "media { ${AniListAdapter.MEDIA_FIELDS} } } } } }"
        val vars = JsonObject().apply { addProperty("userId", who.accountId.toIntOrNull() ?: 0) }
        val lists = gql(token, q, vars).obj("MediaListCollection")?.arr("lists")?.objects().orEmpty()
        val out = LinkedHashMap<String, RemoteListEntry>()
        for (list in lists) for (e in list.arr("entries")?.objects().orEmpty()) {
            val media = e.obj("media")?.let { AniListAdapter.parseMedia("anilist", "AniList", it) } ?: continue
            out[media.providerId] = RemoteListEntry(
                remoteMediaId = media.providerId,
                remoteEntryId = e.str("id"),
                notes = e.str("notes") ?: "",
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

    private fun mediaIdOf(entry: AnimeEntry): Int = RemoteIds.parse(entry.remoteIds)["anilist"]?.toIntOrNull()
        ?: throw ProviderException(FailureKind.CLIENT, "This title has no AniList id")

    /** Adds one aliased SaveMediaListEntry to a (possibly batched) mutation. */
    private fun saveFields(i: Int, entry: AnimeEntry, extra: PushExtra, decl: StringBuilder, call: StringBuilder, vars: JsonObject) {
        decl.append("§m$i: Int, §st$i: MediaListStatus, §p$i: Int, §sc$i: Int, §s$i: FuzzyDateInput, §c$i: FuzzyDateInput, ")
        vars.addProperty("m$i", mediaIdOf(entry))
        vars.addProperty("st$i", statusTo(entry.status))
        vars.addProperty("p$i", entry.watchedEpisodes)
        vars.addProperty("sc$i", Math.round(entry.rating * 10f))
        vars.add("s$i", fuzzyInput(entry.startDate))
        vars.add("c$i", fuzzyInput(entry.endDate))
        var args = "mediaId: §m$i, status: §st$i, progress: §p$i, scoreRaw: §sc$i, startedAt: §s$i, completedAt: §c$i"
        extra.notes?.let { decl.append("§n$i: String, "); vars.addProperty("n$i", it); args += ", notes: §n$i" }
        extra.private?.let { decl.append("§pr$i: Boolean, "); vars.addProperty("pr$i", it); args += ", private: §pr$i" }
        call.append("a$i: SaveMediaListEntry($args) { id } ")
    }

    private suspend fun saveBatch(token: String, items: List<PushItem>) {
        val decl = StringBuilder(); val call = StringBuilder(); val vars = JsonObject()
        items.forEachIndexed { i, it -> saveFields(i, it.entry, it.extra, decl, call, vars) }
        gql(token, "mutation (${decl.toString().trimEnd(',', ' ')}) { $call}", vars)
    }

    override suspend fun push(token: String, who: Identity, entry: AnimeEntry, extra: PushExtra): PushResult {
        saveBatch(token, listOf(PushItem(entry, extra)))
        return PushResult(null)
    }

    /** Several saves per request keeps a big first sync well inside AniList's per-minute limit. */
    override suspend fun pushMany(token: String, who: Identity, items: List<PushItem>): List<PushOutcome> {
        val out = ArrayList<PushOutcome>(items.size)
        var limited: ProviderException? = null
        for (chunk in items.chunked(BATCH)) {
            if (limited != null) { chunk.forEach { out += PushOutcome(it, error = limited) }; continue }
            try {
                saveBatch(token, chunk)
                chunk.forEach { out += PushOutcome(it, result = PushResult(null)) }
            } catch (e: ProviderException) {
                when (e.kind) {
                    FailureKind.AUTH -> throw e
                    FailureKind.RATE_LIMITED -> { limited = e; chunk.forEach { out += PushOutcome(it, error = e) } }
                    // One bad entry fails the whole batch, so retry the chunk one by one to isolate it.
                    else -> chunk.forEach { item ->
                        try { saveBatch(token, listOf(item)); out += PushOutcome(item, result = PushResult(null)) }
                        catch (e2: ProviderException) {
                            if (e2.kind == FailureKind.AUTH) throw e2
                            if (e2.kind == FailureKind.RATE_LIMITED) limited = e2
                            out += PushOutcome(item, error = e2)
                        }
                    }
                }
            }
        }
        return out
    }

    override suspend fun delete(token: String, who: Identity, mediaId: String, entryId: String?) {
        val id = entryId?.toIntOrNull() ?: return // not on the list (any more)
        gql(token, "mutation (§id: Int) { DeleteMediaListEntry(id: §id) { deleted } }", JsonObject().apply { addProperty("id", id) })
    }

    override suspend fun mark(token: String, who: Identity, entry: RemoteListEntry, vault: Boolean) {
        val q = "mutation (§m: Int, §n: String, §pr: Boolean) { SaveMediaListEntry(mediaId: §m, notes: §n, private: §pr) { id } }"
        val vars = JsonObject().apply {
            addProperty("m", entry.remoteMediaId.toIntOrNull() ?: 0)
            addProperty("n", VaultOwnership.withMark(entry.notes, vault))
            addProperty("pr", vault)
        }
        gql(token, q, vars)
    }

    private fun fuzzyInput(ms: Long?): JsonElement = if (ms == null) {
        JsonObject().apply { add("year", JsonNull.INSTANCE); add("month", JsonNull.INSTANCE); add("day", JsonNull.INSTANCE) }
    } else {
        val (y, m, d) = Dates.toParts(ms)
        JsonObject().apply { addProperty("year", y); addProperty("month", m); addProperty("day", d) }
    }

    private fun fuzzy(o: JsonObject?): Long? = if (o == null) null else Dates.fromParts(o.int("year"), o.int("month"), o.int("day"))

    companion object {
        private const val MIN_GAP_MS = 750L
        private const val BATCH = 8
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

    override suspend fun push(token: String, who: Identity, entry: AnimeEntry, extra: PushExtra): PushResult {
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

    override suspend fun delete(token: String, who: Identity, mediaId: String, entryId: String?) {
        checkDelete(http.execute(HttpRequestSpec("$base/anime/$mediaId/my_list_status", "DELETE", auth(token))), "MyAnimeList")
    }

    /** MyAnimeList has no private flag and its list notes cannot be read back safely, so nothing is written there. */
    override suspend fun mark(token: String, who: Identity, entry: RemoteListEntry, vault: Boolean) = Unit

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
                    notes = a.str("notes") ?: "",
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

    override suspend fun push(token: String, who: Identity, entry: AnimeEntry, extra: PushExtra): PushResult {
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
            extra.notes?.let { addProperty("notes", it) }
            extra.private?.let { addProperty("private", it) }
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

    override suspend fun delete(token: String, who: Identity, mediaId: String, entryId: String?) {
        val id = entryId ?: return
        checkDelete(http.execute(HttpRequestSpec("$base/library-entries/$id", "DELETE", auth(token))), "Kitsu")
    }

    override suspend fun mark(token: String, who: Identity, entry: RemoteListEntry, vault: Boolean) {
        val id = entry.remoteEntryId ?: return
        val attrs = JsonObject().apply {
            addProperty("notes", VaultOwnership.withMark(entry.notes, vault))
            addProperty("private", vault)
        }
        val data = JsonObject().apply { addProperty("type", "libraryEntries"); addProperty("id", id); add("attributes", attrs) }
        val body = JsonObject().apply { add("data", data) }.toString()
        HttpErrors.check(http.execute(HttpRequestSpec("$base/library-entries/$id", "PATCH", auth(token), body, json)), "Kitsu")
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
