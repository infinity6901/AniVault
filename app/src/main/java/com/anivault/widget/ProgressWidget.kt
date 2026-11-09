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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
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
 * Dynamically renders a true 3D Liquid Glass surface bitmap for RemoteViews homescreen widgets.
 * Includes translucent frosted glass fill, radial light orbs, diagonal surface sheen,
 * inner top caustic highlight, and a directional 3D specular rim border.
 */
object LiquidGlassWidgetRenderer {

    private fun withAlpha(argb: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    fun applyToRemoteViews(
        ctx: Context,
        views: RemoteViews,
        prefs: AppPrefs,
        coverBitmap: Bitmap? = null,
        widthDp: Int = 320,
        heightDp: Int = 150
    ) {
        val tint = LiquidGlassTint.parse(prefs.glassTint)
        val coverAccentArgb = if (prefs.widgetGlassMatchCover && coverBitmap != null) {
            extractFromBitmap(coverBitmap)
        } else null
        val accentArgb = coverAccentArgb ?: tint.primary.toArgb()
        views.setTextColor(R.id.widget_label, accentArgb)

        if (!prefs.widgetGlassEnabled) {
            views.setImageViewBitmap(R.id.widget_glass_bg, null)
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
            return
        }

        views.setInt(R.id.widget_root, "setBackgroundResource", android.R.color.transparent)
        val bmp = renderSurfaceBitmap(ctx, prefs, tint, accentArgb, widthDp, heightDp)
        views.setImageViewBitmap(R.id.widget_glass_bg, bmp)
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
        val radius = 20f * density
        val stroke = 1.4f * density

        val opacity = (prefs.widgetGlassOpacity.coerceIn(15, 85) / 100f)
        val spec = (prefs.glassSpecular.coerceIn(0, 100) / 100f)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)

        // 1. Base frosted glass body
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    withAlpha(tint.baseStart.toArgb(), (opacity * 1.05f).coerceIn(0.15f, 0.90f)),
                    withAlpha(tint.baseEnd.toArgb(), (opacity * 0.92f).coerceIn(0.12f, 0.86f))
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, basePaint)

        // 2. Primary glowing liquid orb (top-right / center-right)
        val orb1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.78f, h * 0.26f, w * 0.62f,
                intArrayOf(withAlpha(accentArgb, 0.36f), 0x00000000),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, orb1Paint)

        // 3. Secondary glowing liquid orb (bottom-left)
        val orb2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.22f, h * 0.85f, w * 0.55f,
                intArrayOf(withAlpha(tint.orb2.toArgb(), 0.28f), 0x00000000),
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
                        withAlpha(0xFFFFFFFF.toInt(), 0.20f * spec),
                        withAlpha(0xFFFFFFFF.toInt(), 0.05f * spec),
                        0x00000000,
                        withAlpha(accentArgb, 0.09f * spec)
                    ),
                    floatArrayOf(0f, 0.32f, 0.68f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        }

        // 5. Inner top-edge caustic highlight line
        if (spec > 0.05f) {
            val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * density
                shader = LinearGradient(
                    radius, 0f, w - radius, 0f,
                    intArrayOf(
                        0x00FFFFFF,
                        withAlpha(0xFFFFFFFF.toInt(), 0.58f * spec),
                        withAlpha(accentArgb, 0.44f * spec),
                        0x00FFFFFF
                    ),
                    floatArrayOf(0f, 0.35f, 0.70f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawLine(radius * 0.8f, stroke * 1.2f, w - radius * 0.8f, stroke * 1.2f, causticPaint)
        }

        // 6. Directional 3D specular rim border
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    withAlpha(0xFFFFFFFF.toInt(), (0.76f * spec).coerceIn(0.14f, 0.88f)),
                    withAlpha(accentArgb, (0.52f * spec).coerceIn(0.10f, 0.68f)),
                    withAlpha(0xFFFFFFFF.toInt(), (0.14f * spec).coerceIn(0.05f, 0.28f)),
                    withAlpha(tint.secondary.toArgb(), (0.45f * spec).coerceIn(0.08f, 0.58f))
                ),
                floatArrayOf(0f, 0.35f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, rimPaint)

        return bmp
    }
}

