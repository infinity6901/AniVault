package com.anivault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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

private val Dark = darkColorScheme(
    primary = AniVaultColors.Primary, onPrimary = AniVaultColors.OnPrimary,
    secondary = AniVaultColors.Secondary, onSecondary = AniVaultColors.OnPrimary,
    tertiary = AniVaultColors.Progress,
    background = AniVaultColors.Background, onBackground = AniVaultColors.Text,
    surface = AniVaultColors.Bars, onSurface = AniVaultColors.Text,
    surfaceVariant = AniVaultColors.Card, onSurfaceVariant = AniVaultColors.TextMuted,
    surfaceContainer = AniVaultColors.Card, surfaceContainerHigh = AniVaultColors.Card,
    outline = AniVaultColors.Outline, outlineVariant = AniVaultColors.Outline,
    error = AniVaultColors.Error, onError = Color(0xFF3B0A14)
)

private val Light = lightColorScheme(
    primary = Color(0xFF8E3FCB), onPrimary = Color.White,
    secondary = Color(0xFFB0409F), tertiary = Color(0xFF0E8F82),
    background = Color(0xFFF7F5FC), onBackground = Color(0xFF191525),
    surface = Color.White, onSurface = Color(0xFF191525),
    surfaceVariant = Color(0xFFEDE8F5), onSurfaceVariant = Color(0xFF5A5670),
    outline = Color(0xFFD2C8E2), outlineVariant = Color(0xFFD2C8E2),
    error = Color(0xFFB3261E)
)

/**
 * @param theme "dark" | "light" | "system"
 * @param dynamicColor Material You on Android 12+ (falls back to the AniVault palette below that).
 */
@Composable
fun AniVaultTheme(
    theme: String = "dark",
    dynamicColor: Boolean = false,
    mode: AppMode = AppMode.ANIME,
    content: @Composable () -> Unit
) {
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    if (mode == AppMode.H_VAULT && !dynamicColor) scheme = scheme.copy(primary = AniVaultColors.HVaultPrimary)
    MaterialTheme(colorScheme = scheme, content = content)
}
