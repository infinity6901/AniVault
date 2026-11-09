package com.anivault.ui.export

import android.content.Context
import android.net.Uri
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles JSON export and import of vault entries.
 *
 * Export: serialise the whole vault to a pretty-printed JSON file in Downloads.
 * Import: read a previously exported JSON file from a Uri and return the parsed list.
 */
@Singleton
class JsonExportManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    // ── Wrapper for round-trip import ──────────────────────────────────────

    private data class VaultExport(
        val version: Int    = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val anime: List<AnimeEntry>?   = emptyList(),
        val hvault: List<HVaultEntry>? = emptyList()
    )

    // ── Export ─────────────────────────────────────────────────────────────

    fun exportAnimeJson(entries: List<AnimeEntry>): Boolean = runCatching {
        val fileName = "AniVault_Anime_${synchronized(dateFormat) { dateFormat.format(Date()) }}.json"
        val json     = gson.toJson(VaultExport(anime = entries))
        writeJson(json, fileName)
    }.isSuccess

    fun exportHVaultJson(entries: List<HVaultEntry>): Boolean = runCatching {
        val fileName = "AniVault_HVault_${synchronized(dateFormat) { dateFormat.format(Date()) }}.json"
        val json     = gson.toJson(VaultExport(hvault = entries))
        writeJson(json, fileName)
    }.isSuccess

    /** Export both vaults in one file (preferred for full backup). */
    fun exportAll(anime: List<AnimeEntry>, hvault: List<HVaultEntry>): Boolean = runCatching {
        val fileName = "AniVault_Backup_${synchronized(dateFormat) { dateFormat.format(Date()) }}.json"
        val json     = gson.toJson(VaultExport(anime = anime, hvault = hvault))
        writeJson(json, fileName)
    }.isSuccess

    // ── Import ─────────────────────────────────────────────────────────────

    data class ImportResult(
        val anime:  List<AnimeEntry>   = emptyList(),
        val hvault: List<HVaultEntry>  = emptyList()
    )

    fun importFromUri(uri: Uri): ImportResult? = runCatching {
        val json = context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
            ?.toString(Charsets.UTF_8) ?: return null
        if (json.isBlank()) return null

        val export = gson.fromJson<VaultExport>(json, VaultExport::class.java) ?: return null
        ImportResult(
            anime = export.anime.orEmpty().filter { it.id.isNotBlank() && it.title.isNotBlank() },
            hvault = export.hvault.orEmpty().filter { it.id.isNotBlank() && it.title.isNotBlank() }
        )
    }.getOrNull()

    // ── Util ───────────────────────────────────────────────────────────────

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_JSON_BYTES) throw IOException("JSON file exceeds maximum size")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeJson(json: String, fileName: String) {
        DownloadsWriter.write(context, fileName, "application/json") { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 50 * 1024 * 1024
    }
}
