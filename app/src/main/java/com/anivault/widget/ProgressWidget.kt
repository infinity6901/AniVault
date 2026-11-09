package com.anivault.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import com.anivault.MainActivity
import com.anivault.R
import com.anivault.core.AppPrefs
import com.anivault.data.library.ProgressRules
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.WatchHistoryDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.WatchHistoryEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.ui.common.CoverPalette
import com.anivault.ui.theme.LiquidGlassTint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun animeDao(): AnimeDao
    fun historyDao(): WatchHistoryDao
    fun prefs(): AppPrefs
    fun airing(): com.anivault.data.provider.AiringRepository
}

/** Pure rules so the widget logic is testable without Android. */
object WidgetRules {
    /** Titles worth showing: currently watching, not finished, most recently touched first. */
    fun candidates(all: List<AnimeEntry>): List<AnimeEntry> =
        all.filter { it.status == WatchStatus.WATCHING && (it.totalEpisodes == 0 || it.watchedEpisodes < it.totalEpisodes) }
            .sortedByDescending { it.updatedAt }

    fun wrap(index: Int, size: Int): Int = if (size <= 0) 0 else ((index % size) + size) % size

    /**
     * Resolves the entry for a "+1" tap: prefers the entry whose ID was actually rendered on the widget
     * so finishing a series doesn't cause a rapid second tap to increment a different series.
     */
    fun resolveTarget(candidates: List<AnimeEntry>, index: Int, renderedId: String?): AnimeEntry? {
        if (candidates.isEmpty()) return null
        if (!renderedId.isNullOrBlank()) {
            return candidates.firstOrNull { it.id == renderedId }
        }
        return candidates.getOrNull(wrap(index, candidates.size))
    }
}

/**
 * Dynamically renders a high-contrast 3D Liquid Glass surface bitmap for RemoteViews homescreen widgets.
 * Uses a deep frosted glass base so white text stays crisp even over bright white wallpapers,
 * while boosting cover-extracted accents to high-luminance pastel/neon tones so labels never look muddy.
 */
object LiquidGlassWidgetRenderer {

    private fun withAlpha(argb: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    /** Boosts an ARGB colour to high luminance (V >= 0.90) and balanced saturation so header labels & rims are always bright and legible. */
    internal fun ensureLuminousAccent(argb: Int): Int {
        val hsv = FloatArray(3)
        CoverPalette.rgbToHsv(argb, hsv)
        val s = hsv[1].coerceIn(0.42f, 0.76f)
        val v = hsv[2].coerceIn(0.90f, 0.98f)
        return CoverPalette.hsvToArgb(hsv[0], s, v)
    }

    fun applyToRemoteViews(
        ctx: Context,
        views: RemoteViews,
        prefs: AppPrefs,
        coverBitmap: Bitmap? = null,
        widthDp: Int = 320,
        heightDp: Int = 150
    ): Int {
        val tint = LiquidGlassTint.parse(prefs.glassTint)
        val rawCoverAccent = if (prefs.widgetGlassMatchCover && coverBitmap != null) {
            extractFromBitmap(coverBitmap)
        } else null
        val accentArgb = rawCoverAccent?.let { ensureLuminousAccent(it) } ?: tint.primary.toArgb()
        views.setTextColor(R.id.widget_label, accentArgb)

        if (!prefs.widgetGlassEnabled) {
            views.setImageViewBitmap(R.id.widget_glass_bg, null)
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
            return accentArgb
        }

        views.setInt(R.id.widget_root, "setBackgroundResource", android.R.color.transparent)
        val bmp = renderSurfaceBitmap(ctx, prefs, tint, accentArgb, widthDp, heightDp)
        views.setImageViewBitmap(R.id.widget_glass_bg, bmp)
        return accentArgb
    }

    private fun extractFromBitmap(bmp: Bitmap): Int? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        CoverPalette.extractFromArgb(pixels)?.toArgb()
    }.getOrNull()

