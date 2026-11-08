package com.anivault.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.anivault.MainActivity
import com.anivault.R
import com.anivault.core.AppPrefs
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.model.WatchStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Pure quiet-hours rule (spec §14). Hours are 0–23; a window may wrap past midnight (22 → 8). */
object QuietHours {
    fun isQuiet(hour: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> hour in start until end
        else -> hour >= start || hour < end
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyEntryPoint { fun prefs(): AppPrefs; fun animeDao(): AnimeDao; fun airing(): com.anivault.data.provider.AiringRepository }

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    private const val WORK = "anivault-reminder"
    private const val WORK_AIRING = "anivault-airing"
    const val CHANNEL_AIRING = "airing"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Watch reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A gentle nudge about shows you're in the middle of" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AIRING, "New episodes", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a new episode of a show you're watching has aired" }
        )
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** (Re)schedules or cancels the daily reminder check according to settings. */
    fun applySettings(ctx: Context, prefs: AppPrefs) {
        val wm = WorkManager.getInstance(ctx)
        if (prefs.notifEnabled && prefs.notifReminders) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(WORK)
        if (prefs.notifEnabled && prefs.notifAiring) {
            val req = PeriodicWorkRequestBuilder<AiringWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork(WORK_AIRING, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(WORK_AIRING)
    }
}

/**
 * Posts at most one reminder a day. The text is generic on purpose: it never contains titles, so
 * nothing private appears on a lock screen (spec §14/§16).
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Permission is checked above via Notifications.hasPermission(); lint can't see through the helper.
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, NotifyEntryPoint::class.java)
        val prefs = ep.prefs()
        if (!prefs.notifEnabled || !prefs.notifReminders || !Notifications.hasPermission(applicationContext)) return Result.success()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (QuietHours.isQuiet(hour, prefs.quietStartHour, prefs.quietEndHour)) return Result.retry()

        val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        val stale = ep.animeDao().getAll().count { it.status == WatchStatus.WATCHING && it.updatedAt < cutoff }
        if (stale == 0) return Result.success()

        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pick up where you left off")
            .setContentText(if (stale == 1) "You have a show in progress." else "You have $stale shows in progress.")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(1001, n) }
        return Result.success()
    }
}

/**
 * Announces new episodes of shows being watched. Only the normal library is ever involved (H-Vault titles are not
 * looked up), and the lock-screen version of each notification is generic.
 */
class AiringWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, NotifyEntryPoint::class.java)
        val prefs = ep.prefs()
        if (!prefs.notifEnabled || !prefs.notifAiring) return Result.success()
        val repo = ep.airing()
        runCatching { repo.refresh(maxAgeMs = 2 * 3_600_000L) }.onFailure { return Result.retry() }
        if (!Notifications.hasPermission(applicationContext)) return Result.success()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (QuietHours.isQuiet(hour, prefs.quietStartHour, prefs.quietEndHour)) return Result.success() // not marked, so a later run announces it

        val now = System.currentTimeMillis()
        val fresh = repo.items().filter { it.airingAt in (now - 12 * 3_600_000L)..now && !repo.wasNotified("${it.libraryId}:${it.episode}") }
        if (fresh.isEmpty()) return Result.success()
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val publicVersion = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_AIRING)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("New episode available").build()
        fresh.take(5).forEachIndexed { i, item ->
            val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_AIRING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(item.title)
                .setContentText("Episode ${item.episode} just aired")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(applicationContext).notify(2000 + i, n) }
            repo.markNotified("${item.libraryId}:${item.episode}")
        }
        fresh.drop(5).forEach { repo.markNotified("${it.libraryId}:${it.episode}") }
        return Result.success()
    }
}
