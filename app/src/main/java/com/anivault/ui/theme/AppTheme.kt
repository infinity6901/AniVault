package com.anivault.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette ───────────────────────────────────────────────────────────────
object AniVaultColors {
    // Backgrounds
    val Background   = Color(0xFF0F1419)
    val Surface      = Color(0xFF1A1F2E)
    val SurfaceGlass = Color(0x331A1F2E)   // 20 % alpha — glass cards

    // Anime mode (Teal / Cyan)
    val AnimePrimary   = Color(0xFF14B8A6)
    val AnimeOnPrimary = Color(0xFF003733)

    // H-Vault mode (Pink / Purple)
    val HVaultPrimary   = Color(0xFFF472B6)
    val HVaultOnPrimary = Color(0xFF4A0026)

    // Text
    val OnSurface    = Color(0xFFE2E8F0)
    val OnSurfaceMid = Color(0xFF94A3B8)
    val OnSurfaceDim = Color(0xFF4A5568)

    // Divider
    val Divider = Color(0xFF2D3748)

    // Status badge colours
    val StatusWatching  = Color(0xFF3B82F6)
    val StatusCompleted = Color(0xFF22C55E)
    val StatusPlanTo    = Color(0xFFA855F7)
    val StatusDropped   = Color(0xFFEF4444)
}

enum class AppMode { ANIME, H_VAULT }

@Composable
fun AniVaultTheme(
    currentMode: AppMode = AppMode.ANIME,
    content: @Composable () -> Unit
) {
    val accent = when (currentMode) {
        AppMode.ANIME   -> AniVaultColors.AnimePrimary
        AppMode.H_VAULT -> AniVaultColors.HVaultPrimary
    }

    val colorScheme = darkColorScheme(
        primary         = accent,
        onPrimary       = Color.White,
        background      = AniVaultColors.Background,
        surface         = AniVaultColors.Surface,
        onBackground    = AniVaultColors.OnSurface,
        onSurface       = AniVaultColors.OnSurface,
        surfaceVariant  = AniVaultColors.SurfaceGlass,
        outline         = AniVaultColors.Divider
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content
    )
}
