package com.anivault.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.anivault.core.AppPrefs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tint presets for the customizable Liquid Glass theme and homescreen widgets.
 */
enum class LiquidGlassTint(
    val key: String,
    val label: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val orb1: Color,
    val orb2: Color,
    val orb3: Color,
    val baseStart: Color,
    val baseEnd: Color
) {
    AURORA(
        key = "aurora",
        label = "Aurora Prism",
        primary = Color(0xFF7CE8FF),
        secondary = Color(0xFFC4B5FD),
        tertiary = Color(0xFF5EEAD4),
        orb1 = Color(0xFF00D2FF),
        orb2 = Color(0xFF9333EA),
        orb3 = Color(0xFF14B8A6),
        baseStart = Color(0xFF060D1D),
        baseEnd = Color(0xFF120B29)
    ),
    CRYSTAL(
        key = "crystal",
        label = "Crystal Ice",
        primary = Color(0xFFE0F2FE),
        secondary = Color(0xFF93C5FD),
        tertiary = Color(0xFFA5F3FC),
        orb1 = Color(0xFF38BDF8),
        orb2 = Color(0xFF818CF8),
        orb3 = Color(0xFFE2E8F0),
        baseStart = Color(0xFF07111E),
        baseEnd = Color(0xFF0F1E36)
    ),
    SAKURA(
        key = "sakura",
        label = "Rose Quartz",
        primary = Color(0xFFFBCFE8),
        secondary = Color(0xFFD8B4FE),
        tertiary = Color(0xFFFDE68A),
        orb1 = Color(0xFFF472B6),
        orb2 = Color(0xFFA855F7),
        orb3 = Color(0xFFFB7185),
        baseStart = Color(0xFF170916),
        baseEnd = Color(0xFF230D26)
    ),
    EMERALD(
        key = "emerald",
        label = "Emerald Lagoon",
        primary = Color(0xFF6EE7B7),
        secondary = Color(0xFF67E8F9),
        tertiary = Color(0xFFA7F3D0),
        orb1 = Color(0xFF10B981),
        orb2 = Color(0xFF06B6D4),
        orb3 = Color(0xFF84CC16),
        baseStart = Color(0xFF041410),
        baseEnd = Color(0xFF072329)
    ),
    AMBER(
        key = "amber",
        label = "Solar Amber",
        primary = Color(0xFFFDE68A),
        secondary = Color(0xFFFDA4AF),
        tertiary = Color(0xFFFB923C),
        orb1 = Color(0xFFF59E0B),
        orb2 = Color(0xFFF43F5E),
        orb3 = Color(0xFFEC4899),
        baseStart = Color(0xFF170D08),
        baseEnd = Color(0xFF281117)
    ),
    OBSIDIAN(
        key = "obsidian",
        label = "Smoked Obsidian",
        primary = Color(0xFFD8B4FE),
        secondary = Color(0xFF67E8F9),
        tertiary = Color(0xFF94A3B8),
        orb1 = Color(0xFF4F46E5),
        orb2 = Color(0xFF0891B2),
        orb3 = Color(0xFF6D28D9),
        baseStart = Color(0xFF030408),
        baseEnd = Color(0xFF090C15)
    );

    companion object {
        fun parse(key: String?): LiquidGlassTint = values().firstOrNull { it.key == key } ?: AURORA
    }
}

/**
 * Immutable snapshot of the user's Liquid Glass customization settings.
 */
@Immutable
data class LiquidGlassConfig(
    val enabled: Boolean = false,
    val tint: LiquidGlassTint = LiquidGlassTint.AURORA,
    val opacityPct: Int = 24,
    val specularPct: Int = 78,
    val blurDp: Int = 22,
    val animated: Boolean = true,
    val floatingNav: Boolean = true,
    val widgetEnabled: Boolean = true,
    val widgetOpacityPct: Int = 42,
    val widgetMatchCover: Boolean = true
) {
    val panelAlpha: Float get() = (opacityPct.coerceIn(10, 65) / 100f)
    val barAlpha: Float get() = (panelAlpha + 0.14f).coerceAtMost(0.82f)
    val specularIntensity: Float get() = (specularPct.coerceIn(0, 100) / 100f)

    companion object {
        fun fromPrefs(prefs: AppPrefs, palette: Palette): LiquidGlassConfig = LiquidGlassConfig(
            enabled = palette == Palette.LIQUID_GLASS || palette == Palette.GLASS,
            tint = LiquidGlassTint.parse(prefs.glassTint),
            opacityPct = prefs.glassOpacity,
            specularPct = prefs.glassSpecular,
            blurDp = prefs.glassBlur,
            animated = prefs.glassAnimated,
            floatingNav = prefs.glassFloatingNav,
            widgetEnabled = prefs.widgetGlassEnabled,
            widgetOpacityPct = prefs.widgetGlassOpacity,
            widgetMatchCover = prefs.widgetGlassMatchCover
        )
    }
}

val LocalLiquidGlass = compositionLocalOf { LiquidGlassConfig() }

/**
 * Renders the living multi-orb refractive backdrop behind the entire app when Liquid Glass is active.
 */
