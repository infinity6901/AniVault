package com.anivault.data.library

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.provider.AniListLookup
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One anime row from a MyAnimeList export (https://myanimelist.net/panel.php?go=export). */
data class MalRow(
    val malId: Int, val title: String, val episodes: Int, val watched: Int, val status: WatchStatus,
    val score: Float, val start: Long?, val finish: Long?, val comments: String, val tags: List<String>
)

data class MalImportResult(val added: Int, val skipped: Int, val toVault: Int, val failed: Boolean = false)

object MalParser {
    private val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun status(raw: String): WatchStatus = when (raw.trim().lowercase()) {
        "1", "watching", "currently watching" -> WatchStatus.WATCHING
        "2", "completed" -> WatchStatus.COMPLETED
        "3", "on-hold", "on hold", "onhold" -> WatchStatus.ON_HOLD
        "4", "dropped" -> WatchStatus.DROPPED
        "6", "plan to watch", "plantowatch", "plan_to_watch" -> WatchStatus.PLAN_TO_WATCH
        else -> WatchStatus.PLAN_TO_WATCH
    }

    /** MAL writes unset dates as 0000-00-00. */
    fun date(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("0000") || t.contains("-00")) return null
        return runCatching { DATE.parse(t)?.time }.getOrNull()
    }

    /** Accepts plain XML or gzip (MAL serves .xml.gz). */
    fun open(raw: InputStream): InputStream {
        val b = java.io.BufferedInputStream(raw)
        b.mark(2)
        val a = b.read(); val c = b.read()
        b.reset()
        return if (a == 0x1f && c == 0x8b) GZIPInputStream(b) else b
    }

    fun parse(input: InputStream): List<MalRow> {
        val f = DocumentBuilderFactory.newInstance()
        // Untrusted file: no DTDs / external entities.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.isXIncludeAware = false; f.isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().parse(open(input))
        val nodes = doc.getElementsByTagName("anime")
        val out = ArrayList<MalRow>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            fun t(n: String) = e.getElementsByTagName(n).item(0)?.textContent?.trim().orEmpty()
            val id = t("series_animedb_id").toIntOrNull() ?: continue
            val eps = t("series_episodes").toIntOrNull() ?: 0
            out += MalRow(
                malId = id, title = t("series_title"), episodes = eps,
                watched = (t("my_watched_episodes").toIntOrNull() ?: 0).coerceAtLeast(0),
                status = status(t("my_status")),
                score = (t("my_score").toFloatOrNull() ?: 0f).coerceIn(0f, 10f),
                start = date(t("my_start_date")), finish = date(t("my_finish_date")),
                comments = t("my_comments"),
                tags = t("my_tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        }
        return out
    }

    fun toEntry(r: MalRow, now: Long) = AnimeEntry(
        id = "mal:${r.malId}", title = r.title, totalEpisodes = r.episodes,
        watchedEpisodes = if (r.episodes > 0) r.watched.coerceAtMost(r.episodes) else r.watched,
        status = r.status, rating = r.score, notes = r.comments, tags = r.tags,
        startDate = r.start, endDate = r.finish, updatedAt = now, addedAt = now,
        sourceName = "MyAnimeList import", remoteIds = RemoteIds.encode(mapOf("mal" to r.malId.toString())),
        pendingSync = false
    )
}

@Singleton
class MalImporter @Inject constructor(
    private val dao: AnimeDao, private val vaultDao: HVaultDao, private val lookup: AniListLookup
) {
    /** Adds titles not already present, fills in covers/genres via AniList, and routes adult titles to H-Vault. */
    suspend fun import(input: InputStream): MalImportResult {
        val rows = runCatching { MalParser.parse(input) }.getOrElse { return MalImportResult(0, 0, 0, failed = true) }
        val now = System.currentTimeMillis()
        val existing = dao.getAll().map { it.id }.toHashSet() + vaultDao.getAll().map { it.id }.toHashSet()
        val fresh = rows.filter { "mal:${it.malId}" !in existing && "sync:mal:${it.malId}" !in existing }
        val meta = runCatching { lookup.byIds(fresh.map { it.malId }, byMal = true).associateBy { it.malId } }.getOrDefault(emptyMap())
        val lib = ArrayList<AnimeEntry>(); val vault = ArrayList<com.anivault.data.local.entity.HVaultEntry>()
        for (r in fresh) {
            var e = MalParser.toEntry(r, now)
            val m = meta[r.malId]
            if (m != null) {
                e = e.copy(
                    imageUrl = m.coverUrl, genres = m.genres, totalEpisodes = if (e.totalEpisodes > 0) e.totalEpisodes else m.episodes,
                    durationMinutes = m.durationMinutes, year = m.year, season = m.season, format = m.format,
                    synopsis = m.synopsis, airingStatus = m.airingStatus,
                    remoteIds = RemoteIds.encode(mapOf("mal" to r.malId.toString(), "anilist" to m.anilistId.toString()))
                )
            }
            if (m?.adult == true) vault += VaultMapping.toVault(e.copy(id = "mal:${r.malId}"), now).copy(id = "sync:mal:${r.malId}")
            else lib += e
        }
        dao.insertAllIgnore(lib)
        vaultDao.insertAllIgnore(vault)
        return MalImportResult(lib.size, rows.size - fresh.size, vault.size)
    }
}
