package com.anivault.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Extracts a vibrant accent colour from a cover image without external palette dependencies.
 * Samples a 16x16 grid of pixels, picks the highest-saturation colour with readable luminance,
 * and boosts lightness slightly so it reads well on dark and light surfaces.
 * Uses pure Kotlin RGB<->HSV math so it is also testable in JVM unit tests.
 */
object CoverPalette {

    internal fun rgbToHsv(argb: Int, outHsv: FloatArray) {
        val r = ((argb ushr 16) and 0xff) / 255f
        val g = ((argb ushr 8) and 0xff) / 255f
        val b = (argb and 0xff) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val h = when {
            delta == 0f -> 0f
            maxC == r   -> 60f * (((g - b) / delta) % 6f)
            maxC == g   -> 60f * (((b - r) / delta) + 2f)
            else        -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val s = if (maxC == 0f) 0f else delta / maxC
        outHsv[0] = h
        outHsv[1] = s
        outHsv[2] = maxC
    }

    internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f  -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else     -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun extractFromArgb(pixels: IntArray): Color? {
        if (pixels.isEmpty()) return null
        val hsv = FloatArray(3)
        var bestHue = 0f
        var bestSat = 0f
        var bestVal = 0f
        var bestScore = 0f

        for (px in pixels) {
            val a = (px ushr 24) and 0xff
            if (a < 180) continue
            rgbToHsv(px, hsv)
            val s = hsv[1]
            val v = hsv[2]
            // Ignore near-black, near-white, or washed-out grey pixels.
            if (s < 0.22f || v < 0.20f || v > 0.96f) continue
            val score = s * (1f - abs(v - 0.65f))
            if (score > bestScore) {
                bestScore = score
                bestHue = hsv[0]
                bestSat = s.coerceIn(0.45f, 0.85f)
                bestVal = v.coerceIn(0.68f, 0.92f)
            }
        }
        if (bestScore <= 0f) return null
        return Color(hsvToArgb(bestHue, bestSat, bestVal))
    }

    suspend fun loadAccent(ctx: android.content.Context, url: String): Color? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val req = ImageRequest.Builder(ctx)
                .data(url)
                .size(64, 64)
                .allowHardware(false)
                .build()
            val drawable = (Coil.imageLoader(ctx).execute(req) as? SuccessResult)?.drawable ?: return@withContext null
            val bmp: Bitmap = drawable.toBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            extractFromArgb(pixels)
        }.getOrNull()
    }
}

@Composable
fun rememberCoverAccent(url: String, fallback: Color): Color {
    val ctx = LocalContext.current
    var accent by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        accent = CoverPalette.loadAccent(ctx, url)
    }
    return accent ?: fallback
}
