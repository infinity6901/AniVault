package com.anivault.ui.dashboard

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anivault.data.local.model.WatchStatus
import com.anivault.ui.detail.EntryDetailSheet
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.AniVaultTheme
import com.anivault.ui.theme.AppMode

// ═══════════════════════════════════════════════════════════════════════════
// Root – biometric + FLAG_SECURE orchestration
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun DashboardRoot(
    onSearchClick: (AppMode) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context  = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(uiState.mode) {
        activity?.window?.let { win ->
            if (uiState.mode == AppMode.H_VAULT)
                win.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val biometricPrompt = remember(executor) {
        val fa = context as FragmentActivity
        BiometricPrompt(fa, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = viewModel.onBiometricSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) = viewModel.onBiometricFailure()
            // A failed biometric match is non-terminal. Keep the pending mode
            // so a later successful scan in the same prompt can unlock H-Vault.
            override fun onAuthenticationFailed() = Unit
        })
    }
    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock H-Vault")
            .setSubtitle("Authenticate to access H-Vault content")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ).build()
    }

    // ── Import launcher ──────────────────────────────────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importJson(it) } }

    AniVaultTheme(currentMode = uiState.mode) {
        DashboardScreen(
            uiState        = uiState,
            onModeToggle   = { target ->
                val needsBio = viewModel.requestModeSwitch(target)
                if (needsBio) biometricPrompt.authenticate(promptInfo)
            },
            onFilterSelect  = viewModel::setFilter,
            onSortSelect    = viewModel::setSortOrder,
            onLockHVault    = viewModel::lockHVault,
            onSearchClick   = onSearchClick,
            onSettingsClick = onSettingsClick,
            onToggleSelect  = viewModel::toggleSelect,
            onLongPress     = viewModel::enterMultiSelectMode,
            onSelectAll     = viewModel::selectAll,
            onClearSelect   = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
            onStatusSelected = viewModel::setStatusForSelected,
            onExportPdf     = viewModel::exportPdf,
            onExportDocx    = viewModel::exportDocx,
            onExportJson    = viewModel::exportJson,
            onImportJson    = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            onConsumeExportMsg = viewModel::consumeExportMessage
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Stateless Dashboard Screen
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onModeToggle: (AppMode) -> Unit,
    onFilterSelect: (WatchStatus?) -> Unit,
    onSortSelect: (SortOrder) -> Unit,
    onLockHVault: () -> Unit,
    onSearchClick: (AppMode) -> Unit,
    onSettingsClick: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelect: () -> Unit,
    onDeleteSelected: () -> Unit,
    onStatusSelected: (WatchStatus) -> Unit,
    onExportPdf: () -> Unit,
    onExportDocx: () -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit,
    onConsumeExportMsg: () -> Unit
) {
    val accent   = MaterialTheme.colorScheme.primary
    val snackbar = remember { SnackbarHostState() }

    // Selected entry for detail sheet
    var detailEntry by remember { mutableStateOf<DisplayEntry?>(null) }

    // Sort dialog
    var showSortDialog by remember { mutableStateOf(false) }

    // Bulk-status dialog
    var showBulkStatusDialog by remember { mutableStateOf(false) }

    // Export snackbar
    LaunchedEffect(uiState.exportMessage) {
        uiState.exportMessage?.let {
            snackbar.showSnackbar(it)
            onConsumeExportMsg()
        }
    }

    Scaffold(
        containerColor = AniVaultColors.Background,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            if (uiState.isMultiSelectMode) {
                MultiSelectTopBar(
                    selectedCount = uiState.selectedIds.size,
                    totalCount    = uiState.entries.size,
                    accent        = accent,
                    onClear       = onClearSelect,
                    onSelectAll   = onSelectAll,
                    onDeleteSelected = onDeleteSelected,
                    onChangeStatus   = { showBulkStatusDialog = true }
                )
            } else {
                DashboardTopBar(
                    currentMode   = uiState.mode,
                    accent        = accent,
                    isHVaultOpen  = uiState.mode == AppMode.H_VAULT,
                    sortOrder     = uiState.sortOrder,
                    onModeToggle  = onModeToggle,
                    onLockHVault  = onLockHVault,
                    onSearchClick = onSearchClick,
                    onSettingsClick = onSettingsClick,
                    onSortClick   = { showSortDialog = true },
                    onExportPdf   = onExportPdf,
                    onExportDocx  = onExportDocx,
                    onExportJson  = onExportJson,
                    onImportJson  = onImportJson
                )
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start  = 12.dp, end = 12.dp,
                top    = padding.calculateTopPadding() + 8.dp,
                bottom = 80.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement   = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(2) }) {
                StatsRow(stats = uiState.stats, accent = accent)
            }
            item(span = { GridItemSpan(2) }) {
                FilterTabRow(selected = uiState.selectedFilter, accent = accent, onSelect = onFilterSelect)
            }

            when {
                uiState.isLoading -> item(span = { GridItemSpan(2) }) {
                    Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                        CircularProgressIndicator(color = accent)
                    }
                }
                uiState.entries.isEmpty() -> item(span = { GridItemSpan(2) }) {
                    EmptyState(mode = uiState.mode, accent = accent, onSearchClick = onSearchClick)
                }
                else -> items(uiState.entries, key = { it.id }) { entry ->
                    EntryCard(
                        entry        = entry,
                        accent       = accent,
                        isSelected   = entry.id in uiState.selectedIds,
                        isMultiSelect = uiState.isMultiSelectMode,
                        onClick      = {
                            if (uiState.isMultiSelectMode) onToggleSelect(entry.id)
                            else detailEntry = entry
                        },
                        onLongClick  = { onLongPress(entry.id) }
                    )
                }
            }
        }
    }

    // ── Entry detail bottom sheet ──────────────────────────────────────────
    detailEntry?.let { entry ->
        EntryDetailSheet(
            entry    = entry,
            mode     = uiState.mode,
            accent   = accent,
            onDismiss = { detailEntry = null }
        )
    }

    // ── Sort dialog ────────────────────────────────────────────────────────
    if (showSortDialog) {
        SortDialog(
            current  = uiState.sortOrder,
            accent   = accent,
            onSelect = { onSortSelect(it); showSortDialog = false },
            onDismiss = { showSortDialog = false }
        )
    }

    // ── Bulk status dialog ─────────────────────────────────────────────────
    if (showBulkStatusDialog) {
        BulkStatusDialog(
            accent    = accent,
            onSelect  = { onStatusSelected(it); showBulkStatusDialog = false },
            onDismiss = { showBulkStatusDialog = false }
        )
    }
}