    fun renderSurfaceBitmap(
        ctx: Context,
        prefs: AppPrefs,
        tint: LiquidGlassTint,
        accentArgb: Int,
        widthDp: Int,
        heightDp: Int
    ): Bitmap {
        val density = ctx.resources.displayMetrics.density.coerceIn(1f, 3f)
        val w = (widthDp * density).toInt().coerceAtLeast(160)
        val h = (heightDp * density).toInt().coerceAtLeast(90)
        val radius = 22f * density
        val stroke = 1.5f * density

        // Map user slider (15..85) to a deep frosted glass floor (0.72..0.94) so white text is always legible over light wallpapers.
        val userSlider = (prefs.widgetGlassOpacity.coerceIn(15, 85) - 15f) / 70f
        val baseAlpha = (0.72f + userSlider * 0.22f).coerceIn(0.72f, 0.94f)
        val spec = (prefs.glassSpecular.coerceIn(0, 100) / 100f)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)

        // 1. Deep frosted dark glass base (guarantees crisp white-text contrast over light & dark wallpapers)
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    withAlpha(tint.baseStart.toArgb(), baseAlpha),
                    withAlpha(tint.baseEnd.toArgb(), (baseAlpha - 0.04f).coerceIn(0.68f, 0.92f))
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, basePaint)

        // 2. Primary glowing liquid orb (top-right corner, subtle so it never washes out text)
        val orb1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.85f, h * 0.18f, w * 0.58f,
                intArrayOf(withAlpha(accentArgb, 0.26f), 0x00000000),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, orb1Paint)

        // 3. Secondary glowing liquid orb (bottom-left corner)
        val orb2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.18f, h * 0.88f, w * 0.52f,
                intArrayOf(withAlpha(tint.orb2.toArgb(), 0.22f), 0x00000000),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, orb2Paint)

        // 4. Diagonal refractive glass reflection sheen
        if (spec > 0.02f) {
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w * 0.85f, h.toFloat(),
                    intArrayOf(
                        withAlpha(0xFFFFFFFF.toInt(), 0.16f * spec),
                        withAlpha(0xFFFFFFFF.toInt(), 0.04f * spec),
                        0x00000000,
                        withAlpha(accentArgb, 0.08f * spec)
                    ),
                    floatArrayOf(0f, 0.30f, 0.68f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        }

        // 5. Inner top-edge caustic highlight line
        if (spec > 0.05f) {
            val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.25f * density
                shader = LinearGradient(
                    radius, 0f, w - radius, 0f,
                    intArrayOf(
                        0x00FFFFFF,
                        withAlpha(0xFFFFFFFF.toInt(), 0.65f * spec),
                        withAlpha(accentArgb, 0.50f * spec),
                        0x00FFFFFF
                    ),
                    floatArrayOf(0f, 0.35f, 0.70f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawLine(radius * 0.75f, stroke * 1.2f, w - radius * 0.75f, stroke * 1.2f, causticPaint)
        }

        // 6. Directional 3D specular rim border
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    withAlpha(0xFFFFFFFF.toInt(), (0.82f * spec).coerceIn(0.20f, 0.92f)),
                    withAlpha(accentArgb, (0.58f * spec).coerceIn(0.14f, 0.74f)),
                    withAlpha(0xFFFFFFFF.toInt(), (0.18f * spec).coerceIn(0.08f, 0.32f)),
                    withAlpha(tint.secondary.toArgb(), (0.50f * spec).coerceIn(0.12f, 0.64f))
                ),
                floatArrayOf(0f, 0.35f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, rimPaint)

        return bmp
    }
}

