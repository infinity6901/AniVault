package com.anivault.ui.export

import android.content.Context
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates a .docx file (Office Open XML) using raw ZIP + XML.
 * No third-party library required.
 *
 * File layout:
 *   [Content_Types].xml
 *   _rels/.rels
 *   word/document.xml
 *   word/_rels/document.xml.rels
 */
@Singleton
class DocxExportManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    fun exportAnimeToDocs(entries: List<AnimeEntry>): Boolean = runCatching {
        val fileName = "AniVault_Anime_${dateFormat.format(Date())}.docx"
        val xml      = buildAnimeDocumentXml(entries)
        writeDocx(xml, fileName)
    }.isSuccess

    fun exportHVaultToDocs(entries: List<HVaultEntry>): Boolean = runCatching {
        val fileName = "AniVault_HVault_${dateFormat.format(Date())}.docx"
        val xml      = buildHVaultDocumentXml(entries)
        writeDocx(xml, fileName)
    }.isSuccess

    // ── Write ──────────────────────────────────────────────────────────────

    private fun writeDocx(documentXml: String, fileName: String) {
        DownloadsWriter.write(
            context,
            fileName,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ) { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write(contentTypesXml().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("_rels/.rels"))
                zip.write(relsXml().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                zip.write(documentRelsXml().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    // ── XML templates ──────────────────────────────────────────────────────

    private fun contentTypesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml"  ContentType="application/xml"/>
  <Override PartName="/word/document.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private fun relsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    Target="word/document.xml"/>
</Relationships>"""

    private fun documentRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""

    // ── Anime document ─────────────────────────────────────────────────────

    private fun buildAnimeDocumentXml(entries: List<AnimeEntry>): String {
        val watching  = entries.filter { it.status == WatchStatus.WATCHING }
        val completed = entries.filter { it.status == WatchStatus.COMPLETED }
        val plan      = entries.filter { it.status == WatchStatus.PLAN_TO_WATCH }
        val dropped   = entries.filter { it.status == WatchStatus.DROPPED }
        val totalMins = entries.sumOf { it.watchedEpisodes.toLong() * it.durationMinutes }
        val avgRating = entries.filter { it.rating > 0 }.map { it.rating }.average()

        val paragraphs = buildString {
            appendPara("AniVault – Anime List", heading = true, size = 32)
            appendPara("Exported on ${SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date())}", italic = true)
            appendPara("")
            appendPara("Summary", heading = true)
            appendPara("Total Entries: ${entries.size}   |   Watch Time: ${totalMins / 60}h   |   Avg Rating: ${if (avgRating.isNaN()) "—" else "%.1f".format(avgRating)}")
            appendPara("")

            for ((label, list) in listOf("Watching" to watching, "Completed" to completed, "Plan to Watch" to plan, "Dropped" to dropped)) {
                if (list.isEmpty()) continue
                appendPara("$label (${list.size})", heading = true)
                for (e in list) {
                    val stars = if (e.rating > 0) "★ %.1f".format(e.rating) else "—"
                    appendPara("• ${e.title}   [${e.watchedEpisodes}/${e.totalEpisodes} eps]   $stars   ${e.genres.take(3).joinToString(", ")}")
                }
                appendPara("")
            }
        }
        return wrapInDocument(paragraphs)
    }

    // ── HVault document ────────────────────────────────────────────────────

    private fun buildHVaultDocumentXml(entries: List<HVaultEntry>): String {
        val totalMins = entries.sumOf { it.watchedEpisodes.toLong() * it.durationMinutes }
        val avgRating = entries.filter { it.rating > 0 }.map { it.rating }.average()

        val paragraphs = buildString {
            appendPara("H-Vault Export", heading = true, size = 32)
            appendPara("Exported on ${SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date())}", italic = true)
            appendPara("")
            appendPara("Summary", heading = true)
            appendPara("Total Entries: ${entries.size}   |   Watch Time: ${totalMins / 60}h   |   Avg Rating: ${if (avgRating.isNaN()) "—" else "%.1f".format(avgRating)}")
            appendPara("")
            appendPara("All Entries", heading = true)
            for (e in entries) {
                val stars  = if (e.rating > 0) "★ %.1f".format(e.rating) else "—"
                val studio = e.studio ?: "—"
                appendPara("• ${e.title}   [$studio]   [${e.watchedEpisodes}/${e.totalEpisodes} eps]   $stars   ${e.status.name.replace('_', ' ')}")
            }
        }
        return wrapInDocument(paragraphs)
    }

    // ── XML helpers ────────────────────────────────────────────────────────

    private fun StringBuilder.appendPara(
        text: String,
        heading: Boolean = false,
        size: Int = 22,       // half-points: 22 = 11pt
        italic: Boolean = false
    ) {
        val safeText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val sizeAttr = if (heading) (size).toString() else size.toString()
        val boldAttr = if (heading) "<w:b/>" else ""
        val italAttr = if (italic)  "<w:i/>" else ""
        val spaceAfter = if (heading) "<w:spacing w:after=\"120\"/>" else ""
        append("""
<w:p>
  <w:pPr>$spaceAfter</w:pPr>
  <w:r>
    <w:rPr>$boldAttr$italAttr<w:sz w:val="$sizeAttr"/></w:rPr>
    <w:t xml:space="preserve">$safeText</w:t>
  </w:r>
</w:p>""")
    }

    private fun wrapInDocument(body: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
  xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:body>
$body
    <w:sectPr/>
  </w:body>
</w:document>"""
}