// ── Multi-select top bar ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectTopBar(
    selectedCount: Int,
    totalCount: Int,
    accent: Color,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onChangeStatus: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = accent)
            }
        },
        title = {
            Text("$selectedCount / $totalCount selected", color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select all", tint = accent)
            }
            IconButton(onClick = onChangeStatus) {
                Icon(Icons.Default.Edit, contentDescription = "Change status", tint = accent)
            }
            IconButton(onClick = onDeleteSelected) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AniVaultColors.Surface.copy(alpha = 0.97f))
    )
}

// ── Normal top bar ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    currentMode: AppMode,
    accent: Color,
    isHVaultOpen: Boolean,
    sortOrder: SortOrder,
    onModeToggle: (AppMode) -> Unit,
    onLockHVault: () -> Unit,
    onSearchClick: (AppMode) -> Unit,
    onSettingsClick: () -> Unit,
    onSortClick: () -> Unit,
    onExportPdf: () -> Unit,
    onExportDocx: () -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                if (currentMode == AppMode.ANIME) "AniVault" else "H-Vault",
                fontWeight = FontWeight.Bold, fontSize = 20.sp, color = accent
            )
        },
        actions = {
            IconButton(onClick = { onSearchClick(currentMode) }) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = accent)
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Settings / API sources", tint = accent)
            }
            IconButton(onClick = onSortClick) {
                Icon(Icons.Default.Sort, contentDescription = "Sort", tint = accent)
            }
            ModeTogglePill(currentMode = currentMode, accent = accent, onToggle = onModeToggle)
            AnimatedVisibility(visible = isHVaultOpen, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = onLockHVault) {
                    Icon(Icons.Default.Lock, contentDescription = "Lock H-Vault", tint = accent)
                }
            }
            // Overflow menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = accent)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(AniVaultColors.Surface)
                )  {
                    DropdownMenuItem(
                        text = { Text("Export PDF", color = AniVaultColors.OnSurface) },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = accent) },
                        onClick = { showMenu = false; onExportPdf() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export Word (.docx)", color = AniVaultColors.OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, tint = accent) },
                        onClick = { showMenu = false; onExportDocx() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export JSON", color = AniVaultColors.OnSurface) },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = accent) },
                        onClick = { showMenu = false; onExportJson() }
                    )
                    HorizontalDivider(color = AniVaultColors.Divider)
                    DropdownMenuItem(
                        text = { Text("Import JSON", color = AniVaultColors.OnSurface) },
                        leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null, tint = accent) },
                        onClick = { showMenu = false; onImportJson() }
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AniVaultColors.Surface.copy(alpha = 0.97f))
    )
}