/** Home-screen widget: shows your primary watching title plus secondary watchlist rows, each with +1 buttons. */
class ProgressWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, goAsync())

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLUS, ACTION_PLUS_SUB_1, ACTION_PLUS_SUB_2, ACTION_NEXT -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
                        val list = WidgetRules.candidates(ep.animeDao().getAll())
                        val sp = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
                        val i = WidgetRules.wrap(sp.getInt(KEY_INDEX, 0), list.size)
                        when (intent.action) {
                            ACTION_PLUS, ACTION_PLUS_SUB_1, ACTION_PLUS_SUB_2 -> {
                                val targetKey = when (intent.action) {
                                    ACTION_PLUS_SUB_1 -> KEY_SUB_ID_1
                                    ACTION_PLUS_SUB_2 -> KEY_SUB_ID_2
                                    else -> KEY_CURRENT_ID
                                }
                                val renderedId = sp.getString(targetKey, null)
                                WidgetRules.resolveTarget(list, i, renderedId)?.let { e ->
                                    val now = System.currentTimeMillis()
                                    val updated = ProgressRules.apply(e, e.watchedEpisodes + 1, ep.prefs().autoCompleteOnLast, now).copy(pendingSync = true, updatedAt = now)
                                    ep.animeDao().upsert(updated)
                                    if (updated.watchedEpisodes > e.watchedEpisodes) {
                                        ep.historyDao().insert(
                                            WatchHistoryEntry(
                                                entryId = updated.id,
                                                title = updated.title,
                                                imageUrl = updated.imageUrl,
                                                episode = updated.watchedEpisodes,
                                                rewatchCount = updated.rewatchCount,
                                                watchedAt = now
                                            )
                                        )
                                    }
                                }
                            }
                            ACTION_NEXT -> {
                                val nextIdx = WidgetRules.wrap(i + 1, list.size)
                                sp.edit().putInt(KEY_INDEX, nextIdx).putString(KEY_CURRENT_ID, list.getOrNull(nextIdx)?.id).apply()
                            }
                        }
                        render(ctx)
                    } finally { pending.finish() }
                }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    private fun refresh(ctx: Context, pending: BroadcastReceiver.PendingResult) {
        CoroutineScope(Dispatchers.IO).launch { try { render(ctx) } finally { pending.finish() } }
    }

    private suspend fun render(ctx: Context) {
        val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
        val prefs = ep.prefs()
        val list = WidgetRules.candidates(ep.animeDao().getAll())
        val sp = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val storedId = sp.getString(KEY_CURRENT_ID, null)
        val byStoredId = storedId?.let { id -> list.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        val i = byStoredId ?: WidgetRules.wrap(sp.getInt(KEY_INDEX, 0), list.size)
        val e = list.getOrNull(i)
        val sub1 = if (list.size > 1) list[WidgetRules.wrap(i + 1, list.size)] else null
        val sub2 = if (list.size > 2) list[WidgetRules.wrap(i + 2, list.size)] else null
        sp.edit()
            .putInt(KEY_INDEX, i)
            .putString(KEY_CURRENT_ID, e?.id)
            .putString(KEY_SUB_ID_1, sub1?.id)
            .putString(KEY_SUB_ID_2, sub2?.id)
            .apply()
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, ProgressWidget::class.java))
        val coverBmp = e?.let { WidgetCovers.load(ctx, it.imageUrl) }
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_progress)
            LiquidGlassWidgetRenderer.applyToRemoteViews(ctx, v, prefs, coverBmp, widthDp = 320, heightDp = 148)
            if (e == null) {
                v.setTextViewText(R.id.widget_title, "Nothing in progress")
                v.setTextViewText(R.id.widget_progress_text, "Start a series in AniVault")
                v.setViewVisibility(R.id.widget_plus, View.GONE)
                v.setViewVisibility(R.id.widget_next, View.GONE)
                v.setViewVisibility(R.id.widget_sub_row_1, View.GONE)
                v.setViewVisibility(R.id.widget_sub_row_2, View.GONE)
                v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, null, 0))
            } else {
                v.setTextViewText(R.id.widget_title, e.title)
                coverBmp?.let { v.setImageViewBitmap(R.id.widget_cover, it) }
                v.setProgressBar(R.id.widget_bar, 100, if (e.totalEpisodes > 0) (e.watchedEpisodes * 100 / e.totalEpisodes).coerceIn(0, 100) else 0, false)
                val total = if (e.totalEpisodes > 0) "/${e.totalEpisodes}" else ""
                v.setTextViewText(R.id.widget_progress_text, "Episode ${e.watchedEpisodes}$total")
                v.setViewVisibility(R.id.widget_plus, View.VISIBLE)
                v.setViewVisibility(R.id.widget_next, if (list.size > 1) View.VISIBLE else View.GONE)
                v.setOnClickPendingIntent(R.id.widget_plus, action(ctx, ACTION_PLUS, 1))
                v.setOnClickPendingIntent(R.id.widget_next, action(ctx, ACTION_NEXT, 2))
                v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, e.id, 10))

                if (sub1 != null) {
                    v.setViewVisibility(R.id.widget_sub_row_1, View.VISIBLE)
                    val s1Total = if (sub1.totalEpisodes > 0) "/${sub1.totalEpisodes}" else ""
                    v.setTextViewText(R.id.widget_sub_title_1, "${sub1.title} · Ep ${sub1.watchedEpisodes}$s1Total")
                    v.setOnClickPendingIntent(R.id.widget_sub_plus_1, action(ctx, ACTION_PLUS_SUB_1, 3))
                } else {
                    v.setViewVisibility(R.id.widget_sub_row_1, View.GONE)
                }

                // Keep widget_sub_row_2 hidden by default on compact 2-row heights so buttons never overflow;
                // only show sub1 below the main action row.
                v.setViewVisibility(R.id.widget_sub_row_2, View.GONE)
            }
            mgr.updateAppWidget(id, v)
        }
    }

    private fun action(ctx: Context, a: String, code: Int) =
        PendingIntent.getBroadcast(ctx, code, Intent(ctx, ProgressWidget::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val ACTION_PLUS = "com.anivault.widget.PLUS"
        const val ACTION_PLUS_SUB_1 = "com.anivault.widget.PLUS_SUB_1"
        const val ACTION_PLUS_SUB_2 = "com.anivault.widget.PLUS_SUB_2"
        const val ACTION_NEXT = "com.anivault.widget.NEXT"
        private const val STATE = "anivault_widget"
        private const val KEY_INDEX = "index"
        private const val KEY_CURRENT_ID = "current_id"
        private const val KEY_SUB_ID_1 = "sub_id_1"
        private const val KEY_SUB_ID_2 = "sub_id_2"
    }
}

