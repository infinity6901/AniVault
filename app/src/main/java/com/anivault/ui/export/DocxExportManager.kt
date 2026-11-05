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
 * Creates a .docx (Office Open XML) with raw ZIP + XML, no third-party library. Each title is a table
 * row with its cover image, full title, progress, status, rating and watch time.
 */
@Singleton
class DocxExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val covers: CoverLoader
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    fun exportAnimeToDocs(entries: List<AnimeEntry>): Boolean = runCatching {
        write("AniVault_Anime_${dateFormat.format(Date())}.docx", "AniVault – Anime List", "14B8A6", ExportData.anime(entries))
    }.isSuccess

    fun exportHVaultToDocs(entries: List<HVaultEntry>): Boolean = runCatching {
        write("AniVault_HVault_${dateFormat.format(Date())}.docx", "H-Vault Export", "DB2777", ExportData.vault(entries))
    }.isSuccess

    private class Media(val rId: String, val file: String, val bytes: ByteArray)

    private fun write(fileName: String, title: String, accent: String, rows: List<ExportRow>) {
        val images = covers.load(rows.map { it.coverUrl })
        val media = LinkedHashMap<String, Media>()
        images.entries.forEachIndexed { i, (url, bytes) -> media[url] = Media("rIdImg${i + 1}", "image${i + 1}.jpg", bytes) }
        val xml = buildDocument(title, accent, rows, media)
        DownloadsWriter.write(context, fileName, "application/vnd.openxmlformats-officedocument.wordprocessingml.document") { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                put("[Content_Types].xml", CONTENT_TYPES.toByteArray())
                put("_rels/.rels", RELS.toByteArray())
                put("word/_rels/document.xml.rels", documentRels(media.values).toByteArray())
                put("word/document.xml", xml.toByteArray())
                media.values.forEach { put("word/media/${it.file}", it.bytes) }
            }
        }
    }

    private fun buildDocument(title: String, accent: String, rows: List<ExportRow>, media: Map<String, Media>): String {
        val s = ExportData.stats(rows)
        var drawingId = 1
        val body = StringBuilder()
        body.append(para(title, size = 40, bold = true, color = accent))
        body.append(para("Exported on ${SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date())}", size = 20, color = "64748B", italic = true))
        body.append(para(
            "${s.entries} entries   |   ${s.episodes} episodes watched   |   Watch time: ${if (s.estimated) "~" else ""}${ExportData.duration(s.minutes)}   |   Avg rating: ${s.avgRating?.let { "%.1f / 10".format(it) } ?: "not rated"}",
            size = 22, spaceAfter = 200
        ))
        for (status in ExportData.ORDER) {
            val group = rows.filter { it.status == status }
            if (group.isEmpty()) continue
            body.append(para("${ExportData.statusLabel(status)} (${group.size})", size = 28, bold = true, color = accent, spaceAfter = 80))
            body.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
            for (side in listOf("top", "bottom", "insideH")) body.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CBD5E1\"/>")
            body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr>")
            body.append("<w:tblGrid>${WIDTHS.joinToString("") { "<w:gridCol w:w=\"$it\"/>" }}</w:tblGrid>")
            body.append(headerRow(accent))
            for (r in group) {
                val m = media[r.coverUrl]
                val cover = if (m != null) drawing(m, drawingId++) else ""
                body.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
                body.append(cell(WIDTHS[0], "<w:p><w:r>$cover</w:r></w:p>"))
                body.append(cell(WIDTHS[1], paraRaw(esc(r.title), size = 21, bold = true) + (if (r.detail.isNotBlank()) paraRaw(esc(r.detail), size = 16, color = "64748B") else "") +
                    (if (r.synopsis.isNotBlank()) paraRaw(esc(r.synopsis.take(420) + if (r.synopsis.length > 420) "…" else ""), size = 17, italic = true, color = "475569", font = "Georgia", spaceAfter = 40) else "")))
                body.append(cell(WIDTHS[2], paraRaw(r.progress, size = 20)))
                body.append(cell(WIDTHS[3], paraRaw(esc(ExportData.statusLabel(r.status)), size = 18, bold = true, color = statusColor(r.status))))
                body.append(cell(WIDTHS[4], paraRaw(if (r.rating > 0) "%.1f / 10".format(r.rating) else "Not rated", size = 20)))
                body.append(cell(WIDTHS[5], paraRaw((if (r.estimated && r.watched > 0) "~" else "") + ExportData.duration(r.minutes), size = 20)))
                body.append("</w:tr>")
            }
            body.append("</w:tbl>")
            body.append(para("", size = 12))
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
  xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
  <w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="900" w:right="900" w:bottom="900" w:left="900" w:header="400" w:footer="400" w:gutter="0"/></w:sectPr></w:body>
</w:document>"""
    }

    private fun headerRow(accent: String): String =
        "<w:tr><w:trPr><w:tblHeader/></w:trPr>" + listOf("Cover", "Title", "Episodes", "Status", "Rating", "Time").mapIndexed { i, h ->
            cell(WIDTHS[i], paraRaw(h, size = 17, bold = true, color = accent))
        }.joinToString("") + "</w:tr>"

    private fun cell(width: Int, content: String) =
        "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/></w:tcPr>$content</w:tc>"

    private fun drawing(m: Media, id: Int): String {
        val cx = 822960L // 0.9 in
        val cy = 1170000L
        return """<w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Cover $id"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="${m.file}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="${m.rId}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"""
    }

    private fun para(text: String, size: Int = 22, bold: Boolean = false, italic: Boolean = false, color: String? = null, spaceAfter: Int = 60) =
        paraRaw(esc(text), size, bold, italic, color, spaceAfter)

    private fun paraRaw(escaped: String, size: Int = 22, bold: Boolean = false, italic: Boolean = false, color: String? = null, spaceAfter: Int = 20, font: String? = null) =
        "<w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"$spaceAfter\"/></w:pPr><w:r><w:rPr>" +
            (if (font != null) "<w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\"/>" else "") +
            (if (bold) "<w:b/>" else "") + (if (italic) "<w:i/>" else "") +
            (if (color != null) "<w:color w:val=\"$color\"/>" else "") +
            "<w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">$escaped</w:t></w:r></w:p>"

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it >= ' ' }

    private fun statusColor(s: WatchStatus) = when (s) {
        WatchStatus.WATCHING -> "2563EB"
        WatchStatus.COMPLETED -> "16A34A"
        WatchStatus.ON_HOLD -> "D97706"
        WatchStatus.PLAN_TO_WATCH -> "9333EA"
        WatchStatus.DROPPED -> "DC2626"
    }

    private fun documentRels(media: Collection<Media>) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${media.joinToString("\n") { "<Relationship Id=\"${it.rId}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${it.file}\"/>" }}
</Relationships>"""

    private companion object {
        // Cover, Title, Episodes, Status, Rating, Time (twentieths of a point; usable width ≈ 10100)
        val WIDTHS = intArrayOf(1450, 4300, 1000, 1300, 1100, 950)

        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="jpg" ContentType="image/jpeg"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
    }
}
