package com.anivault.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.anivault.data.local.model.WatchStatus

/** Design tokens from the product specification (§2). */
object AniVaultColors {
    val Background = Color(0xFF0B0D16)
    val Bars = Color(0xFF151A2A)
    val Card = Color(0xFF1B2132)
    val Primary = Color(0xFFB56AF0)
    val OnPrimary = Color(0xFF1E0B30)
    val Secondary = Color(0xFFE58AD8)
    val Progress = Color(0xFF20C7B5)
    val Text = Color(0xFFF6F4FB)
    val TextMuted = Color(0xFFA8A9BD)
    val Outline = Color(0xFF2A3044)
    val Error = Color(0xFFF2A3B0)
    val Success = Color(0xFF54D6A4)
    val Warning = Color(0xFFF2C46B)

    val StatusWatching = Progress
    val StatusCompleted = Success
    val StatusOnHold = Warning
    val StatusPlan = Primary
    val StatusDropped = Error

    // Kept for the H-Vault accent.
    val HVaultPrimary = Color(0xFFF36BCB)
}

enum class AppMode { ANIME, H_VAULT }

fun WatchStatus.color(): Color = when (this) {
    WatchStatus.WATCHING -> AniVaultColors.StatusWatching
    WatchStatus.COMPLETED -> AniVaultColors.StatusCompleted
    WatchStatus.ON_HOLD -> AniVaultColors.StatusOnHold
    WatchStatus.PLAN_TO_WATCH -> AniVaultColors.StatusPlan
    WatchStatus.DROPPED -> AniVaultColors.StatusDropped
}

/** Muted secondary text colour (was the nonexistent `onSurfaceDim`). */
val ColorScheme.dim: Color get() = onSurfaceVariant

val ColorScheme.progress: Color get() = AniVaultColors.Progress
val ColorScheme.success: Color get() = AniVaultColors.Success

/** Colour palettes the user can pick in Settings → Appearance. */
enum class Palette(val key: String, val label: String, val blurb: String, val swatch: List<Color>) {
    ANIVAULT("anivault", "AniVault", "The default purple.", listOf(Color(0xFF0B0D16), Color(0xFFB56AF0), Color(0xFFE58AD8))),
    DYNAMIC("dynamic", "Material You", "Colors from your wallpaper (Android 12+).", listOf(Color(0xFF4A5568), Color(0xFF8AB4F8), Color(0xFFCBA6F7))),
    GLASS("glass", "Frosted glass", "Translucent panels over a soft gradient.", listOf(Color(0xFF0F766E), Color(0xFF7DD3FC), Color(0xFFC4B5FD))),
    AMOLED("amoled", "AMOLED black", "True black for OLED screens.", listOf(Color(0xFF000000), Color(0xFFD0BCFF), Color(0xFF9AD0F5))),
    MIDNIGHT("midnight", "Midnight blue", "Deep navy with a cool blue accent.", listOf(Color(0xFF071225), Color(0xFF5AA9FF), Color(0xFF7CE0FF))),
    FOREST("forest", "Forest", "Dark green with a fresh mint accent.", listOf(Color(0xFF08130E), Color(0xFF4ADE80), Color(0xFFA3E635))),
    SUNSET("sunset", "Sunset", "Warm charcoal with orange and pink.", listOf(Color(0xFF150E0C), Color(0xFFFF8A4C), Color(0xFFFF5D8F))),
    SAKURA("sakura", "Sakura", "Soft plum with cherry-blossom pink.", listOf(Color(0xFF180F16), Color(0xFFF9A8D4), Color(0xFFC4B5FD)));

    companion object {
        fun parse(v: String?): Palette = values().firstOrNull { it.key == v } ?: ANIVAULT
    }
}

private class Tone(val bg: Color, val bars: Color, val card: Color, val outline: Color, val primary: Color, val onPrimary: Color, val secondary: Color, val tertiary: Color)

private fun tone(p: Palette): Tone = when (p) {
    Palette.AMOLED -> Tone(Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF141414), Color(0xFF2B2B2B), Color(0xFFD0BCFF), Color(0xFF2B1A4D), Color(0xFF9AD0F5), AniVaultColors.Progress)
    Palette.MIDNIGHT -> Tone(Color(0xFF071225), Color(0xFF0D1B33), Color(0xFF13233F), Color(0xFF223657), Color(0xFF5AA9FF), Color(0xFF03213F), Color(0xFF7CE0FF), Color(0xFF3DDBC0))
    Palette.FOREST -> Tone(Color(0xFF08130E), Color(0xFF0F1F17), Color(0xFF16291F), Color(0xFF244033), Color(0xFF4ADE80), Color(0xFF05260F), Color(0xFFA3E635), Color(0xFF2DD4BF))
    Palette.SUNSET -> Tone(Color(0xFF150E0C), Color(0xFF211613), Color(0xFF2B1D19), Color(0xFF44302A), Color(0xFFFF8A4C), Color(0xFF3A1600), Color(0xFFFF5D8F), Color(0xFFFFC857))
    Palette.SAKURA -> Tone(Color(0xFF180F16), Color(0xFF231620), Color(0xFF2E1C29), Color(0xFF47303F), Color(0xFFF9A8D4), Color(0xFF3F0F2A), Color(0xFFC4B5FD), Color(0xFF86EFAC))
    else -> Tone(AniVaultColors.Background, AniVaultColors.Bars, AniVaultColors.Card, AniVaultColors.Outline, AniVaultColors.Primary, AniVaultColors.OnPrimary, AniVaultColors.Secondary, AniVaultColors.Progress)
}