@Composable
fun LiquidGlassBackdrop(config: LiquidGlassConfig, modifier: Modifier = Modifier) {
    val phase = if (config.animated) {
        val transition = rememberInfiniteTransition(label = "liquid_glass_orbs")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 22_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orb_phase"
        )
        p
    } else {
        0.85f
    }

    val tint = config.tint
    val blurMod = if (config.blurDp > 0) Modifier.blur(config.blurDp.dp) else Modifier

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().then(blurMod)) {
            val w = size.width
            val h = size.height
            val minDim = size.minDimension.coerceAtLeast(1f)

            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(tint.baseStart, tint.baseEnd, tint.baseStart),
                    start = Offset.Zero,
                    end = Offset(w, h)
                )
            )

            val c1 = Offset(
                x = w * (0.22f + 0.14f * cos(phase)),
                y = h * (0.20f + 0.10f * sin(phase))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.orb1.copy(alpha = 0.42f), Color.Transparent),
                    center = c1,
                    radius = minDim * 0.72f
                ),
                radius = minDim * 0.72f,
                center = c1
            )

            val c2 = Offset(
                x = w * (0.80f + 0.12f * sin(phase * 0.8f + 1.2f)),
                y = h * (0.52f + 0.14f * cos(phase * 0.8f + 0.6f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.orb2.copy(alpha = 0.38f), Color.Transparent),
                    center = c2,
                    radius = minDim * 0.78f
                ),
                radius = minDim * 0.78f,
                center = c2
            )

            val c3 = Offset(
                x = w * (0.35f + 0.16f * sin(phase * 1.1f + 2.5f)),
                y = h * (0.84f + 0.08f * cos(phase * 1.1f + 2.0f))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.orb3.copy(alpha = 0.32f), Color.Transparent),
                    center = c3,
                    radius = minDim * 0.68f
                ),
                radius = minDim * 0.68f,
                center = c3
            )
        }

        // Subtle diagonal caustic light-sheen overlay across the viewport
        Canvas(Modifier.fillMaxSize()) {
            val spec = config.specularIntensity
            if (spec > 0.05f) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.045f * spec),
                            Color.Transparent,
                            tint.primary.copy(alpha = 0.03f * spec),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height * 0.65f)
                    )
                )
            }
        }
    }
}

/**
 * Applies a true Liquid Glass refractive surface with:
 * 1. Translucent frosted glass fill tinted by [config.tint] (or [accentOverride]).
 * 2. Diagonal glass surface reflection sheen.
 * 3. Top-edge inner caustic highlight line.
 * 4. Directional 3D prismatic specular rim border.
 */
fun Modifier.liquidGlassPanel(
    config: LiquidGlassConfig,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    cornerRadiusDp: Float = 16f,
    accentOverride: Color? = null,
    elevated: Boolean = false
): Modifier = this.clip(shape).drawWithCache {
    val cr = CornerRadius(cornerRadiusDp.dp.toPx(), cornerRadiusDp.dp.toPx())
    val strokePx = (if (elevated) 1.5.dp else 1.15.dp).toPx()
    val accent = accentOverride ?: config.tint.primary
    val secondary = config.tint.secondary
    val baseAlpha = if (elevated) config.barAlpha else config.panelAlpha
    val spec = config.specularIntensity

    val fillStart = lerp(config.tint.baseStart, accent, 0.16f).copy(alpha = (baseAlpha * 0.92f).coerceIn(0.08f, 0.88f))
    val fillEnd = lerp(config.tint.baseEnd, Color.White, 0.08f).copy(alpha = (baseAlpha * 0.78f).coerceIn(0.06f, 0.84f))

    val fillBrush = Brush.linearGradient(
        colors = listOf(fillStart, fillEnd),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )

    val sheenBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = (0.18f * spec).coerceIn(0f, 0.30f)),
            Color.White.copy(alpha = (0.04f * spec).coerceIn(0f, 0.12f)),
            Color.Transparent,
            accent.copy(alpha = (0.07f * spec).coerceIn(0f, 0.18f))
        ),
        start = Offset.Zero,
        end = Offset(size.width * 0.85f, size.height)
    )

    val rimBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = (0.72f * spec).coerceIn(0.10f, 0.85f)),
            accent.copy(alpha = (0.48f * spec).coerceIn(0.08f, 0.65f)),
            Color.White.copy(alpha = (0.12f * spec).coerceIn(0.04f, 0.25f)),
            secondary.copy(alpha = (0.42f * spec).coerceIn(0.06f, 0.55f))
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )

    val topCausticBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color.White.copy(alpha = (0.52f * spec).coerceIn(0f, 0.70f)),
            accent.copy(alpha = (0.38f * spec).coerceIn(0f, 0.55f)),
            Color.Transparent
        )
    )

    onDrawBehind {
        // 1. Tinted translucent glass body
        drawRoundRect(brush = fillBrush, cornerRadius = cr)
        // 2. Diagonal refractive surface sheen
        if (spec > 0.02f) {
            drawRoundRect(brush = sheenBrush, cornerRadius = cr)
        }
        // 3. Top inner caustic highlight
        if (spec > 0.05f && size.width > 24f) {
            val inset = cornerRadiusDp.dp.toPx() * 0.65f
            drawLine(
                brush = topCausticBrush,
                start = Offset(inset, strokePx * 1.1f),
                end = Offset((size.width - inset).coerceAtLeast(inset), strokePx * 1.1f),
                strokeWidth = 1.25.dp.toPx()
            )
        }
        // 4. Directional 3D specular rim border
        drawRoundRect(
            brush = rimBrush,
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = Size(
                (size.width - strokePx).coerceAtLeast(0f),
                (size.height - strokePx).coerceAtLeast(0f)
            ),
            cornerRadius = cr,
            style = Stroke(width = strokePx)
        )
    }
}
