package com.anivault.data.provider

import android.content.Context
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** The next episode of a title in the user's library, or a seasonal airing slot on the calendar. */
data class AiringItem(
    val libraryId: String,
    val title: String,
    val coverUrl: String,
    val episode: Int,
    val airingAt: Long,
    val watched: Int,
    val media: LookupMedia? = null
)

object AiringFormat {
    /** "in 3h", "in 2d 4h", "airing now" or "aired 2h ago" for a time relative to [now]. */
    fun relative(airingAt: Long, now: Long): String {
        val diff = airingAt - now
        val abs = Math.abs(diff)
        val d = abs / 86_400_000L; val h = (abs % 86_400_000L) / 3_600_000L; val m = (abs % 3_600_000L) / 60_000L
        val span = when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
        return if (abs < 60_000L) "airing now" else if (diff > 0) "in $span" else "aired $span ago"
    }

    /** 0 = Monday .. 6 = Sunday in local time for grouping a weekly schedule. */
    fun dayOfWeekIndex(epochMillis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
    }
}

/** Keeps a small cache of "what airs next" for the shows being watched, refreshed from AniList at most every few hours. */
@Singleton
class AiringRepository @Inject constructor(
    @ApplicationContext ctx: Context,
    private val dao: AnimeDao,
    private val lookup: AniListLookup
) {
    private val sp = ctx.getSharedPreferences("anivault_airing", Context.MODE_PRIVATE)
    @Volatile private var globalCache: List<AiringScheduleSlot> = emptyList()
    @Volatile private var globalCacheAt: Long = 0L

    fun lastRefresh(): Long = sp.getLong("at", 0L)

    /** Refreshes the cache when it is older than [maxAgeMs]. Returns true when new data was fetched. */
    suspend fun refresh(maxAgeMs: Long = 3 * 3_600_000L): Boolean {
        if (System.currentTimeMillis() - lastRefresh() < maxAgeMs) return false
        val tracked = dao.getAll().filter { it.status == WatchStatus.WATCHING || it.status == WatchStatus.PLAN_TO_WATCH }
        val byAl = HashMap<Int, String>(); val byMal = HashMap<Int, String>()
        tracked.forEach { e ->
            val ids = RemoteIds.parse(e.remoteIds)
            val al = ids["anilist"]?.toIntOrNull(); val mal = ids["mal"]?.toIntOrNull()
            if (al != null) byAl[al] = e.id else if (mal != null) byMal[mal] = e.id
        }
        val found = ArrayList<Pair<String, LookupMedia>>()
        if (byAl.isNotEmpty()) lookup.byIds(byAl.keys).forEach { m -> byAl[m.anilistId]?.let { found += it to m } }
        if (byMal.isNotEmpty()) lookup.byIds(byMal.keys, byMal = true).forEach { m -> m.malId?.let { byMal[it] }?.let { found += it to m } }
        val arr = JSONArray()
        found.filter { it.second.nextEpisode != null && it.second.nextAiringAt != null }.forEach { (libId, m) ->
            arr.put(JSONObject().put("id", libId).put("t", m.title).put("c", m.coverUrl).put("e", m.nextEpisode).put("a", m.nextAiringAt))
        }
        sp.edit().putString("items", arr.toString()).putLong("at", System.currentTimeMillis()).apply()
        return true
    }

    /** Cached next episodes, soonest first. Titles dropped from the library since the last refresh are filtered out. */
    suspend fun items(): List<AiringItem> {
        val a = runCatching { JSONArray(sp.getString("items", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val library = dao.getAll().associateBy { it.id }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val entry = library[o.optString("id")] ?: return@mapNotNull null
            AiringItem(entry.id, o.optString("t"), o.optString("c"), o.optInt("e"), o.optLong("a"), entry.watchedEpisodes)
        }.sortedBy { it.airingAt }
    }

    /** Full 7-day seasonal airing schedule (Monday 00:00 to Sunday 23:59 local time), linked to library entries when owned. */
    suspend fun globalWeek(force: Boolean = false): List<AiringItem> {
        val now = System.currentTimeMillis()
        if (force || globalCache.isEmpty() || now - globalCacheAt > 2 * 3_600_000L) {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val startSec = cal.timeInMillis / 1000L
            val endSec = startSec + 7 * 86_400L
            runCatching { lookup.weeklySchedule(startSec, endSec) }.onSuccess {
                globalCache = it
                globalCacheAt = now
            }
        }
        val library = dao.getAll()
        val byAnilist = HashMap<String, com.anivault.data.local.entity.AnimeEntry>()
        val byMal = HashMap<String, com.anivault.data.local.entity.AnimeEntry>()
        library.forEach { e ->
            val ids = RemoteIds.parse(e.remoteIds)
            ids["anilist"]?.let { byAnilist[it] = e }
            ids["mal"]?.let { byMal[it] = e }
        }
        return globalCache.map { slot ->
            val libEntry = byAnilist[slot.media.anilistId.toString()]
                ?: slot.media.malId?.let { byMal[it.toString()] }
            AiringItem(
                libraryId = libEntry?.id ?: "",
                title = libEntry?.title ?: slot.media.title,
                coverUrl = libEntry?.imageUrl?.ifBlank { slot.media.coverUrl } ?: slot.media.coverUrl,
                episode = slot.episode,
                airingAt = slot.airingAt,
                watched = libEntry?.watchedEpisodes ?: 0,
                media = slot.media
            )
        }.sortedBy { it.airingAt }
    }

    /** Keys ("libraryId:episode") already announced, so every episode notifies once. */
    fun wasNotified(key: String) = sp.getStringSet("notified", emptySet())?.contains(key) == true
    fun markNotified(key: String) {
        val set = (sp.getStringSet("notified", emptySet()) ?: emptySet()).toMutableSet()
        set += key
        // Keep the set small: episodes older than the newest 200 keys are irrelevant.
        val trimmed = if (set.size > 200) set.toList().takeLast(200).toSet() else set
        sp.edit().putStringSet("notified", trimmed).apply()
    }
}