/** Home-screen widget: shows your primary watching title plus up to 2 secondary watchlist rows, each with +1 buttons. */
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
            LiquidGlassWidgetRenderer.applyToRemoteViews(ctx, v, prefs, coverBmp, widthDp = 320, heightDp = 156)
            if (e == null) {
                v.setTextViewText(R.id.widget_title, "Nothing in progress")
                v.setTextViewText(R.id.widget_progress_text, "Start a series in AniVault")
                v.setViewVisibility(R.id.widget_plus, android.view.View.GONE)
                v.setViewVisibility(R.id.widget_next, android.view.View.GONE)
                v.setViewVisibility(R.id.widget_sub_row_1, android.view.View.GONE)
                v.setViewVisibility(R.id.widget_sub_row_2, android.view.View.GONE)
                v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, null, 0))
            } else {
                v.setTextViewText(R.id.widget_title, e.title)
                coverBmp?.let { v.setImageViewBitmap(R.id.widget_cover, it) }
                v.setProgressBar(R.id.widget_bar, 100, if (e.totalEpisodes > 0) (e.watchedEpisodes * 100 / e.totalEpisodes).coerceIn(0, 100) else 0, false)
                val total = if (e.totalEpisodes > 0) "/${e.totalEpisodes}" else ""
                v.setTextViewText(R.id.widget_progress_text, "Episode ${e.watchedEpisodes}$total")
                v.setViewVisibility(R.id.widget_plus, android.view.View.VISIBLE)
                v.setViewVisibility(R.id.widget_next, if (list.size > 1) android.view.View.VISIBLE else android.view.View.GONE)
                v.setOnClickPendingIntent(R.id.widget_plus, action(ctx, ACTION_PLUS, 1))
                v.setOnClickPendingIntent(R.id.widget_next, action(ctx, ACTION_NEXT, 2))
                v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, e.id, 10))

                if (sub1 != null) {
                    v.setViewVisibility(R.id.widget_sub_row_1, android.view.View.VISIBLE)
                    val s1Total = if (sub1.totalEpisodes > 0) "/${sub1.totalEpisodes}" else ""
                    v.setTextViewText(R.id.widget_sub_title_1, "${sub1.title} · Ep ${sub1.watchedEpisodes}$s1Total")
                    v.setOnClickPendingIntent(R.id.widget_sub_plus_1, action(ctx, ACTION_PLUS_SUB_1, 3))
                } else {
                    v.setViewVisibility(R.id.widget_sub_row_1, android.view.View.GONE)
                }

                if (sub2 != null) {
                    v.setViewVisibility(R.id.widget_sub_row_2, android.view.View.VISIBLE)
                    val s2Total = if (sub2.totalEpisodes > 0) "/${sub2.totalEpisodes}" else ""
                    v.setTextViewText(R.id.widget_sub_title_2, "${sub2.title} · Ep ${sub2.watchedEpisodes}$s2Total")
                    v.setOnClickPendingIntent(R.id.widget_sub_plus_2, action(ctx, ACTION_PLUS_SUB_2, 4))
                } else {
                    v.setViewVisibility(R.id.widget_sub_row_2, android.view.View.GONE)
                }
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

/** Downloads (or reads from the app's cover cache) a cover as a small bitmap with rounded left corners for widgets. */
object WidgetCovers {
    suspend fun load(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val loader = coil.Coil.imageLoader(ctx)
        for (u in listOf(com.anivault.ui.common.CoverUrls.upgrade(url), url).distinct()) {
            val req = coil.request.ImageRequest.Builder(ctx).data(u).size(360, 520).allowHardware(false).build()
            val d = (loader.execute(req) as? coil.request.SuccessResult)?.drawable ?: continue
            return roundLeft(d.toBitmap(), 20f * ctx.resources.displayMetrics.density)
        }
        return null
    }

    private fun roundLeft(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        c.drawRoundRect(rect, r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        // Square off the right side so it joins the text panel.
        val sq = Paint()
        sq.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OVER)
        c.drawBitmap(src, android.graphics.Rect(src.width - r.toInt(), 0, src.width, src.height), android.graphics.Rect(src.width - r.toInt(), 0, src.width, src.height), sq)
        return out
    }
}

/** Pure text for the airing widget rows (tested). */
object AiringWidgetText {
    fun row(title: String, episode: Int, relative: String, behind: Int = 0): String =
        "$title · Ep $episode · $relative" + (if (behind > 0) " ($behind behind)" else "")
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

/** Lists the next episodes airing for shows you follow, with behind badges and per-row deep links. */
class AiringWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
                val prefs = ep.prefs()
                runCatching { ep.airing().refresh() }
                val items = ep.airing().items().take(4)
                val rows = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
                val now = System.currentTimeMillis()
                for (id in ids) {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_airing)
                    LiquidGlassWidgetRenderer.applyToRemoteViews(ctx, v, prefs, coverBitmap = null, widthDp = 320, heightDp = 140)
                    rows.forEachIndexed { i, r ->
                        val it = items.getOrNull(i)
                        if (it != null) {
                            val targetEp = if (it.airingAt <= now) it.episode else it.episode - 1
                            val behind = (targetEp - it.watched).coerceAtLeast(0)
                            v.setTextViewText(r, AiringWidgetText.row(it.title, it.episode, com.anivault.data.provider.AiringFormat.relative(it.airingAt, now), behind))
                            v.setOnClickPendingIntent(r, openEntry(ctx, it.libraryId, 100 + i))
                        } else {
                            v.setTextViewText(r, if (i == 0) "Nothing scheduled — follow airing shows to see them here" else "")
                            v.setOnClickPendingIntent(r, openEntry(ctx, null, 100 + i))
                        }
                    }
                    v.setOnClickPendingIntent(R.id.widget_root, openEntry(ctx, items.firstOrNull()?.libraryId, 99))
                    mgr.updateAppWidget(id, v)
                }
            } finally { pending.finish() }
        }
    }
}

/** Counts of what you are watching and completed, plus total watch time. */
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
                    LiquidGlassWidgetRenderer.applyToRemoteViews(ctx, v, prefs, coverBitmap = null, widthDp = 240, heightDp = 120)
                    v.setTextViewText(R.id.stat_main, "$watching watching")
                    v.setTextViewText(R.id.stat_sub, "$done completed · ${hours}h watched")
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
