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
interface WidgetEntryPoint { fun animeDao(): AnimeDao; fun prefs(): AppPrefs }

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
