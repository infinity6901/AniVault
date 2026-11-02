package com.anivault.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anivault.data.local.model.WatchStatus
import com.anivault.ui.dashboard.DisplayEntry
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.AppMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailSheet(
    entry: DisplayEntry,
    mode: AppMode,
    accent: Color,
    onDismiss: () -> Unit,
    viewModel: EntryDetailViewModel = hiltViewModel()
) {
    val state      by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Load full entry from DB on first composition (auto-populates synopsis, genres, etc.)
    LaunchedEffect(entry.id) { viewModel.loadById(entry.id, mode) }

    LaunchedEffect(state.isSaved)   { if (state.isSaved)   { viewModel.resetFlags(); onDismiss() } }
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) { viewModel.resetFlags(); onDismiss() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AniVaultColors.Surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AniVaultColors.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment     = Alignment.Top
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(entry.imageUrl).crossfade(true).build(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(width = 80.dp, height = 115.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AniVaultColors.Background)
                )
                Column(
                    Modifier.weight(1f).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        entry.title,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 15.sp,
                        color      = AniVaultColors.OnSurface,
                        maxLines   = 3
                    )
                    // Metadata chips row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        if (mode == AppMode.ANIME) {
                            val yearSeason = listOfNotNull(
                                state.season.ifBlank { null },
                                state.year?.toString()
                            ).joinToString(" · ")
                            if (yearSeason.isNotBlank()) MetaChip(yearSeason, accent)
                            if (state.totalEpisodes > 0) MetaChip("${state.totalEpisodes} eps", accent)
                            if (state.durationMinutes > 0) MetaChip("${state.durationMinutes} min/ep", accent)
                        } else {
                            state.studio?.let { MetaChip(it, accent) }
                            if (state.totalEpisodes > 0) MetaChip("${state.totalEpisodes} eps", accent)
                            if (state.durationMinutes > 0) MetaChip("${state.durationMinutes} min/ep", accent)
                            if (state.isCensored) MetaChip("Censored", AniVaultColors.StatusDropped)
                        }
                    }
                }
            }

            // ── Synopsis ───────────────────────────────────────────────────
            if (state.synopsis.isNotBlank()) {
                HorizontalDivider(color = AniVaultColors.Divider)
                SectionLabel("Synopsis", accent)
                var expanded by remember { mutableStateOf(false) }
                Text(
                    text     = state.synopsis,
                    fontSize = 12.sp,
                    color    = AniVaultColors.OnSurfaceMid,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                )
                if (state.synopsis.length > 200) {
                    Text(
                        text     = if (expanded) "Show less" else "Show more",
                        fontSize = 11.sp,
                        color    = accent,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }

            // ── Genres / Tags ──────────────────────────────────────────────
            val chips = if (mode == AppMode.ANIME) state.genres else state.tags
            if (chips.isNotEmpty()) {
                HorizontalDivider(color = AniVaultColors.Divider)
                SectionLabel(if (mode == AppMode.ANIME) "Genres" else "Tags", accent)
                FlowRow(chips, accent)
            }

            HorizontalDivider(color = AniVaultColors.Divider)

            // ── Status ─────────────────────────────────────────────────────
            SectionLabel("Watch Status", accent)
            StatusChipRow(current = state.status, accent = accent, onSelect = viewModel::setStatus)

            // ── Episodes ───────────────────────────────────────────────────
            SectionLabel("Episodes", accent)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                EpisodeCounter(
                    label       = "Watched",
                    value       = state.watchedEpisodes,
                    onDecrement = { viewModel.setWatched(state.watchedEpisodes - 1) },
                    onIncrement = { viewModel.setWatched(state.watchedEpisodes + 1) },
                    onEdit      = viewModel::setWatched,
                    accent      = accent,
                    modifier    = Modifier.weight(1f)
                )
                Text("/", color = AniVaultColors.OnSurfaceMid, fontSize = 18.sp)
                EpisodeCounter(
                    label       = "Total",
                    value       = state.totalEpisodes,
                    onDecrement = { viewModel.setTotal(state.totalEpisodes - 1) },
                    onIncrement = { viewModel.setTotal(state.totalEpisodes + 1) },
                    onEdit      = viewModel::setTotal,
                    accent      = accent,
                    modifier    = Modifier.weight(1f)
                )
            }
            if (state.totalEpisodes > 0) {
                val progress = (state.watchedEpisodes.toFloat() / state.totalEpisodes).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress   = { progress },
                    modifier   = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color      = accent,
                    trackColor = AniVaultColors.Divider
                )
            }

            // ── Duration ───────────────────────────────────────────────────
            SectionLabel("Duration per Episode (min)", accent)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                EpisodeCounter(
                    label       = "Minutes",
                    value       = state.durationMinutes,
                    onDecrement = { viewModel.setDuration(state.durationMinutes - 1) },
                    onIncrement = { viewModel.setDuration(state.durationMinutes + 1) },
                    onEdit      = viewModel::setDuration,
                    accent      = accent,
                    modifier    = Modifier.weight(1f)
                )
                Spacer(Modifier.weight(1f))
            }

            // ── Rating ─────────────────────────────────────────────────────
            SectionLabel(
                "Rating  ${if (state.rating > 0) "%.1f / 10".format(state.rating) else "— / 10"}",
                accent
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Star, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Slider(
                    value         = state.rating,
                    onValueChange = viewModel::setRating,
                    valueRange    = 0f..10f,
                    steps         = 19,
                    modifier      = Modifier.weight(1f),
                    colors        = SliderDefaults.colors(
                        thumbColor         = accent,
                        activeTrackColor   = accent,
                        inactiveTrackColor = AniVaultColors.Divider
                    )
                )
                Text("%.1f".format(state.rating), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            // ── Notes ──────────────────────────────────────────────────────
            SectionLabel("Notes", accent)
            OutlinedTextField(
                value         = state.notes,
                onValueChange = viewModel::setNotes,
                modifier      = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                placeholder   = { Text("Personal notes…", color = AniVaultColors.OnSurfaceMid) },
                minLines      = 3,
                maxLines      = 6,
                shape         = RoundedCornerShape(10.dp),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accent,
                    unfocusedBorderColor = accent.copy(alpha = 0.3f),
                    focusedTextColor     = AniVaultColors.OnSurface,
                    unfocusedTextColor   = AniVaultColors.OnSurface
                )
            )

            // ── Actions ────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick  = { showDeleteConfirm = true },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete", fontSize = 13.sp)
                }
                Button(
                    onClick  = { viewModel.save() },
                    modifier = Modifier.weight(2f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Save Changes", fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor   = AniVaultColors.Surface,
            title  = { Text("Delete Entry?", color = AniVaultColors.OnSurface) },
            text   = {
                Text(
                    "\"${entry.title}\" will be permanently removed from your vault.",
                    color = AniVaultColors.OnSurfaceMid
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; viewModel.delete() },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = AniVaultColors.OnSurfaceMid)
                }
            }
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String, accent: Color) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
}

