package com.anivault.ui.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anivault.data.library.StatsCalculator
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One printable line of an export, shared by the PDF and Word writers. */
data class ExportRow(
    val title: String,
    /** Second line under the title: genres, tags or studio. */
    val detail: String,
    val coverUrl: String,
    val status: WatchStatus,
    val watched: Int,
    val total: Int,
    val rating: Float,
    val minutes: Long,
    /** True when [minutes] used a typical episode length because the database had none. */
    val estimated: Boolean
) {
    val progress: String get() = if (total > 0) "$watched/$total" else "$watched/?"
}

data class ExportStats(val entries: Int, val episodes: Int, val minutes: Long, val estimated: Boolean, val avgRating: Double?)

object ExportData {
    /** A completed title with a known episode count has, by definition, been watched to the end. */
    fun watched(status: WatchStatus, watched: Int, total: Int): Int =
        if (status == WatchStatus.COMPLETED && total > 0) maxOf(watched, total) else watched

    fun anime(entries: List<AnimeEntry>): List<ExportRow> = entries.map { e ->
        val w = watched(e.status, e.watchedEpisodes, e.totalEpisodes)
        val per = StatsCalculator.effectiveMinutes(e)
        ExportRow(e.title, e.genres.take(4).joinToString(" · "), e.imageUrl, e.status, w, e.totalEpisodes, e.rating, w.toLong() * per, e.durationMinutes <= 0)
    }

    fun vault(entries: List<HVaultEntry>): List<ExportRow> = entries.map { e ->
        val w = watched(e.status, e.watchedEpisodes, e.totalEpisodes)
        val per = if (e.durationMinutes > 0) e.durationMinutes else StatsCalculator.typicalMinutes("")
        val detail = listOfNotNull(e.studio?.takeIf { it.isNotBlank() }, e.tags.take(4).joinToString(" · ").takeIf { it.isNotBlank() }).joinToString(" — ")
        ExportRow(e.title, detail, e.imageUrl, e.status, w, e.totalEpisodes, e.rating, w.toLong() * per, e.durationMinutes <= 0)
    }

    fun stats(rows: List<ExportRow>): ExportStats {
        val rated = rows.filter { it.rating > 0 }
        return ExportStats(
            entries = rows.size,
            episodes = rows.sumOf { it.watched },
            minutes = rows.sumOf { it.minutes },
            estimated = rows.any { it.estimated && it.watched > 0 },
            avgRating = if (rated.isEmpty()) null else rated.map { it.rating.toDouble() }.average()
        )
    }

    val ORDER = listOf(WatchStatus.WATCHING, WatchStatus.COMPLETED, WatchStatus.ON_HOLD, WatchStatus.PLAN_TO_WATCH, WatchStatus.DROPPED)

    fun statusLabel(s: WatchStatus) = when (s) {
        WatchStatus.PLAN_TO_WATCH -> "Plan to Watch"
        WatchStatus.ON_HOLD -> "On Hold"
        else -> s.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun duration(minutes: Long): String = when {
        minutes <= 0 -> "0m"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / (60 * 24)}d ${(minutes / 60) % 24}h"
    }
}

/** Downloads small JPEG thumbnails of cover art for exports. Missing or failed covers are simply skipped. */
@Singleton
class CoverLoader @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    /** url -> JPEG bytes, about 160 px wide. Runs up to 6 downloads at once. */
    fun load(urls: Collection<String>): Map<String, ByteArray> = runBlocking(Dispatchers.IO) {
        val gate = Semaphore(6)
        urls.filter { it.startsWith("http", ignoreCase = true) }.distinct()
            .map { u -> async { u to gate.withPermit { fetch(u) } } }
            .mapNotNull { d -> d.await().let { (u, b) -> b?.let { u to it } } }
            .toMap()
    }

    private fun fetch(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val raw = r.body?.bytes() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_W) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scaled = if (bmp.width > TARGET_W) Bitmap.createScaledBitmap(bmp, TARGET_W, (bmp.height * TARGET_W / bmp.width.toFloat()).toInt().coerceAtLeast(1), true) else bmp
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }
    } catch (_: Exception) { null }

    private companion object { const val TARGET_W = 160 }
}