/** Downloads (or reads from the app's cover cache) a cover as a bitmap with all 4 corners smoothly rounded so it sits cleanly inside the Liquid Glass card. */
object WidgetCovers {
    suspend fun load(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val loader = coil.Coil.imageLoader(ctx)
        for (u in listOf(com.anivault.ui.common.CoverUrls.upgrade(url), url).distinct()) {
            val req = coil.request.ImageRequest.Builder(ctx).data(u).size(320, 460).allowHardware(false).build()
            val d = (loader.execute(req) as? coil.request.SuccessResult)?.drawable ?: continue
            return roundAllCorners(d.toBitmap(), 14f * ctx.resources.displayMetrics.density)
        }
        return null
    }

    private fun roundAllCorners(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        c.drawRoundRect(rect, r, r, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }
}

/** Pure text for the airing widget rows (tested). */
object AiringWidgetText {
    fun row(title: String, episode: Int, relative: String, behind: Int = 0): String =
        "$title · Ep $episode · $relative" + (if (behind > 0) " ($behind behind)" else "")

    fun meta(episode: Int, relative: String, behind: Int = 0): String =
        "Ep $episode · $relative" + (if (behind > 0) " ($behind behind)" else "")
}

private fun openEntry(ctx: Context, entryId: String?, requestCode: Int): PendingIntent {
    val intent = Intent(ctx, MainActivity::class.java).apply {
        if (!entryId.isNullOrBlank()) {
            action = Intent.ACTION_VIEW
            data = Uri.parse("anivault://entry/${Uri.encode(entryId)}")
        }
    }
    return PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Lists the next episodes airing for shows you follow, with a right-aligned episode/countdown column that is never truncated by long titles. */
class AiringWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
                val prefs = ep.prefs()
                runCatching { ep.airing().refresh() }
                val items = ep.airing().items().take(4)
                val titleIds = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
                val metaIds = intArrayOf(R.id.row1_meta, R.id.row2_meta, R.id.row3_meta, R.id.row4_meta)
                val boxIds = intArrayOf(R.id.row1_box, R.id.row2_box, R.id.row3_box, R.id.row4_box)
                val now = System.currentTimeMillis()
                for (id in ids) {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_airing)
                    val accentArgb = LiquidGlassWidgetRenderer.applyToRemoteViews(ctx, v, prefs, coverBitmap = null, widthDp = 320, heightDp = 140)
                    titleIds.indices.forEach { i ->
                        val it = items.getOrNull(i)
                        if (it != null) {
                            v.setViewVisibility(boxIds[i], View.VISIBLE)
                            val targetEp = if (it.airingAt <= now) it.episode else it.episode - 1
                            val behind = (targetEp - it.watched).coerceAtLeast(0)
                            val rel = com.anivault.data.provider.AiringFormat.relative(it.airingAt, now)
                            v.setTextViewText(titleIds[i], it.title)
                            v.setTextViewText(metaIds[i], AiringWidgetText.meta(it.episode, rel, behind))
                            v.setTextColor(metaIds[i], accentArgb)
                            v.setOnClickPendingIntent(boxIds[i], openEntry(ctx, it.libraryId, 100 + i))
                        } else if (i == 0) {
                            v.setViewVisibility(boxIds[i], View.VISIBLE)
                            v.setTextViewText(titleIds[i], "Nothing scheduled — follow airing shows to see them here")
                            v.setTextViewText(metaIds[i], "")
                            v.setOnClickPendingIntent(boxIds[i], openEntry(ctx, null, 100))
                        } else {
                            v.setViewVisibility(boxIds[i], View.GONE)
                        }
                    }
                    v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, items.firstOrNull()?.libraryId, 99))
                    mgr.updateAppWidget(id, v)
                }
            } finally { pending.finish() }
        }
    }
}

