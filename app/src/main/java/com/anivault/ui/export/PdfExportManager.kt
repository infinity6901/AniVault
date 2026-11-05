package com.anivault.ui.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Styled PDF export drawn with Android's [PdfDocument]. Each title gets its cover, full (wrapped)
 * title, progress, status, rating and watch time. Works from the application context.
 */
@Singleton
class PdfExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val covers: CoverLoader
) {
    private val dateStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    private val cBg = Color.parseColor("#0f1419")
    private val cSurface = Color.parseColor("#1a1f2e")
    private val cTeal = Color.parseColor("#14B8A6")
    private val cPink = Color.parseColor("#F472B6")
    private val cText = Color.parseColor("#e2e8f0")
    private val cSub = Color.parseColor("#94a3b8")
    private val cDiv = Color.parseColor("#2d3748")

    private val PW = 595
    private val PH = 842
    private val M = 36f
    private val ROW_H = 54f
    private val COVER_W = 30f
    private val COVER_H = 42f

    // Column x positions (title block, progress, status badge, rating, time)
    private val xTitle = M + COVER_W + 10f          // 76
    private val wTitle = 196f
    private val xProg = xTitle + wTitle + 8f        // 280
    private val xStatus = xProg + 50f               // 330
    private val wStatus = 84f
    private val xRating = xStatus + wStatus + 10f   // 424
    private val xTime = xRating + 44f               // 468

    fun exportAnimeToPdf(entries: List<AnimeEntry>): Boolean =
        write("AniVault_Anime_${dateStamp.format(Date())}.pdf", "AniVault", cTeal, ExportData.anime(entries))

    fun exportHVaultToPdf(entries: List<HVaultEntry>): Boolean =
        write("AniVault_HVault_${dateStamp.format(Date())}.pdf", "H-Vault", cPink, ExportData.vault(entries))

    private fun write(name: String, heading: String, accent: Int, rows: List<ExportRow>): Boolean {
        val doc = PdfDocument()
        return try {
            val images = covers.load(rows.map { it.coverUrl })
            render(doc, heading, accent, rows, images)
            DownloadsWriter.write(context, name, "application/pdf") { doc.writeTo(it) }
            true
        } catch (e: Exception) {
            false
        } finally {
            doc.close()
        }
    }

    private inner class Pages(val doc: PdfDocument, val accent: Int) {
        private var n = 1
        var page: PdfDocument.Page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, n++).create())
        var canvas: Canvas = page.canvas
        var y = M
        fun newPage() {
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, n++).create())
            canvas = page.canvas
            canvas.drawColor(cBg)
            y = M
        }
        fun ensure(h: Float) { if (y + h > PH - M) newPage() }
    }

    private fun render(doc: PdfDocument, heading: String, accent: Int, rows: List<ExportRow>, images: Map<String, ByteArray>) {
        val p = Pages(doc, accent)
        p.canvas.drawColor(cBg)
        drawHeader(p, heading, ExportData.stats(rows))
        val bitmaps = HashMap<String, Bitmap?>()
        for (status in ExportData.ORDER) {
            val group = rows.filter { it.status == status }
            if (group.isEmpty()) continue
            p.ensure(30f + ROW_H)
            drawSection(p, "${ExportData.statusLabel(status)}  (${group.size})")
            for (r in group) {
                if (p.y + ROW_H > PH - M) { p.newPage(); drawColumnHeads(p) }
                val bmp = bitmaps.getOrPut(r.coverUrl) { images[r.coverUrl]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                drawRow(p, r, bmp)
            }
            p.y += 10f
        }
        doc.finishPage(p.page)
    }

    private fun drawHeader(p: Pages, heading: String, s: ExportStats) {
        val c = p.canvas
        c.drawText(heading, M, p.y + 24f, paint(p.accent, 26f, bold = true))
        c.drawText("Exported on ${SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date())}", M, p.y + 40f, paint(cSub, 10f))
        val boxes = listOf(
            "Entries" to s.entries.toString(),
            "Episodes watched" to s.episodes.toString(),
            (if (s.estimated) "Watch time (est.)" else "Watch time") to ExportData.duration(s.minutes),
            "Avg rating" to (s.avgRating?.let { "%.1f / 10".format(it) } ?: "Not rated")
        )
        val gap = 8f
        val w = (PW - 2 * M - gap * 3) / 4f
        var x = M
        val top = p.y + 54f
        val fill = Paint().apply { color = cSurface; style = Paint.Style.FILL }
        boxes.forEach { (label, value) ->
            c.drawRoundRect(RectF(x, top, x + w, top + 40f), 6f, 6f, fill)
            c.drawText(value, x + 8f, top + 19f, paint(p.accent, 14f, bold = true))
            c.drawText(label, x + 8f, top + 33f, paint(cSub, 8f))
            x += w + gap
        }
        p.y = top + 40f + 18f
    }

    private fun drawSection(p: Pages, text: String) {
        p.canvas.drawText(text, M, p.y + 12f, paint(p.accent, 13f, bold = true))
        p.y += 20f
        drawColumnHeads(p)
    }

    private fun drawColumnHeads(p: Pages) {
        val c = p.canvas
        c.drawRect(M, p.y, PW - M, p.y + 18f, Paint().apply { color = cSurface })
        val h = paint(p.accent, 8.5f, bold = true)
        c.drawText("TITLE", xTitle, p.y + 12f, h)
        c.drawText("EPISODES", xProg, p.y + 12f, h)
        c.drawText("STATUS", xStatus, p.y + 12f, h)
        c.drawText("RATING", xRating, p.y + 12f, h)
        c.drawText("TIME", xTime, p.y + 12f, h)
        p.y += 20f
    }

    private fun drawRow(p: Pages, r: ExportRow, cover: Bitmap?) {
        val c = p.canvas
        val top = p.y
        // Cover (centre-cropped into a rounded rect)
        val dst = RectF(M, top + 5f, M + COVER_W, top + 5f + COVER_H)
        if (cover != null) {
            val save = c.save()
            val clip = Path().apply { addRoundRect(dst, 3f, 3f, Path.Direction.CW) }
            c.clipPath(clip)
            val srcRatio = cover.width.toFloat() / cover.height
            val dstRatio = COVER_W / COVER_H
            val src = if (srcRatio > dstRatio) {
                val w = (cover.height * dstRatio).toInt(); Rect((cover.width - w) / 2, 0, (cover.width + w) / 2, cover.height)
            } else {
                val h = (cover.width / dstRatio).toInt(); Rect(0, 0, cover.width, h.coerceAtMost(cover.height))
            }
            c.drawBitmap(cover, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restoreToCount(save)
        } else {
            c.drawRoundRect(dst, 3f, 3f, Paint().apply { color = cSurface })
        }
        // Title: wraps to two lines with the full text, ellipsised only if longer than that
        val tp = TextPaint(paint(cText, 9.5f, bold = true))
        val layout = StaticLayout.Builder.obtain(r.title, 0, r.title.length, tp, wTitle.toInt())
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(xTitle, top + 6f); layout.draw(c); c.restore()
        if (r.detail.isNotBlank()) {
            val line = TextUtils.ellipsize(r.detail, TextPaint(paint(cSub, 8f)), wTitle, TextUtils.TruncateAt.END).toString()
            c.drawText(line, xTitle, top + 6f + layout.height + 10f, paint(cSub, 8f))
        }
        val mid = top + ROW_H / 2f + 3f
        c.drawText(r.progress, xProg, mid, paint(cText, 9.5f))
        // Status badge sized to its text so long labels such as "PLAN TO WATCH" never spill out
        val label = ExportData.statusLabel(r.status).uppercase()
        val bp = paint(Color.WHITE, 7.5f, bold = true)
        val bw = (bp.measureText(label) + 12f).coerceAtMost(wStatus)
        c.drawRoundRect(RectF(xStatus, mid - 11f, xStatus + bw, mid + 4f), 4f, 4f, Paint().apply { color = statusColor(r.status); isAntiAlias = true })
        c.drawText(label, xStatus + 6f, mid - 0.5f, bp)
        c.drawText(if (r.rating > 0) "%.1f / 10".format(r.rating) else "Not rated", xRating, mid, paint(if (r.rating > 0) cText else cSub, 9f))
        val t = if (r.watched > 0) (if (r.estimated) "~" else "") + ExportData.duration(r.minutes) else "0m"
        c.drawText(t, xTime, mid, paint(cText, 9.5f))
        c.drawLine(M, top + ROW_H, PW - M, top + ROW_H, Paint().apply { color = cDiv; strokeWidth = 0.5f })
        p.y += ROW_H
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false) =
        Paint().apply { this.color = color; textSize = size; isFakeBoldText = bold; isAntiAlias = true }

    private fun statusColor(s: WatchStatus) = when (s) {
        WatchStatus.WATCHING -> Color.parseColor("#3B82F6")
        WatchStatus.COMPLETED -> Color.parseColor("#22C55E")
        WatchStatus.ON_HOLD -> Color.parseColor("#F59E0B")
        WatchStatus.PLAN_TO_WATCH -> Color.parseColor("#A855F7")
        WatchStatus.DROPPED -> Color.parseColor("#EF4444")
    }
}