@Composable
private fun MetaChip(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.13f))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 10.sp, color = accent, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FlowRow(items: List<String>, accent: Color) {
    val rows = items.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(accent.copy(alpha = 0.10f))
                            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(tag, fontSize = 11.sp, color = AniVaultColors.OnSurfaceMid)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChipRow(current: WatchStatus, accent: Color, onSelect: (WatchStatus) -> Unit) {
    val statuses = listOf(
        WatchStatus.WATCHING      to "Watching",
        WatchStatus.COMPLETED     to "Completed",
        WatchStatus.PLAN_TO_WATCH to "Plan",
        WatchStatus.DROPPED       to "Dropped"
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        statuses.forEach { (status, label) ->
            val isSelected = status == current
            val chipColor  = statusColor(status)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) chipColor.copy(alpha = 0.25f) else AniVaultColors.Background)
                    .border(1.5.dp, if (isSelected) chipColor else AniVaultColors.Divider, RoundedCornerShape(8.dp))
                    .clickable { onSelect(status) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize   = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color      = if (isSelected) chipColor else AniVaultColors.OnSurfaceMid
                )
            }
        }
    }
}

private fun statusColor(status: WatchStatus): Color = when (status) {
    WatchStatus.WATCHING      -> AniVaultColors.StatusWatching
    WatchStatus.COMPLETED     -> AniVaultColors.StatusCompleted
    WatchStatus.PLAN_TO_WATCH -> AniVaultColors.StatusPlanTo
    WatchStatus.DROPPED       -> AniVaultColors.StatusDropped
}

@Composable
private fun EpisodeCounter(
    label: String, value: Int,
    onDecrement: () -> Unit, onIncrement: () -> Unit, onEdit: (Int) -> Unit,
    accent: Color, modifier: Modifier = Modifier
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = AniVaultColors.OnSurfaceMid)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledIconButton(
                onClick  = { onDecrement(); textValue = (value - 1).coerceAtLeast(0).toString() },
                modifier = Modifier.size(30.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(containerColor = accent.copy(alpha = 0.18f))
            ) { Text("−", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            OutlinedTextField(
                value         = textValue,
                onValueChange = { s -> textValue = s; s.toIntOrNull()?.let { onEdit(it) } },
                modifier      = Modifier.width(56.dp),
                singleLine    = true,
                textStyle     = LocalTextStyle.current.copy(
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AniVaultColors.OnSurface
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape  = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accent,
                    unfocusedBorderColor = accent.copy(alpha = 0.3f),
                    focusedTextColor     = AniVaultColors.OnSurface,
                    unfocusedTextColor   = AniVaultColors.OnSurface
                )
            )

            FilledIconButton(
                onClick  = { onIncrement(); textValue = (value + 1).toString() },
                modifier = Modifier.size(30.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(containerColor = accent.copy(alpha = 0.18f))
            ) { Text("+", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
