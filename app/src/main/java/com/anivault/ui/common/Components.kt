package com.anivault.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.anivault.data.local.model.WatchStatus
import com.anivault.ui.theme.LocalLiquidGlass
import com.anivault.ui.theme.TrackGlassOverlay
import com.anivault.ui.theme.color
import com.anivault.ui.theme.dim
import com.anivault.ui.theme.liquidGlassPanel

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun StatusChip(status: WatchStatus, modifier: Modifier = Modifier) {
    val c = status.color()
    Surface(modifier, shape = RoundedCornerShape(50), color = c.copy(alpha = 0.18f), border = BorderStroke(1.dp, c.copy(alpha = 0.55f))) {
        Text(status.label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Poster with a neutral placeholder; the image is decorative so it carries no description of its own.
 * When [allowDiskCache] is false (used in H-Vault), the image is kept only in memory and never written to disk.
 */
@Composable
fun Poster(
    url: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    allowDiskCache: Boolean = true
) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNotBlank()) {
            val ctx = LocalContext.current
            val model: Any = if (allowDiskCache) url else ImageRequest.Builder(ctx)
                .data(url)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun AniCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalLiquidGlass.current
    val shape = RoundedCornerShape(16.dp)
    if (glass.enabled) {
        val glassMod = modifier.liquidGlassPanel(config = glass, shape = shape, cornerRadiusDp = 16f)
        val transparentColors = CardDefaults.cardColors(containerColor = Color.Transparent)
        if (onClick != null) {
            Card(onClick = onClick, modifier = glassMod, shape = shape, colors = transparentColors, content = content)
        } else {
            Card(modifier = glassMod, shape = shape, colors = transparentColors, content = content)
        }
    } else {
        val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
        else Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: ImageVector? = null, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.dim, modifier = Modifier.size(40.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(message, color = MaterialTheme.colorScheme.dim, fontSize = 14.sp)
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Text(message, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        if (!detail.isNullOrBlank()) Text(detail, color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading" })
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    TrackGlassOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SettingNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, highlight: Boolean = false) {
    AniCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, color = MaterialTheme.colorScheme.dim, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.dim)
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.dim, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Single-choice row group (radio), used for theme / sort / policy pickers. */
@Composable
fun <T> ChoiceGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, description: ((T) -> String?)? = null) {
    Column {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelect(value) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, color = MaterialTheme.colorScheme.onSurface)
                    description?.invoke(value)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim) }
                }
            }
        }
    }
}

@Composable
fun GroupLabel(text: String) {
    Text(text.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.dim) {
    val glass = LocalLiquidGlass.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (glass.enabled) BorderStroke(0.75.dp, Color.White.copy(alpha = 0.22f * glass.specularIntensity)) else null
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun formatDuration(minutes: Long): String = when {
    minutes <= 0 -> "0h"
    minutes < 60 -> "${minutes}m"
    minutes < 60 * 48 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes / (60 * 24)}d ${(minutes / 60) % 24}h"
}

/** A slimmer search field (about 44dp instead of 56dp) so lists get more room. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompactSearch(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val glass = LocalLiquidGlass.current
    val pillShape = RoundedCornerShape(22.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        decorationBox = { inner ->
            val rowMod = if (glass.enabled) {
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .liquidGlassPanel(config = glass, shape = pillShape, cornerRadiusDp = 22f)
                    .padding(horizontal = 14.dp)
            } else {
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), pillShape)
                    .padding(horizontal = 14.dp)
            }
            Row(
                rowMod,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    inner()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, Modifier.size(32.dp)) {
                    Icon(Icons.Default.Clear, "Clear search", Modifier.size(18.dp))
                }
                trailing?.invoke()
            }
        }
    )
}

/**
 * Bottom content padding for top-level tab screens (Home, Library, Discover, Settings)
 * so the last item and ScrollToTop FAB always clear the bottom navigation bar
 * (both when the floating Liquid Glass capsule dock is active and in standard bar mode).
 */
@Composable
fun bottomTabContentPadding(extra: Dp = 28.dp): Dp {
    val glass = LocalLiquidGlass.current
    val sysBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return if (glass.enabled && glass.floatingNav) {
        sysBottom + 104.dp + extra
    } else {
        extra
    }
}

/** Appears once the list is scrolled past a few rows; jumps back to the search and filters. */
@Composable
fun BoxScope.ScrollToTop(visible: Boolean, inTabScreen: Boolean = true, onClick: () -> Unit) {
    val bottomPad = if (inTabScreen) bottomTabContentPadding(16.dp) else 16.dp
    androidx.compose.animation.AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomEnd).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPad)
    ) {
        SmallFloatingActionButton(onClick = onClick) { Icon(Icons.Default.KeyboardArrowUp, "Back to search and filters") }
    }
}

/** Lets an item inside a padded lazy grid draw edge to edge (cancels the grid's 16dp side padding). */
val fullBleed: androidx.compose.ui.layout.MeasureScope.(androidx.compose.ui.layout.Measurable, androidx.compose.ui.unit.Constraints) -> androidx.compose.ui.layout.MeasureResult = { m, c ->
    val extra = 32.dp.roundToPx()
    val pl = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, pl.height) { pl.place(-16.dp.roundToPx(), 0) }
}