/** Counts of what you are watching and completed, plus total watch time, with single-line auto-sizing so words never split mid-word. */
class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
                val prefs = ep.prefs()
                val all = ep.animeDao().getAll()
                val watching = all.count { it.status == WatchStatus.WATCHING }
                val done = all.count { it.status == WatchStatus.COMPLETED }
                val hours = all.sumOf { com.anivault.data.library.StatsCalculator.totalWatchedWithRewatches(it).toLong() * it.durationMinutes } / 60
                for (id in ids) {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_stats)
                    LiquidGlassWidgetRenderer.applyToRemoteViews(ctx, v, prefs, coverBitmap = null, widthDp = 200, heightDp = 130)
                    v.setTextViewText(R.id.stat_main, "$watching Watching")
                    v.setTextViewText(R.id.stat_sub, "$done completed\n${hours}h watched")
                    v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, null, 200))
                    mgr.updateAppWidget(id, v)
                }
            } finally { pending.finish() }
        }
    }
}

object WidgetUpdater {
    /** Redraws every AniVault widget (called when the app goes to the background or theme changes). */
    fun refreshAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (cls in listOf(ProgressWidget::class.java, AiringWidget::class.java, StatsWidget::class.java)) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isNotEmpty()) ctx.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setClass(ctx, cls).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }
}
