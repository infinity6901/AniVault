package com.anivault.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.anivault.MainActivity
import com.anivault.R
import com.anivault.core.AppPrefs
import com.anivault.data.library.ProgressRules
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint { fun animeDao(): AnimeDao; fun prefs(): AppPrefs; fun airing(): com.anivault.data.provider.AiringRepository }

/** Pure rules so the widget logic is testable without Android. */
object WidgetRules {
    /** Titles worth showing: currently watching, not finished, most recently touched first. */
    fun candidates(all: List<AnimeEntry>): List<AnimeEntry> =
        all.filter { it.status == WatchStatus.WATCHING && (it.totalEpisodes == 0 || it.watchedEpisodes < it.totalEpisodes) }
            .sortedByDescending { it.updatedAt }

    fun wrap(index: Int, size: Int): Int = if (size <= 0) 0 else ((index % size) + size) % size
}

/** Home-screen widget: shows one title you are watching, with +1 episode and next-title buttons. */
class ProgressWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, goAsync())

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLUS, ACTION_NEXT -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
                        val list = WidgetRules.candidates(ep.animeDao().getAll())
                        val sp = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
                        val i = WidgetRules.wrap(sp.getInt(KEY_INDEX, 0), list.size)
                        if (intent.action == ACTION_PLUS) {
                            list.getOrNull(i)?.let { e ->
                                val now = System.currentTimeMillis()
                                ep.animeDao().upsert(ProgressRules.apply(e, e.watchedEpisodes + 1, ep.prefs().autoCompleteOnLast, now).copy(pendingSync = true, updatedAt = now))
                            }
                        } else sp.edit().putInt(KEY_INDEX, i + 1).apply()
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
        val list = WidgetRules.candidates(ep.animeDao().getAll())
        val sp = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val i = WidgetRules.wrap(sp.getInt(KEY_INDEX, 0), list.size)
        val e = list.getOrNull(i)
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, ProgressWidget::class.java))
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_progress)
            if (e == null) {
                v.setTextViewText(R.id.widget_title, "Nothing in progress")
                v.setTextViewText(R.id.widget_progress_text, "Start a series in AniVault")
                v.setViewVisibility(R.id.widget_plus, android.view.View.GONE)
                v.setViewVisibility(R.id.widget_next, android.view.View.GONE)
            } else {
                v.setTextViewText(R.id.widget_title, e.title)
                WidgetCovers.load(ctx, e.imageUrl)?.let { v.setImageViewBitmap(R.id.widget_cover, it) }
                v.setProgressBar(R.id.widget_bar, 100, if (e.totalEpisodes > 0) (e.watchedEpisodes * 100 / e.totalEpisodes).coerceIn(0, 100) else 0, false)
                val total = if (e.totalEpisodes > 0) "/${e.totalEpisodes}" else ""
                v.setTextViewText(R.id.widget_progress_text, "Episode ${e.watchedEpisodes}$total")
                v.setViewVisibility(R.id.widget_plus, android.view.View.VISIBLE)
                v.setViewVisibility(R.id.widget_next, if (list.size > 1) android.view.View.VISIBLE else android.view.View.GONE)
                v.setOnClickPendingIntent(R.id.widget_plus, action(ctx, ACTION_PLUS, 1))
                v.setOnClickPendingIntent(R.id.widget_next, action(ctx, ACTION_NEXT, 2))
            }
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            mgr.updateAppWidget(id, v)
        }
    }

    private fun action(ctx: Context, a: String, code: Int) =
        PendingIntent.getBroadcast(ctx, code, Intent(ctx, ProgressWidget::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val ACTION_PLUS = "com.anivault.widget.PLUS"
        const val ACTION_NEXT = "com.anivault.widget.NEXT"
        private const val STATE = "anivault_widget"
        private const val KEY_INDEX = "index"
    }
}

/** Downloads (or reads from the app's cover cache) a cover as a small bitmap with rounded left corners for widgets. */
object WidgetCovers {
    suspend fun load(ctx: Context, url: String): android.graphics.Bitmap? {
        if (url.isBlank()) return null
        val loader = coil.Coil.imageLoader(ctx)
        for (u in listOf(com.anivault.ui.common.CoverUrls.upgrade(url), url).distinct()) {
            val req = coil.request.ImageRequest.Builder(ctx).data(u).size(360, 520).allowHardware(false).build()
            val d = (loader.execute(req) as? coil.request.SuccessResult)?.drawable ?: continue
            return roundLeft(androidx.core.graphics.drawable.toBitmap(d), 20f * ctx.resources.displayMetrics.density)
        }
        return null
    }

    private fun roundLeft(src: android.graphics.Bitmap, r: Float): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rect = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        c.drawRoundRect(rect, r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        // Square off the right side so it joins the text panel.
        val sq = android.graphics.Paint()
        sq.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OVER)
        c.drawBitmap(src, android.graphics.Rect(src.width - r.toInt(), 0, src.width, src.height), android.graphics.Rect(src.width - r.toInt(), 0, src.width, src.height), sq)
        return out
    }
}

/** Pure text for the airing widget rows (tested). */
object AiringWidgetText {
    fun row(title: String, episode: Int, relative: String) = "$title · Ep $episode · $relative"
}

private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Lists the next episodes airing for shows you follow. */
class AiringWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java)
                runCatching { ep.airing().refresh() }
                val items = ep.airing().items().take(4)
                val rows = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
                val now = System.currentTimeMillis()
                for (id in ids) {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_airing)
                    rows.forEachIndexed { i, r ->
                        val it = items.getOrNull(i)
                        v.setTextViewText(r, when {
                            it != null -> AiringWidgetText.row(it.title, it.episode, com.anivault.data.provider.AiringFormat.relative(it.airingAt, now))
                            i == 0 -> "Nothing scheduled — follow airing shows to see them here"
                            else -> ""
                        })
                    }
                    v.setOnClickPendingIntent(R.id.widget_root, open(ctx))
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
                val all = EntryPointAccessors.fromApplication(ctx.applicationContext, WidgetEntryPoint::class.java).animeDao().getAll()
                val watching = all.count { it.status == WatchStatus.WATCHING }
                val done = all.count { it.status == WatchStatus.COMPLETED }
                val hours = all.sumOf { it.watchedEpisodes.toLong() * it.durationMinutes } / 60
                for (id in ids) {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_stats)
                    v.setTextViewText(R.id.stat_main, "$watching watching")
                    v.setTextViewText(R.id.stat_sub, "$done completed · ${hours}h watched")
                    v.setOnClickPendingIntent(R.id.widget_root, open(ctx))
                    mgr.updateAppWidget(id, v)
                }
            } finally { pending.finish() }
        }
    }
}

object WidgetUpdater {
    /** Redraws every AniVault widget (called when the app goes to the background so they match the library). */
    fun refreshAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (cls in listOf(ProgressWidget::class.java, AiringWidget::class.java, StatsWidget::class.java)) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isNotEmpty()) ctx.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setClass(ctx, cls).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }
}