private fun darkScheme(t: Tone) = darkColorScheme(
    primary = t.primary, onPrimary = t.onPrimary, secondary = t.secondary, onSecondary = t.onPrimary, tertiary = t.tertiary,
    background = t.bg, onBackground = AniVaultColors.Text,
    surface = t.bars, onSurface = AniVaultColors.Text,
    surfaceVariant = t.card, onSurfaceVariant = AniVaultColors.TextMuted,
    surfaceContainerLowest = t.bg, surfaceContainerLow = t.bars, surfaceContainer = t.card, surfaceContainerHigh = t.card, surfaceContainerHighest = t.card,
    outline = t.outline, outlineVariant = t.outline,
    error = AniVaultColors.Error, onError = Color(0xFF3B0A14)
)

private fun lightScheme(t: Tone): ColorScheme {
    val p = androidx.compose.ui.graphics.lerp(t.primary, Color.Black, 0.38f)
    val tint = { amount: Float -> androidx.compose.ui.graphics.lerp(Color.White, t.primary, amount) }
    return lightColorScheme(
        primary = p, onPrimary = Color.White, secondary = androidx.compose.ui.graphics.lerp(t.secondary, Color.Black, 0.35f),
        tertiary = Color(0xFF0E8F82),
        background = tint(0.05f), onBackground = Color(0xFF191525),
        surface = Color.White, onSurface = Color(0xFF191525),
        surfaceVariant = tint(0.12f), onSurfaceVariant = Color(0xFF5A5670),
        surfaceContainer = tint(0.12f), surfaceContainerHigh = tint(0.16f),
        outline = tint(0.28f), outlineVariant = tint(0.28f), error = Color(0xFFB3261E)
    )
}

/** Frosted glass: translucent panels (blur is not available behind arbitrary content, so it is a tinted-glass look). */
private val GlassScheme = darkColorScheme(
    primary = Color(0xFF9BE7FF), onPrimary = Color(0xFF00344A), secondary = Color(0xFFC4B5FD), onSecondary = Color(0xFF1E1147), tertiary = Color(0xFF5EEAD4),
    background = Color.Transparent, onBackground = Color(0xFFF4FAFF),
    surface = Color(0xB3162B3D), onSurface = Color(0xFFF4FAFF),
    surfaceVariant = Color(0x33FFFFFF), onSurfaceVariant = Color(0xFFC6D6E4),
    surfaceContainerLowest = Color(0x14FFFFFF), surfaceContainerLow = Color(0xE6162B3D), surfaceContainer = Color(0xB3162B3D),
    surfaceContainerHigh = Color(0xF0162B3D), surfaceContainerHighest = Color(0xF0162B3D),
    outline = Color(0x4DFFFFFF), outlineVariant = Color(0x33FFFFFF), error = Color(0xFFFFB4AB), onError = Color(0xFF410002)
)

private val GlassBackdrop = androidx.compose.ui.graphics.Brush.linearGradient(
    listOf(Color(0xFF0F766E), Color(0xFF1E3A8A), Color(0xFF6D28D9))
)

val LocalPalette = androidx.compose.runtime.compositionLocalOf { Palette.ANIVAULT }

/**
 * @param theme "dark" | "light" | "system"
 * @param palette colour palette; null keeps whatever the enclosing theme chose (used by the H-Vault screen)
 */
@Composable
fun AniVaultTheme(
    theme: String = "dark",
    palette: Palette? = null,
    mode: AppMode = AppMode.ANIME,
    content: @Composable () -> Unit
) {
    val pal = palette ?: LocalPalette.current
    val dark = when {
        pal == Palette.GLASS || pal == Palette.AMOLED -> true
        theme == "light" -> false
        theme == "system" -> isSystemInDarkTheme()
        else -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        pal == Palette.GLASS -> GlassScheme
        pal == Palette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> tone(pal).let { if (dark) darkScheme(it) else lightScheme(it) }
    }
    if (mode == AppMode.H_VAULT && pal != Palette.DYNAMIC && pal != Palette.GLASS) scheme = scheme.copy(primary = AniVaultColors.HVaultPrimary)
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides pal) {
        MaterialTheme(colorScheme = scheme) {
            // Always the same wrapper: switching it on and off would rebuild the whole app (and its navigation state)
            // whenever the style changes, which dropped you back on the Home tab.
            androidx.compose.foundation.layout.Box(
                if (pal == Palette.GLASS) androidx.compose.ui.Modifier.fillMaxSize().background(GlassBackdrop) else androidx.compose.ui.Modifier.fillMaxSize()
            ) { content() }
        }
    }
}