@Composable
private fun ModeTogglePill(currentMode: AppMode, accent: Color, onToggle: (AppMode) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(AniVaultColors.Background)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppMode.values().forEach { mode ->
            val isSelected = mode == currentMode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) accent else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onToggle(mode) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (mode == AppMode.ANIME) "Anime" else "H-Vault",
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else AniVaultColors.OnSurfaceMid
                )
            }
        }
    }
}

// ── Stats Row ─────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(stats: DashboardStats, accent: Color) {
    val hours = stats.watchMinutes / 60L
    val mins  = stats.watchMinutes % 60L
    val timeLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("Entries",    stats.totalEntries.toString(),   accent, Modifier.weight(1f))
        StatCard("Watch Time", timeLabel,                        accent, Modifier.weight(1f))
        StatCard("Avg Rating", if (stats.avgRating > 0) "%.1f★".format(stats.avgRating) else "—★", accent, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AniVaultColors.Surface)
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
            Text(label, fontSize = 11.sp, color = AniVaultColors.OnSurfaceMid)
        }
    }
}

// ── Filter tabs ───────────────────────────────────────────────────────────

private val filterOptions: List<WatchStatus?> = listOf(null, WatchStatus.WATCHING, WatchStatus.COMPLETED, WatchStatus.PLAN_TO_WATCH, WatchStatus.DROPPED)
private val filterLabels = mapOf(
    null to "All",
    WatchStatus.WATCHING      to "Watching",
    WatchStatus.COMPLETED     to "Completed",
    WatchStatus.PLAN_TO_WATCH to "Plan",
    WatchStatus.DROPPED       to "Dropped"
)

@Composable
private fun FilterTabRow(selected: WatchStatus?, accent: Color, onSelect: (WatchStatus?) -> Unit) {
    val idx = filterOptions.indexOf(selected).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = idx,
        containerColor   = Color.Transparent,
        edgePadding      = 0.dp,
        indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[idx]), color = accent) },
        divider  = {}
    ) {
        filterOptions.forEach { status ->
            Tab(
                selected = selected == status,
                onClick  = { onSelect(status) },
                text = {
                    Text(
                        filterLabels[status] ?: "",
                        fontSize = 13.sp,
                        color    = if (selected == status) accent else AniVaultColors.OnSurfaceMid
                    )
                }
            )
        }
    }
}

