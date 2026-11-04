package com.anivault.ui.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a styled PDF using Android's built-in [PdfDocument] + Canvas API.
 * No WebView, no Activity context required — works entirely from ApplicationContext.
 * Output is saved to the public Downloads folder.
 */
@Singleton
class PdfExportManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dateStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    // Palette
    private val cBg      = Color.parseColor("#0f1419")
    private val cSurface = Color.parseColor("#1a1f2e")
    private val cTeal    = Color.parseColor("#14B8A6")
    private val cPink    = Color.parseColor("#F472B6")
    private val cText    = Color.parseColor("#e2e8f0")
    private val cSub     = Color.parseColor("#94a3b8")
    private val cDiv     = Color.parseColor("#2d3748")
    private val cWatch   = Color.parseColor("#3B82F6")
    private val cDone    = Color.parseColor("#22C55E")
    private val cPlan    = Color.parseColor("#A855F7")
    private val cDrop    = Color.parseColor("#EF4444")

    // Page geometry (A4 at 72 dpi)
    private val PW       = 595
    private val PH       = 842
    private val MARGIN   = 36f
    private val ROW_H    = 22f
    private val HEADER_H = 110f

    // ── Public API ─────────────────────────────────────────────────────────

    fun exportAnimeToPdf(entries: List<AnimeEntry>): Boolean {
        val name = "AniVault_Anime_${dateStamp.format(Date())}.pdf"
        return writePdf(name) { renderAnime(it, entries) }
    }

    fun exportHVaultToPdf(entries: List<HVaultEntry>): Boolean {
        val name = "AniVault_HVault_${dateStamp.format(Date())}.pdf"
        return writePdf(name) { renderHVault(it, entries) }
    }

    // ── PDF output ─────────────────────────────────────────────────────────

    private fun writePdf(name: String, block: (PdfDocument) -> Unit): Boolean {
        val doc = PdfDocument()
        return try {
            block(doc)
            DownloadsWriter.write(context, name, "application/pdf") { output ->
                doc.writeTo(output)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            doc.close()
        }
    }

    // ── Anime renderer ─────────────────────────────────────────────────────

    private fun renderAnime(doc: PdfDocument, entries: List<AnimeEntry>) {
        val accent    = cTeal
        val totalMins = entries.sumOf { it.watchedEpisodes.toLong() * it.durationMinutes }
        val avgRating = entries.filter { it.rating > 0 }.map { it.rating }.average()

        val cols  = floatArrayOf(MARGIN, 195f, 250f, 312f, 372f, 438f, PW - MARGIN)
        val heads = arrayOf("Title", "Eps", "Status", "Rating", "Genres", "Dur")

        val groups = listOf(
            "Watching"      to entries.filter { it.status == WatchStatus.WATCHING },
            "Completed"     to entries.filter { it.status == WatchStatus.COMPLETED },
            "On Hold"       to entries.filter { it.status == WatchStatus.ON_HOLD },
            "Plan to Watch" to entries.filter { it.status == WatchStatus.PLAN_TO_WATCH },
            "Dropped"       to entries.filter { it.status == WatchStatus.DROPPED }
        ).filter { it.second.isNotEmpty() }

        val ctx = PageCtx(doc, accent)
        drawBg(ctx.canvas)
        drawHeader(ctx.canvas, "AniVault", entries.size, totalMins,
            if (avgRating.isNaN()) 0.0 else avgRating, accent)

        for ((label, rows) in groups) {
            ctx.ensureSpace(34f)
            ctx.y = drawSectionHead(ctx.canvas, "$label  (${rows.size})", ctx.y, accent)
            ctx.y = drawColHeaders(ctx.canvas, heads, cols, ctx.y, accent)
            for (e in rows) {
                ctx.ensureSpace(ROW_H, cols, heads, accent)
                val cells = arrayOf(
                    e.title.truncate(26),
                    "${e.watchedEpisodes}/${e.totalEpisodes}",
                    e.status.label(),
                    if (e.rating > 0) "%.1f*".format(e.rating) else "--",
                    e.genres.take(2).joinToString(", ").truncate(18).ifBlank { "--" },
                    if (e.durationMinutes > 0) "${e.durationMinutes}m" else "--"
                )
                ctx.y = drawRow(ctx.canvas, cells, cols, ctx.y, statusColor(e.status))
            }
            ctx.y += 8f
        }
        doc.finishPage(ctx.page)
    }

    // ── H-Vault renderer ───────────────────────────────────────────────────

    private fun renderHVault(doc: PdfDocument, entries: List<HVaultEntry>) {
        val accent    = cPink
        val totalMins = entries.sumOf { it.watchedEpisodes.toLong() * it.durationMinutes }
        val avgRating = entries.filter { it.rating > 0 }.map { it.rating }.average()

        val cols  = floatArrayOf(MARGIN, 195f, 268f, 328f, 392f, 455f, PW - MARGIN)
        val heads = arrayOf("Title", "Studio", "Eps", "Status", "Rating", "Dur")

        val ctx = PageCtx(doc, accent)
        drawBg(ctx.canvas)
        drawHeader(ctx.canvas, "H-Vault", entries.size, totalMins,
            if (avgRating.isNaN()) 0.0 else avgRating, accent)

        ctx.y = drawSectionHead(ctx.canvas, "All Entries  (${entries.size})", ctx.y, accent)
        ctx.y = drawColHeaders(ctx.canvas, heads, cols, ctx.y, accent)
        for (e in entries) {
            ctx.ensureSpace(ROW_H, cols, heads, accent)
            val cells = arrayOf(
                e.title.truncate(26),
                (e.studio ?: "--").truncate(16),
                "${e.watchedEpisodes}/${e.totalEpisodes}",
                e.status.label(),
                if (e.rating > 0) "%.1f*".format(e.rating) else "--",
                if (e.durationMinutes > 0) "${e.durationMinutes}m" else "--"
            )
            ctx.y = drawRow(ctx.canvas, cells, cols, ctx.y, statusColor(e.status))
        }
        doc.finishPage(ctx.page)
    }

    // ── Mutable page context ───────────────────────────────────────────────

    private inner class PageCtx(val doc: PdfDocument, val accent: Int) {
        private var pageNum = 1
        var page   = startPage()
        var canvas = page.canvas
        var y      = HEADER_H + 8f

        private fun startPage(): PdfDocument.Page =
            doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNum++).create())

        fun ensureSpace(
            needed: Float,
            cols: FloatArray? = null,
            heads: Array<String>? = null,
            accent: Int = this.accent
        ) {
            if (y + needed > PH - MARGIN) {
                doc.finishPage(page)
                page   = startPage()
                canvas = page.canvas
                drawBg(canvas)
                y = MARGIN + 8f
                if (cols != null && heads != null) {
                    y = drawColHeaders(canvas, heads, cols, y, accent)
                }
            }
        }
    }

    // ── Canvas drawing helpers ─────────────────────────────────────────────

    private fun drawBg(c: Canvas) { c.drawColor(cBg) }

    private fun drawHeader(
        c: Canvas, title: String, total: Int,
        totalMins: Long, avgRating: Double, accent: Int
    ) {
        c.drawText(title, MARGIN, MARGIN + 24f, paint(accent, 24f, bold = true))
        c.drawText(
            "Exported on ${SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date())}",
            MARGIN, MARGIN + 38f, paint(cSub, 10f)
        )

        val stats = listOf(
            "Entries"    to total.toString(),
            "Watch Time" to "${totalMins / 60}h ${totalMins % 60}m",
            "Avg Rating" to if (avgRating > 0) "%.1f".format(avgRating) else "--"
        )
        var sx = MARGIN
        val boxP = Paint().apply { color = cSurface; style = Paint.Style.FILL }
        stats.forEach { (label, value) ->
            c.drawRoundRect(RectF(sx, MARGIN + 50f, sx + 88f, MARGIN + 86f), 6f, 6f, boxP)
            c.drawText(value, sx + 7f, MARGIN + 68f, paint(accent, 15f, bold = true))
            c.drawText(label, sx + 7f, MARGIN + 80f, paint(cSub, 8f))
            sx += 98f
        }
        c.drawLine(MARGIN, HEADER_H, (PW - MARGIN).toFloat(), HEADER_H,
            Paint().apply { color = cDiv; strokeWidth = 1f })
    }

    private fun drawSectionHead(c: Canvas, text: String, y: Float, accent: Int): Float {
        c.drawText(text, MARGIN, y + 14f, paint(accent, 12f, bold = true))
        c.drawLine(MARGIN, y + 18f, (PW - MARGIN).toFloat(), y + 18f,
            Paint().apply { color = cDiv; strokeWidth = 0.8f })
        return y + 24f
    }

    private fun drawColHeaders(
        c: Canvas, headers: Array<String>, cols: FloatArray, y: Float, accent: Int
    ): Float {
        c.drawRect(MARGIN, y, (PW - MARGIN).toFloat(), y + ROW_H,
            Paint().apply { color = cSurface; style = Paint.Style.FILL })
        headers.forEachIndexed { i, h ->
            c.drawText(h, cols[i] + 3f, y + 14f, paint(accent, 9f, bold = true))
        }
        return y + ROW_H + 1f
    }

    private fun drawRow(
        c: Canvas, cells: Array<String>, cols: FloatArray, y: Float, badgeColor: Int
    ): Float {
        // Badge for status cell (index 2)
        c.drawRoundRect(
            RectF(cols[2] + 1f, y + 3f, cols[2] + 57f, y + ROW_H - 3f),
            3f, 3f,
            Paint().apply { color = badgeColor; style = Paint.Style.FILL }
        )
        cells.forEachIndexed { i, cell ->
            val p = if (i == 2) paint(Color.WHITE, 8f) else paint(cText, 9f)
            c.drawText(cell, cols[i] + 3f, y + 14f, p)
        }
        c.drawLine(MARGIN, y + ROW_H, (PW - MARGIN).toFloat(), y + ROW_H,
            Paint().apply { color = cDiv; strokeWidth = 0.5f })
        return y + ROW_H
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private fun paint(color: Int, size: Float, bold: Boolean = false) =
        Paint().apply { this.color = color; textSize = size; isFakeBoldText = bold; isAntiAlias = true }

    private fun String.truncate(max: Int) = if (length > max) take(max - 1) + "+" else this

    private fun WatchStatus.label() = name.replace('_', ' ')

    private fun statusColor(s: WatchStatus) = when (s) {
        WatchStatus.WATCHING      -> cWatch
        WatchStatus.COMPLETED     -> cDone
        WatchStatus.ON_HOLD       -> cPlan
        WatchStatus.PLAN_TO_WATCH -> cPlan
        WatchStatus.DROPPED       -> cDrop
    }
}
