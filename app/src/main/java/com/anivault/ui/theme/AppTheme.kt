package com.anivault.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    LIQUID_GLASS("liquid_glass", "Liquid Glass", "Refractive 3D glass with living orbs, specular rims & custom tint.", listOf(Color(0xFF060D1D), Color(0xFF7CE8FF), Color(0xFFC4B5FD))),
    DYNAMIC("dynamic", "Material You", "Colors from your wallpaper (Android 12+).", listOf(Color(0xFF4A5568), Color(0xFF8AB4F8), Color(0xFFCBA6F7))),
    GLASS("glass", "Frosted glass", "Translucent panels over a soft gradient.", listOf(Color(0xFF0F766E), Color(0xFF7DD3FC), Color(0xFFC4B5FD))),
    AMOLED("amoled", "AMOLED black", "True black for OLED screens.", listOf(Color(0xFF000000), Color(0xFFD0BCFF), Color(0xFF9AD0F5))),
    MIDNIGHT("midnight", "Midnight blue", "Deep navy with a cool blue accent.", listOf(Color(0xFF071225), Color(0xFF5AA9FF), Color(0xFF7CE0FF))),
    FOREST("forest", "Forest", "Dark green with a fresh mint accent.", listOf(Color(0xFF08130E), Color(0xFF4ADE80), Color(0xFFA3E635))),
    SUNSET("sunset", "Sunset", "Warm charcoal with orange and pink.", listOf(Color(0xFF150E0C), Color(0xFFFF8A4C), Color(0xFFFF5D8F))),
    SAKURA("sakura", "Sakura", "Soft plum with cherry-blossom pink.", listOf(Color(0xFF180F16), Color(0xFFF9A8D4), Color(0xFFC4B5FD))),
    CYBERPUNK("cyberpunk", "Cyberpunk Neon", "Deep violet with electric cyan and neon magenta.", listOf(Color(0xFF090614), Color(0xFF00F0FF), Color(0xFFFF2A85))),
    MATCHA("matcha", "Matcha Paper", "Warm cream and sage paper for low-glare reading.", listOf(Color(0xFFF6F3E8), Color(0xFF3E6B48), Color(0xFF8C6239))),
    CRIMSON("crimson", "Crimson Eclipse", "Obsidian black with crimson red and warm gold.", listOf(Color(0xFF0C090A), Color(0xFFE11D48), Color(0xFFF59E0B)));

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
    Palette.CYBERPUNK -> Tone(Color(0xFF090614), Color(0xFF120C24), Color(0xFF1B1236), Color(0xFF352363), Color(0xFF00F0FF), Color(0xFF00262B), Color(0xFFFF2A85), Color(0xFFFCEE09))
    Palette.MATCHA -> Tone(Color(0xFF111A14), Color(0xFF19261E), Color(0xFF213227), Color(0xFF334D3C), Color(0xFF74C69D), Color(0xFF082616), Color(0xFFD4A373), Color(0xFF95D5B2))
    Palette.CRIMSON -> Tone(Color(0xFF0C090A), Color(0xFF171113), Color(0xFF22181B), Color(0xFF3D272D), Color(0xFFFB7185), Color(0xFF3B0510), Color(0xFFFBBF24), Color(0xFFF43F5E))
    else -> Tone(AniVaultColors.Background, AniVaultColors.Bars, AniVaultColors.Card, AniVaultColors.Outline, AniVaultColors.Primary, AniVaultColors.OnPrimary, AniVaultColors.Secondary, AniVaultColors.Progress)
}

private val MatchaPaperScheme = lightColorScheme(
    primary = Color(0xFF2D6A4F), onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8C6239), onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF40916C),
    background = Color(0xFFF6F3E8), onBackground = Color(0xFF1F2421),
    surface = Color(0xFFEDE8D8), onSurface = Color(0xFF1F2421),
    surfaceVariant = Color(0xFFE2DCC7), onSurfaceVariant = Color(0xFF525B54),
    surfaceContainer = Color(0xFFEDE8D8), surfaceContainerHigh = Color(0xFFE5DFCA),
    outline = Color(0xFFC7BFA8), outlineVariant = Color(0xFFD5CEB8),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF)
)

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
    val p = lerp(t.primary, Color.Black, 0.38f)
    val tint = { amount: Float -> lerp(Color.White, t.primary, amount) }
    return lightColorScheme(
        primary = p, onPrimary = Color.White, secondary = lerp(t.secondary, Color.Black, 0.35f),
        tertiary = Color(0xFF0E8F82),
        background = tint(0.05f), onBackground = Color(0xFF191525),
        surface = Color.White, onSurface = Color(0xFF191525),
        surfaceVariant = tint(0.12f), onSurfaceVariant = Color(0xFF5A5670),
        surfaceContainer = tint(0.12f), surfaceContainerHigh = tint(0.16f),
        outline = tint(0.28f), outlineVariant = tint(0.28f), error = Color(0xFFB3261E)
    )
}