// ── Entry Card ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(
    entry: DisplayEntry,
    accent: Color,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val progress = if (entry.totalEpisodes > 0)
        (entry.watchedEpisodes.toFloat() / entry.totalEpisodes).coerceIn(0f, 1f)
    else 0f

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .aspectRatio(2f / 3f)
            .background(AniVaultColors.Surface)
            .border(
                width = if (isSelected) 2.5.dp else 0.dp,
                color = if (isSelected) accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(entry.imageUrl).crossfade(true).build(),
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, AniVaultColors.Background.copy(alpha = 0.97f))))
        )
        Column(
            modifier = Modifier
                .fillMaxWidth().align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AniVaultColors.OnSurface)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = accent, trackColor = AniVaultColors.Divider
            )
            Text("${entry.watchedEpisodes} / ${entry.totalEpisodes} eps", fontSize = 10.sp, color = AniVaultColors.OnSurfaceMid)
        }
        StatusBadge(status = entry.status, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (entry.rating > 0f) {
            RatingBadge(rating = entry.rating, accent = accent, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }

        // Multi-select checkbox overlay
        if (isMultiSelect || isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                // A circular checkbox
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) accent else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: WatchStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        WatchStatus.WATCHING      -> "WATCHING" to AniVaultColors.StatusWatching
        WatchStatus.COMPLETED     -> "DONE"     to AniVaultColors.StatusCompleted
        WatchStatus.PLAN_TO_WATCH -> "PLAN"     to AniVaultColors.StatusPlanTo
        WatchStatus.DROPPED       -> "DROPPED"  to AniVaultColors.StatusDropped
    }
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.87f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun RatingBadge(rating: Float, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AniVaultColors.Background.copy(alpha = 0.78f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = accent, modifier = Modifier.size(10.dp))
        Text("%.1f".format(rating), fontSize = 9.sp, color = AniVaultColors.OnSurface)
    }
}

// ── Sort dialog ───────────────────────────────────────────────────────────

@Composable
private fun SortDialog(current: SortOrder, accent: Color, onSelect: (SortOrder) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        SortOrder.DATE_DESC   to "Most Recent",
        SortOrder.DATE_ASC    to "Oldest First",
        SortOrder.TITLE_ASC   to "A → Z (Title)",
        SortOrder.RATING_DESC to "Highest Rated"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AniVaultColors.Surface,
        title = { Text("Sort by", color = AniVaultColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (order, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(order) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = order == current, onClick = { onSelect(order) }, colors = RadioButtonDefaults.colors(selectedColor = accent))
                        Text(label, color = AniVaultColors.OnSurface, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AniVaultColors.OnSurfaceMid) } }
    )
}

// ── Bulk status dialog ────────────────────────────────────────────────────

@Composable
private fun BulkStatusDialog(accent: Color, onSelect: (WatchStatus) -> Unit, onDismiss: () -> Unit) {
    val statusOptions = listOf(
        WatchStatus.WATCHING      to "Watching",
        WatchStatus.COMPLETED     to "Completed",
        WatchStatus.PLAN_TO_WATCH to "Plan to Watch",
        WatchStatus.DROPPED       to "Dropped"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AniVaultColors.Surface,
        title = { Text("Set Status For Selected", color = AniVaultColors.OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                statusOptions.forEach { (status, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(status) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(statusColor(status))
                        )
                        Text(label, color = AniVaultColors.OnSurface, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AniVaultColors.OnSurfaceMid) } }
    )
}

private fun statusColor(status: WatchStatus): Color = when (status) {
    WatchStatus.WATCHING      -> AniVaultColors.StatusWatching
    WatchStatus.COMPLETED     -> AniVaultColors.StatusCompleted
    WatchStatus.PLAN_TO_WATCH -> AniVaultColors.StatusPlanTo
    WatchStatus.DROPPED       -> AniVaultColors.StatusDropped
}

// ── Empty state ───────────────────────────────────────────────────────────

@Composable
private fun EmptyState(mode: AppMode, accent: Color, onSearchClick: (AppMode) -> Unit) {
    val (emoji, label) = when (mode) {
        AppMode.ANIME   -> "🎌" to "No anime tracked yet.\nSearch and add your first title!"
        AppMode.H_VAULT -> "🔒" to "H-Vault is empty.\nSearch and add entries."
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(emoji, fontSize = 48.sp)
        Text(label, fontSize = 14.sp, color = AniVaultColors.OnSurfaceMid, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSearchClick(mode) },
            border  = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
            colors  = ButtonDefaults.outlinedButtonColors(contentColor = accent)
        ) { Text("Search & Add", fontSize = 13.sp) }
    }
}
