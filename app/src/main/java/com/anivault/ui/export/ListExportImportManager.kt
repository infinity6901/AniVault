package com.anivault.ui.export

import android.content.Context
import android.net.Uri
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.Dates
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class CsvJsonImportResult(val addedAnime: Int = 0, val addedVault: Int = 0, val skipped: Int = 0, val failed: Boolean = false)

/**
 * Pure, JVM-testable codecs for MyAnimeList XML export and RFC-4180 CSV export/import.
 */
object ListExportCodec {

    fun toMalXml(entries: List<AnimeEntry>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
        sb.append("<myanimelist>\n")
        sb.append("  <myinfo>\n")
        sb.append("    <user_export_type>1</user_export_type>\n")
        sb.append("    <user_total_anime>${entries.size}</user_total_anime>\n")
        sb.append("  </myinfo>\n")
        for (e in entries) {
            val ids = RemoteIds.parse(e.remoteIds)
            val malId = ids["mal"]?.toIntOrNull()
                ?: e.id.removePrefix("mal:").toIntOrNull()
                ?: ids["anilist"]?.toIntOrNull()
                ?: 0
            val malStatus = when (e.status) {
                WatchStatus.WATCHING -> "Watching"
                WatchStatus.COMPLETED -> "Completed"
                WatchStatus.ON_HOLD -> "On-Hold"
                WatchStatus.DROPPED -> "Dropped"
                WatchStatus.PLAN_TO_WATCH -> "Plan to Watch"
            }
            val start = e.startDate?.let { Dates.toIsoDate(it) } ?: "0000-00-00"
            val finish = e.endDate?.let { Dates.toIsoDate(it) } ?: "0000-00-00"
            val score = Math.round(e.rating).coerceIn(0, 10)
            sb.append("  <anime>\n")
            sb.append("    <series_animedb_id>$malId</series_animedb_id>\n")
            sb.append("    <series_title>${xmlEsc(e.title)}</series_title>\n")
            sb.append("    <series_episodes>${e.totalEpisodes}</series_episodes>\n")
            sb.append("    <my_watched_episodes>${e.watchedEpisodes}</my_watched_episodes>\n")
            sb.append("    <my_start_date>$start</my_start_date>\n")
            sb.append("    <my_finish_date>$finish</my_finish_date>\n")
            sb.append("    <my_score>$score</my_score>\n")
            sb.append("    <my_status>$malStatus</my_status>\n")
            sb.append("    <my_times_watched>${e.rewatchCount}</my_times_watched>\n")
            sb.append("    <my_comments>${xmlEsc(e.notes)}</my_comments>\n")
            sb.append("    <my_tags>${xmlEsc(e.tags.joinToString(", "))}</my_tags>\n")
            sb.append("    <update_on_import>1</update_on_import>\n")
            sb.append("  </anime>\n")
        }
        sb.append("</myanimelist>\n")
        return sb.toString()
    }