/** Builds a dynamic refractive ColorScheme for Liquid Glass & Frosted Glass based on [LiquidGlassConfig]. */
private fun liquidGlassScheme(cfg: LiquidGlassConfig): ColorScheme {
    val t = cfg.tint
    val surfaceBase = lerp(t.baseStart, t.primary, 0.14f)
    val panelColor = surfaceBase.copy(alpha = cfg.panelAlpha.coerceIn(0.10f, 0.70f))
    val barColor = surfaceBase.copy(alpha = cfg.barAlpha.coerceIn(0.20f, 0.85f))
    val sheetColor = lerp(t.baseStart, t.baseEnd, 0.5f).copy(alpha = (cfg.barAlpha + 0.12f).coerceIn(0.65f, 0.94f))
    val rimAlpha = (0.18f + 0.38f * cfg.specularIntensity).coerceIn(0.15f, 0.60f)
    return darkColorScheme(
        primary = t.primary,
        onPrimary = lerp(t.baseStart, Color.Black, 0.35f),
        secondary = t.secondary,
        onSecondary = lerp(t.baseStart, Color.Black, 0.35f),
        tertiary = t.tertiary,
        background = Color.Transparent,
        onBackground = Color(0xFFF6FAFF),
        surface = panelColor,
        onSurface = Color(0xFFF6FAFF),
        surfaceVariant = Color.White.copy(alpha = (cfg.panelAlpha * 0.65f).coerceIn(0.08f, 0.35f)),
        onSurfaceVariant = Color(0xFFD2E2F2),
        surfaceContainerLowest = Color.White.copy(alpha = 0.08f),
        surfaceContainerLow = barColor,
        surfaceContainer = barColor,
        surfaceContainerHigh = sheetColor,
        surfaceContainerHighest = sheetColor,
        outline = Color.White.copy(alpha = rimAlpha),
        outlineVariant = t.primary.copy(alpha = rimAlpha * 0.7f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF410002)
    )
}

val LocalPalette = androidx.compose.runtime.compositionLocalOf { Palette.ANIVAULT }

/**
 * @param theme "dark" | "light" | "system"
 * @param palette colour palette; null keeps whatever the enclosing theme chose (used by the H-Vault screen)
 * @param liquidGlass customizable Liquid Glass settings
 */
@Composable
fun AniVaultTheme(
    theme: String = "dark",
    palette: Palette? = null,
    liquidGlass: LiquidGlassConfig? = null,
    mode: AppMode = AppMode.ANIME,
    content: @Composable () -> Unit
) {
    val pal = palette ?: LocalPalette.current
    val parentGlass = LocalLiquidGlass.current
    val isGlassPalette = pal == Palette.LIQUID_GLASS || pal == Palette.GLASS
    val effectiveGlass = (liquidGlass ?: parentGlass).copy(enabled = isGlassPalette)

    val dark = when {
        isGlassPalette || pal == Palette.AMOLED || pal == Palette.CYBERPUNK || pal == Palette.CRIMSON -> true
        pal == Palette.MATCHA && theme != "dark" -> false
        theme == "light" -> false
        theme == "system" -> isSystemInDarkTheme()
        else -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        isGlassPalette -> liquidGlassScheme(effectiveGlass)
        pal == Palette.MATCHA && !dark -> MatchaPaperScheme
        pal == Palette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> tone(pal).let { if (dark) darkScheme(it) else lightScheme(it) }
    }
    if (mode == AppMode.H_VAULT && pal != Palette.DYNAMIC && !isGlassPalette) {
        scheme = scheme.copy(primary = AniVaultColors.HVaultPrimary)
    }
    CompositionLocalProvider(
        LocalPalette provides pal,
        LocalLiquidGlass provides effectiveGlass
    ) {
        MaterialTheme(colorScheme = scheme) {
            // Keep the root Box stable so switching palettes never drops navigation state.
            Box(Modifier.fillMaxSize()) {
                if (isGlassPalette) {
                    LiquidGlassBackdrop(config = effectiveGlass)
                }
                content()
            }
        }
    }
}