    fun toCsv(entries: List<AnimeEntry>): String {
        val sb = StringBuilder()
        sb.append("id,title,status,watched_episodes,total_episodes,rating,rewatch_count,format,year,season,genres,tags,notes,mal_id,anilist_id,image_url\n")
        for (e in entries) {
            val ids = RemoteIds.parse(e.remoteIds)
            val row = listOf(
                e.id,
                e.title,
                e.status.name,
                e.watchedEpisodes.toString(),
                e.totalEpisodes.toString(),
                "%.1f".format(Locale.US, e.rating),
                e.rewatchCount.toString(),
                e.format,
                e.year?.toString().orEmpty(),
                e.season,
                e.genres.joinToString("|"),
                e.tags.joinToString("|"),
                e.notes,
                ids["mal"].orEmpty(),
                ids["anilist"].orEmpty(),
                e.imageUrl
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append('\n')
        }
        return sb.toString()
    }

    fun fromCsv(csv: String, now: Long = System.currentTimeMillis()): List<AnimeEntry> {
        val lines = parseCsvLines(csv)
        if (lines.size < 2) return emptyList()
        val header = lines.first().map { it.trim().lowercase() }
        fun col(row: List<String>, name: String, vararg aliases: String): String {
            val idx = (listOf(name) + aliases).map { header.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
            return if (idx in row.indices) row[idx].trim() else ""
        }
        val out = ArrayList<AnimeEntry>()
        for (i in 1 until lines.size) {
            val row = lines[i]
            val title = col(row, "title", "series_title", "anime_title")
            if (title.isBlank()) continue
            val malId = col(row, "mal_id", "malid", "series_animedb_id")
            val anilistId = col(row, "anilist_id", "anilistid")
            val rawId = col(row, "id").ifBlank {
                when {
                    anilistId.isNotBlank() -> "anilist:$anilistId"
                    malId.isNotBlank() -> "mal:$malId"
                    else -> "csv:${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
                }
            }
            val total = col(row, "total_episodes", "episodes", "series_episodes").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val watchedRaw = col(row, "watched_episodes", "progress", "my_watched_episodes").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val watched = if (total > 0) watchedRaw.coerceAtMost(total) else watchedRaw
            val statusRaw = col(row, "status", "my_status")
            val status = WatchStatus.values().firstOrNull { it.name.equals(statusRaw, true) || it.label.equals(statusRaw, true) }
                ?: com.anivault.data.library.MalParser.status(statusRaw)
            val rating = (col(row, "rating", "score", "my_score").toFloatOrNull() ?: 0f).coerceIn(0f, 10f)
            val rewatches = col(row, "rewatch_count", "rewatches", "my_times_watched").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val remote = buildMap {
                if (malId.isNotBlank()) put("mal", malId)
                if (anilistId.isNotBlank()) put("anilist", anilistId)
            }
            out += AnimeEntry(
                id = rawId,
                title = title,
                imageUrl = col(row, "image_url", "cover_url"),
                status = status,
                watchedEpisodes = watched,
                totalEpisodes = total,
                rating = rating,
                rewatchCount = rewatches,
                format = col(row, "format", "type"),
                year = col(row, "year").toIntOrNull(),
                season = col(row, "season"),
                genres = col(row, "genres").split('|', ';').map { it.trim() }.filter { it.isNotEmpty() },
                tags = col(row, "tags", "my_tags").split('|', ';').map { it.trim() }.filter { it.isNotEmpty() },
                notes = col(row, "notes", "comments", "my_comments"),
                remoteIds = RemoteIds.encode(remote),
                sourceName = "CSV import",
                updatedAt = now,
                addedAt = now
            )
        }
        return out
    }

    private fun csvCell(s: String): String {
        val clean = s.replace("\r\n", "\n").replace('\r', '\n')
        return if (clean.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else clean
    }

    private fun parseCsvLines(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var curRow = ArrayList<String>()
        val curCell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        curCell.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    curCell.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { curRow.add(curCell.toString()); curCell.setLength(0) }
                    '\n' -> {
                        curRow.add(curCell.toString()); curCell.setLength(0)
                        if (curRow.any { it.isNotBlank() }) rows.add(curRow)
                        curRow = ArrayList()
                    }
                    '\r' -> Unit
                    else -> curCell.append(c)
                }
            }
            i++
        }
        if (curCell.isNotEmpty() || curRow.isNotEmpty()) {
            curRow.add(curCell.toString())
            if (curRow.any { it.isNotBlank() }) rows.add(curRow)
        }
        return rows
    }

    private fun xmlEsc(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}

@Singleton
class ListExportImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val animeDao: AnimeDao,
    private val vaultDao: HVaultDao,
    private val jsonExport: JsonExportManager
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    fun exportMalXml(entries: List<AnimeEntry>): Boolean = runCatching {
        val fileName = "AniVault_MAL_${synchronized(dateFormat) { dateFormat.format(Date()) }}.xml"
        val xml = ListExportCodec.toMalXml(entries)
        DownloadsWriter.write(context, fileName, "application/xml") { out ->
            out.write(xml.toByteArray(Charsets.UTF_8))
        }
    }.isSuccess

    fun exportCsv(entries: List<AnimeEntry>): Boolean = runCatching {
        val fileName = "AniVault_Anime_${synchronized(dateFormat) { dateFormat.format(Date()) }}.csv"
        val csv = ListExportCodec.toCsv(entries)
        DownloadsWriter.write(context, fileName, "text/csv") { out ->
            out.write(csv.toByteArray(Charsets.UTF_8))
        }
    }.isSuccess

    suspend fun importCsvOrJson(uri: Uri): CsvJsonImportResult {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                ?.toString(Charsets.UTF_8)?.trim() ?: return CsvJsonImportResult(failed = true)
            if (text.isEmpty()) return CsvJsonImportResult(failed = true)

            val existingAnime = animeDao.getAll().map { it.id }.toHashSet()
            val existingVault = vaultDao.getAll().map { it.id }.toHashSet()

            if (text.startsWith("{")) {
                val imported = jsonExport.importFromUri(uri) ?: return CsvJsonImportResult(failed = true)
                val freshAnime = imported.anime.filter { it.id !in existingAnime }
                val freshVault = imported.hvault.filter { it.id !in existingVault }
                if (freshAnime.isNotEmpty()) animeDao.insertAllIgnore(freshAnime)
                if (freshVault.isNotEmpty()) vaultDao.insertAllIgnore(freshVault)
                val skipped = (imported.anime.size - freshAnime.size) + (imported.hvault.size - freshVault.size)
                CsvJsonImportResult(addedAnime = freshAnime.size, addedVault = freshVault.size, skipped = skipped)
            } else {
                val parsed = ListExportCodec.fromCsv(text)
                if (parsed.isEmpty()) return CsvJsonImportResult(failed = true)
                val fresh = parsed.filter { it.id !in existingAnime }
                if (fresh.isNotEmpty()) animeDao.insertAllIgnore(fresh)
                CsvJsonImportResult(addedAnime = fresh.size, skipped = parsed.size - fresh.size)
            }
        } catch (_: Exception) {
            CsvJsonImportResult(failed = true)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("Import file exceeds maximum allowed size")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 50 * 1024 * 1024
    }
}
