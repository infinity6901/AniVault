@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.anivault.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anivault.data.backup.RestoreMode
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.PreviewScraperSource
import com.anivault.data.sync.AccountProvider
import com.anivault.data.sync.ConflictPolicy
import com.anivault.di.MigrationStatus
import com.anivault.notify.Notifications
import com.anivault.ui.common.*
import com.anivault.ui.theme.dim
import com.anivault.ui.theme.TrackGlassOverlay
import com.anivault.ui.theme.liquidGlassPanel
import java.text.DateFormat
import java.util.Date

@Composable
private fun CategoryScaffold(title: String, onBack: () -> Unit, vm: SettingsViewModel, content: @Composable ColumnScope.() -> Unit) {
    val msg by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }
    ) { pad -> Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
private fun Note(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)

// ── Account & Sync ──────────────────────────────────────────────────────────

@Composable
fun AccountScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val states by vm.accountStates.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var kitsuDialog by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(ConflictPolicy.parse(vm.prefs.conflictPolicy)) }
    var disconnecting by remember { mutableStateOf<AccountProvider?>(null) }
    CategoryScaffold("Account & Sync", onBack, vm) {
        Note("Connect a list service to keep your progress in sync. Your library always works offline; changes queue and send when you're back online. Sign-in happens in your browser – AniVault never sees your AniList or MyAnimeList password.")
        states.values.forEach { st ->
            val p = st.provider
            var clientId by remember(p) { mutableStateOf("") }
            AniCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.label, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    when {
                        st.needsReauth -> Text("Sign-in expired. Connect again.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        st.connected -> Text("Connected as ${st.accountName}", fontSize = 13.sp)
                        else -> Text(p.signInHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
                    }
                    if (st.lastSyncAt > 0) Note("Last synced ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.lastSyncAt))}")
                    st.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    if (p.needsClientId && (!st.connected || st.needsReauth)) {
                        OutlinedTextField(
                            clientId, { clientId = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (st.clientIdSet) "Client ID (saved – leave blank to keep)" else "Your ${p.label} client ID") },
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = { Text("Register this redirect URI in your ${p.label} app: ${p.redirectUri}") }
                        )
                        if (clientId.isNotBlank()) OutlinedButton(onClick = { vm.setClientId(p, clientId); clientId = ""; vm.say("Client ID saved on this device.") }) { Text("Save client ID") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!st.connected || st.needsReauth) {
                            Button(onClick = {
                                if (p == AccountProvider.KITSU) kitsuDialog = true
                                else {
                                    if (clientId.isNotBlank()) { vm.setClientId(p, clientId); clientId = "" }
                                    val url = vm.authorizeUrl(p)
                                    if (url == null) vm.say("Enter your ${p.label} client ID first.")
                                    else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        .onFailure { vm.say("No browser available to sign in.") }
                                }
                            }, enabled = st.canConnect || clientId.isNotBlank() || p == AccountProvider.KITSU) { Text("Connect") }
                        } else {
                            OutlinedButton(onClick = { disconnecting = p }) { Text("Disconnect") }
                        }
                    }
                }
            }
        }
        Button(onClick = vm::syncNow, enabled = !syncing && states.values.any { it.connected && !it.needsReauth }, modifier = Modifier.fillMaxWidth()) {
            if (syncing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (syncing) "Syncing…" else "Sync now")
        }
        val syncText by vm.syncText.collectAsStateWithLifecycle()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::previewSync, enabled = !syncing && states.values.any { it.connected && !it.needsReauth }, modifier = Modifier.weight(1f)) { Text("Preview changes") }
            OutlinedButton(onClick = vm::showSyncLog, modifier = Modifier.weight(1f)) { Text("Sync log") }
        }
        syncText?.let { (title, lines) ->
            AlertDialog(
                onDismissRequest = vm::closeSyncText, title = { Text(title) },
                text = {
                    TrackGlassOverlay()
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(lines.size) { i -> Text(lines[i], fontSize = 13.sp, fontWeight = if (lines[i].startsWith("—")) FontWeight.Bold else FontWeight.Normal) }
                    }
                },
                confirmButton = { TextButton(onClick = vm::closeSyncText) { Text("Close") } },
                dismissButton = if (title == "Sync log") ({ TextButton(onClick = vm::clearSyncLog) { Text("Clear log") } }) else null
            )
        }
        var delRemote by remember { mutableStateOf(vm.prefs.deleteOnAccounts) }
        var markVault by remember { mutableStateOf(vm.prefs.markVaultOnAccounts) }
        GroupLabel("Deleting and H-Vault")
        SwitchRow("Delete on accounts too", "Deleting a title here also removes it from your AniList, MyAnimeList and Kitsu lists.", delRemote) { delRemote = it; vm.prefs.deleteOnAccounts = it }
        SwitchRow("Tag H-Vault titles on accounts", "Titles you move to H-Vault get a small tag in their notes (and become private on AniList and Kitsu), so any device that syncs keeps them in H-Vault. MyAnimeList can't hold the tag.", markVault) { markVault = it; vm.prefs.markVaultOnAccounts = it }
        Note("H-Vault progress syncs from the H-Vault screen (Sync button), which needs your unlock.")
        GroupLabel("If both sides changed")
        ChoiceGroup(ConflictPolicy.values().map { it to it.label }, policy, { policy = it; vm.setConflictPolicy(it) }, { it.explanation })
    }
    if (kitsuDialog) KitsuDialog(onDismiss = { kitsuDialog = false }, onSignIn = { e, pw -> kitsuDialog = false; vm.signInKitsu(e, pw) })
    disconnecting?.let { p ->
        ConfirmDialog("Disconnect ${p.label}?", "Sync stops and the saved sign-in is removed. Your library on this device is kept.", "Disconnect", { vm.disconnect(p); disconnecting = null }, { disconnecting = null })
    }
}

@Composable
private fun KitsuDialog(onDismiss: () -> Unit, onSignIn: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sign in to Kitsu") }, text = {
        TrackGlassOverlay()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
            OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Note("Sent only to Kitsu. AniVault keeps a sign-in token, never your password.")
        }
    }, confirmButton = { TextButton(onClick = { onSignIn(email, pw) }, enabled = email.isNotBlank() && pw.isNotEmpty()) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ── Library & Tracking ──────────────────────────────────────────────────────

@Composable
fun LibrarySettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val p = vm.prefs
    var confirm by remember { mutableStateOf(p.confirmDelete) }
    var auto by remember { mutableStateOf(p.autoCompleteOnLast) }
    var plus by remember { mutableStateOf(p.showPlusOne) }
    var syn by remember { mutableStateOf(p.showSynopsis) }
    var status by remember { mutableStateOf(WatchStatus.parse(p.defaultAddStatus)) }
    CategoryScaffold("Library & Tracking", onBack, vm) {
        GroupLabel("Adding titles")
        ChoiceGroup(WatchStatus.values().map { it to it.label }, status, { status = it; p.defaultAddStatus = it.name })
        GroupLabel("Progress")
        SwitchRow("Mark completed automatically", "When progress reaches the last known episode.", auto) { auto = it; p.autoCompleteOnLast = it }
        SwitchRow("Show +1 button", "Quick “next episode” on Continue Watching cards.", plus) { plus = it; p.showPlusOne = it }
        GroupLabel("Cover images")
        Note("Covers are saved on this device after the first load so lists open instantly and work offline. If a cover looks wrong or low quality, refresh them.")
        val busyCovers by vm.busy.collectAsStateWithLifecycle()
        OutlinedButton(onClick = vm::refreshCovers, enabled = !busyCovers, modifier = Modifier.fillMaxWidth()) { Text("Refresh library covers") }
        GroupLabel("Details")
        SwitchRow("Show synopsis", null, syn) { syn = it; p.showSynopsis = it }
        SwitchRow("Confirm before deleting", "Deleting always offers Undo afterwards.", confirm) { confirm = it; p.confirmDelete = it }
    }
}

@Composable
fun PlayerSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var view by remember { mutableStateOf(vm.prefs.libraryView) }
    var density by remember { mutableStateOf(vm.prefs.gridDensity) }
    var badge by remember { mutableStateOf(vm.prefs.posterBadge) }
    var swipes by remember { mutableStateOf(vm.prefs.swipeGestures) }
    CategoryScaffold("Reader / Player", onBack, vm) {
        Note("AniVault is a tracker and catalog. Customize how your library grid, poster badges, and list swipe gestures behave here.")
        GroupLabel("Library layout")
        ChoiceGroup(listOf("grid" to "Poster grid", "list" to "Detailed list"), view, { view = it; vm.prefs.libraryView = it })
        SwitchRow(
            title = "List swipe gestures",
            subtitle = "Deliberate horizontal swipe right for +1 episode or left to cycle watch status.",
            checked = swipes
        ) { swipes = it; vm.prefs.swipeGestures = it }
        GroupLabel("Poster grid density")
        ChoiceGroup(
            listOf(
                "compact" to "Compact (4 columns)",
                "standard" to "Standard (3 columns)",
                "large" to "Large posters (2 columns)"
            ),
            density,
            { density = it; vm.prefs.gridDensity = it }
        )
        GroupLabel("Poster corner badge")
        ChoiceGroup(
            listOf(
                "rating" to "Personal rating (★ 9.0)",
                "unwatched" to "Episodes remaining (e.g. 4 left)",
                "none" to "No extra badge"
            ),
            badge,
            { badge = it; vm.prefs.posterBadge = it }
        )
    }
}

// ── Appearance ──────────────────────────────────────────────────────────────

@Composable
fun AppearanceScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(vm.prefs.theme) }
    var palette by remember { mutableStateOf(com.anivault.ui.theme.Palette.parse(vm.prefs.palette)) }
    var glassTint by remember { mutableStateOf(com.anivault.ui.theme.LiquidGlassTint.parse(vm.prefs.glassTint)) }
    var glassOpacity by remember { mutableStateOf(vm.prefs.glassOpacity) }
    var glassSpecular by remember { mutableStateOf(vm.prefs.glassSpecular) }
    var glassBlur by remember { mutableStateOf(vm.prefs.glassBlur) }
    var glassAnimated by remember { mutableStateOf(vm.prefs.glassAnimated) }
    var glassFloatingNav by remember { mutableStateOf(vm.prefs.glassFloatingNav) }
    var widgetGlass by remember { mutableStateOf(vm.prefs.widgetGlassEnabled) }
    var widgetGlassOpacity by remember { mutableStateOf(vm.prefs.widgetGlassOpacity) }
    var widgetGlassCover by remember { mutableStateOf(vm.prefs.widgetGlassMatchCover) }

    val canDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isGlassSelected = palette == com.anivault.ui.theme.Palette.LIQUID_GLASS || palette == com.anivault.ui.theme.Palette.GLASS

    CategoryScaffold("Appearance", onBack, vm) {
        GroupLabel("Theme")
        ChoiceGroup(listOf("dark" to "Dark", "light" to "Light", "system" to "Follow system"), theme, { theme = it; vm.prefs.theme = it })
        Note("Liquid Glass, Frosted glass, AMOLED black, Cyberpunk Neon and Crimson Eclipse are always dark; Matcha Paper defaults to warm cream paper.")
        GroupLabel("Style")
        com.anivault.ui.theme.Palette.values().forEach { p ->
            val enabled = p != com.anivault.ui.theme.Palette.DYNAMIC || canDynamic
            val selected = p == palette
            androidx.compose.material3.Surface(
                onClick = {
                    if (enabled) {
                        palette = p
                        vm.prefs.palette = p.key
                        com.anivault.widget.WidgetUpdater.refreshAll(ctx)
                    }
                },
                enabled = enabled, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        p.swatch.forEach { c -> Box(Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c).border(1.dp, Color(0x55FFFFFF), androidx.compose.foundation.shape.CircleShape)) }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label, fontWeight = FontWeight.SemiBold)
                        Text(if (!enabled) "Requires Android 12 or newer." else p.blurb, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        GroupLabel("Liquid Glass Studio")
        Note(
            if (isGlassSelected) "Tune the refractive tint, frosted opacity, 3D specular rim, and living orb backdrop in real time."
            else "Select “Liquid Glass” above to activate full-screen refractive glass, or customize the tint used by Liquid Glass homescreen widgets below."
        )

        val previewCfg = com.anivault.ui.theme.LiquidGlassConfig(
            enabled = true,
            tint = glassTint,
            opacityPct = glassOpacity,
            specularPct = glassSpecular,
            blurDp = glassBlur,
            animated = glassAnimated,
            floatingNav = glassFloatingNav,
            widgetEnabled = widgetGlass,
            widgetOpacityPct = widgetGlassOpacity,
            widgetMatchCover = widgetGlassCover
        )

        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(glassTint.baseStart, glassTint.orb1.copy(alpha = 0.45f), glassTint.orb2.copy(alpha = 0.45f), glassTint.baseEnd)
                    )
                )
                .padding(14.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .liquidGlassPanel(previewCfg, androidx.compose.foundation.shape.RoundedCornerShape(16.dp), 16f, elevated = true)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LIQUID GLASS PREVIEW · ${glassTint.label.uppercase()}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = glassTint.primary)
                    Text("Refractive Specular Surface", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    Text("Opacity $glassOpacity% · Specular Rim $glassSpecular% · Blur ${glassBlur}dp", fontSize = 12.sp, color = Color(0xFFD2E2F2))
                }
            }
        }

        Text("Refractive Tint Preset", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.anivault.ui.theme.LiquidGlassTint.values().forEach { t ->
                FilterChip(
                    selected = glassTint == t,
                    onClick = {
                        glassTint = t
                        vm.prefs.glassTint = t.key
                        com.anivault.widget.WidgetUpdater.refreshAll(ctx)
                    },
                    label = { Text(t.label) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(t.primary)
                        )
                    }
                )
            }
        }

        Text("Frosted Panel Opacity: $glassOpacity%", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Slider(
            value = glassOpacity.toFloat(),
            onValueChange = { glassOpacity = it.toInt(); vm.prefs.glassOpacity = glassOpacity },
            valueRange = 10f..65f
        )

        Text("3D Specular Rim & Caustic Highlight: $glassSpecular%", color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = glassSpecular.toFloat(),
            onValueChange = { glassSpecular = it.toInt(); vm.prefs.glassSpecular = glassSpecular },
            valueRange = 0f..100f,
            onValueChangeFinished = { com.anivault.widget.WidgetUpdater.refreshAll(ctx) }
        )

        Text("Backdrop Orb Blur Radius: ${glassBlur} dp", color = MaterialTheme.colorScheme.onSurface)
        Slider(
            value = glassBlur.toFloat(),
            onValueChange = { glassBlur = it.toInt(); vm.prefs.glassBlur = glassBlur },
            valueRange = 0f..40f
        )

        SwitchRow(
            title = "Animated liquid light orbs",
            subtitle = "Slowly drifts refractive aurora orbs behind glass panels.",
            checked = glassAnimated
        ) { glassAnimated = it; vm.prefs.glassAnimated = it }

        SwitchRow(
            title = "Floating Liquid Glass capsule dock",
            subtitle = "Lifts the bottom navigation bar into a floating refractive glass pill.",
            checked = glassFloatingNav
        ) { glassFloatingNav = it; vm.prefs.glassFloatingNav = it }

        GroupLabel("Liquid Glass Homescreen Widgets")
        Note("Applies a dynamically rendered 3D Liquid Glass surface with specular rims and radial light orbs to the Progress, Airing Next, and Library Stats widgets.")

        SwitchRow(
            title = "Enable Liquid Glass widgets",
            subtitle = "Renders translucent frosted glass, caustic top highlights, and glowing orbs on all AniVault homescreen widgets.",
            checked = widgetGlass
        ) {
            widgetGlass = it
            vm.prefs.widgetGlassEnabled = it
            com.anivault.widget.WidgetUpdater.refreshAll(ctx)
        }

        if (widgetGlass) {
            Text("Widget Glass Opacity: $widgetGlassOpacity%", color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = widgetGlassOpacity.toFloat(),
                onValueChange = { widgetGlassOpacity = it.toInt() },
                valueRange = 15f..85f,
                onValueChangeFinished = {
                    vm.prefs.widgetGlassOpacity = widgetGlassOpacity
                    com.anivault.widget.WidgetUpdater.refreshAll(ctx)
                }
            )

            SwitchRow(
                title = "Chameleon cover-art glow",
                subtitle = "Extracts the vibrant colour from your currently watching anime cover to tint the Progress Widget's liquid glass orb and specular rim.",
                checked = widgetGlassCover
            ) {
                widgetGlassCover = it
                vm.prefs.widgetGlassMatchCover = it
                com.anivault.widget.WidgetUpdater.refreshAll(ctx)
            }
        }
    }
}

// ── Backup & Restore ────────────────────────────────────────────────────────

@Composable
fun BackupScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    var includeLibrary by remember { mutableStateOf(true) }
    var includeVault by remember { mutableStateOf(false) }
    var includeSettings by remember { mutableStateOf(true) }
    var includeSecrets by remember { mutableStateOf(false) }
    var pwDialog by remember { mutableStateOf<String?>(null) } // "export" | "restore"
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) { pendingUri = u; pwDialog = "export" } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { pendingUri = u; pwDialog = "restore" } }

    CategoryScaffold("Backup & Restore", onBack, vm) {
        GroupLabel("Encrypted backup")
        Note("Backups are encrypted with a password you choose (AES-256-GCM). If you forget it, the backup cannot be opened – there is no recovery.")
        SwitchRow("Include library", "Your normal anime library. Turn off to back up only H-Vault, settings or sign-ins.", includeLibrary) { includeLibrary = it }
        SwitchRow("Include settings", "Theme and library preferences.", includeSettings) { includeSettings = it }
        SwitchRow("Include H-Vault", "Off by default. Only turn on if you want private entries inside this file.", includeVault) { includeVault = it }
        SwitchRow("Include sign-ins and client IDs", "Off by default. Adds your AniList, MyAnimeList and Kitsu sign-in tokens and your client IDs, so a restore on a new device stays signed in. Anyone with the file and its password can use them.", includeSecrets) { includeSecrets = it }
        Button(onClick = { create.launch("anivault-backup.avbak") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted backup") }
        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup…") }
        if (canUndo) OutlinedButton(onClick = vm::undoRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Undo last restore") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        GroupLabel("Import from MyAnimeList, CSV or JSON")
        Note("Import a MyAnimeList export (.xml or .xml.gz), or an AniVault CSV / JSON file. Titles already in AniVault are skipped.")
        val importMal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importMal(u) }
        val importCsvJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importCsvOrJson(u) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { importMal.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import MAL XML…") }
            OutlinedButton(onClick = { importCsvJson.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import CSV / JSON…") }
        }

        GroupLabel("Export your list")
        Note("Readable copies of your anime library saved to Downloads (not encrypted). MAL XML can be imported directly into MyAnimeList, AniList or Kitsu.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.exportList("pdf") }, enabled = !busy) { Text("PDF") }
            OutlinedButton(onClick = { vm.exportList("docx") }, enabled = !busy) { Text("Word") }
            OutlinedButton(onClick = { vm.exportList("json") }, enabled = !busy) { Text("JSON") }
            OutlinedButton(onClick = { vm.exportList("csv") }, enabled = !busy) { Text("CSV") }
            OutlinedButton(onClick = { vm.exportList("mal_xml") }, enabled = !busy) { Text("MAL XML") }
        }

        GroupLabel("Export H-Vault")
        Note("Private entries with their covers. You must unlock with your fingerprint, face or screen lock first. The file is saved to Downloads and is not encrypted, so keep it somewhere safe.")
        val ctx = LocalContext.current
        val askThenExport = { kind: String ->
            com.anivault.ui.vault.authenticateDevice(ctx, "Export H-Vault", "Confirm it's you to export your private library", { vm.exportVault(kind) }, { vm.say(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { askThenExport("pdf") }, enabled = !busy) { Text("PDF") }
            OutlinedButton(onClick = { askThenExport("docx") }, enabled = !busy) { Text("Word") }
            OutlinedButton(onClick = { askThenExport("json") }, enabled = !busy) { Text("JSON") }
        }
    }

    pwDialog?.let { kind ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pwDialog = null }, title = { Text(if (kind == "export") "Choose a backup password" else "Backup password") }, text = {
            TrackGlassOverlay()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (kind == "export") Note("At least 8 characters.")
            }
        }, confirmButton = {
            TextButton(enabled = pw.isNotEmpty(), onClick = {
                val u = pendingUri; pwDialog = null
                if (u != null) { if (kind == "export") vm.exportBackup(u, pw, includeVault, includeSettings, includeSecrets, includeLibrary) else vm.openBackup(u, pw) }
            }) { Text(if (kind == "export") "Create" else "Open") }
        }, dismissButton = { TextButton(onClick = { pwDialog = null }) { Text("Cancel") } })
    }

    preview?.let { pv ->
        var mode by remember { mutableStateOf(RestoreMode.MERGE) }
        var withLibrary by remember { mutableStateOf(pv.hasLibrary) }
        var withVault by remember { mutableStateOf(false) }
        var withSettings by remember { mutableStateOf(pv.hasSettings) }
        var withSecrets by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Restore preview") }, text = {
            TrackGlassOverlay()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Backup from ${DateFormat.getDateTimeInstance().format(Date(pv.createdAt))}", fontSize = 13.sp)
                if (pv.hasLibrary) Text("${pv.animeInBackup} titles: ${pv.animeNew} new, ${pv.animeConflicts} differ from yours, ${pv.animeIdentical} identical.")
                else Text("This backup does not contain the normal library.", fontSize = 13.sp)
                if (pv.hasLibrary && pv.animeOnlyHere > 0) Text("${pv.animeOnlyHere} titles exist only on this device.", fontSize = 13.sp)
                pv.hvaultInBackup?.let { Text("H-Vault: $it entries (${pv.hvaultNew} new, ${pv.hvaultConflicts} differ).") }
                ChoiceGroup(
                    listOf(RestoreMode.MERGE to "Merge", RestoreMode.REPLACE to "Replace"), mode, { mode = it },
                    { if (it == RestoreMode.MERGE) "Keep everything here; add new titles and take the backup's copy only where it is newer." else "Make the library exactly match the backup. ${pv.animeOnlyHere} local titles would be removed." }
                )
                if (pv.hasLibrary) SwitchRow("Restore library", null, withLibrary) { withLibrary = it }
                if (pv.hvaultInBackup != null) SwitchRow("Restore H-Vault entries", null, withVault) { withVault = it }
                if (pv.hasSettings) SwitchRow("Restore settings", null, withSettings) { withSettings = it }
                if (pv.hasSecrets) SwitchRow("Restore sign-ins and client IDs", "Replaces the accounts and client IDs on this device with the ones in the backup.", withSecrets) { withSecrets = it }
                Note("Everything is applied in one step; if it fails nothing changes, and you can undo afterwards.")
            }
        }, confirmButton = { TextButton(onClick = { vm.restore(mode, withVault, withSettings, withSecrets, withLibrary) }) { Text(if (mode == RestoreMode.REPLACE) "Replace" else "Merge") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } })
    }
}

// ── H-Vault ─────────────────────────────────────────────────────────────────

@Composable
fun VaultSettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var auto by remember { mutableStateOf(vm.prefs.vaultAutoLock) }
    var timeout by remember { mutableStateOf(vm.prefs.vaultLockTimeoutSec) }
    var previewSrc by remember { mutableStateOf(PreviewScraperSource.parse(vm.prefs.vaultPreviewScraper)) }
    val migration by vm.migrationStatus.collectAsStateWithLifecycle()
    val keyLost by vm.health.keyLost.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    CategoryScaffold("H-Vault", onBack, vm) {
        Button(onClick = onOpenVault, Modifier.fillMaxWidth()) { Text("Open H-Vault") }
        Note("H-Vault entries are stored in an encrypted database. The biometric/device-credential prompt controls access in the app; the encryption protects the data on disk. Screenshots are blocked inside H-Vault, and nothing from it appears in notifications or diagnostics.")
        GroupLabel("Auto-lock")
        SwitchRow("Lock when leaving the app", null, auto) { auto = it; vm.prefs.vaultAutoLock = it }
        if (auto) ChoiceGroup(listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes"), timeout, { timeout = it; vm.prefs.vaultLockTimeoutSec = it })
        GroupLabel("Scene Preview Scraper")
        Note("Choose the main scraper for 16:9 scene preview images in H-Vault. Other scrapers are used automatically as fallbacks when a show is missing.")
        ChoiceGroup(
            PreviewScraperSource.values().map { it to it.label },
            previewSrc,
            { previewSrc = it; vm.prefs.vaultPreviewScraper = it.key },
            { it.description }
        )
        GroupLabel("Storage")
        if (keyLost) Text("The device's encryption key was unavailable, so the old database was set aside (not deleted) and a new one started. Restore from a backup to recover entries.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        when (val m = migration) {
            is MigrationStatus.Verified -> {
                Text("Your earlier unencrypted data was copied into the encrypted database and checked (${m.animeCount} anime, ${m.vaultCount} H-Vault).", fontSize = 13.sp)
                if (vm.migrator.canRemoveLegacy()) {
                    OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove old unencrypted copy") }
                    Note("This deletes the old file normally; it cannot be securely wiped on all devices.")
                }
            }
            is MigrationStatus.Failed -> Text("Couldn't finish moving old data: ${m.message}. Your old data is untouched.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            MigrationStatus.Running -> Text("Moving earlier data into the encrypted database…", fontSize = 13.sp)
            else -> Text("Database encrypted.", fontSize = 13.sp)
        }
    }
    if (confirmRemove) ConfirmDialog("Remove old copy?", "Make sure your library looks complete first.", "Remove", { confirmRemove = false; vm.removeLegacy() }, { confirmRemove = false }, destructive = true)
}

// ── Advanced (+ notifications) ──────────────────────────────────────────────

@Composable
fun AdvancedScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val p = vm.prefs
    val ctx = LocalContext.current
    var timeout by remember { mutableStateOf(p.networkTimeoutSec) }
    var retries by remember { mutableStateOf(p.networkRetries) }
    var emptyFb by remember { mutableStateOf(p.fallbackOnEmpty) }
    var notif by remember { mutableStateOf(p.notifEnabled && Notifications.hasPermission(ctx)) }
    var reminders by remember { mutableStateOf(p.notifReminders) }
    var airing by remember { mutableStateOf(p.notifAiring) }
    var qs by remember { mutableStateOf(p.quietStartHour) }
    var qe by remember { mutableStateOf(p.quietEndHour) }
    var confirmReset by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val entries by vm.diagnostics.entries.collectAsStateWithLifecycle()
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notif = ok; p.notifEnabled = ok; vm.applyNotifications()
        if (!ok) vm.say("Notifications stay off until you allow them.")
    }
    CategoryScaffold("Advanced", onBack, vm) {
        GroupLabel("Network")
        Text("Timeout: $timeout s", color = MaterialTheme.colorScheme.onSurface)
        Slider(timeout.toFloat(), { timeout = it.toInt() }, valueRange = 5f..60f, onValueChangeFinished = { p.networkTimeoutSec = timeout })
        Text("Automatic retries: $retries", color = MaterialTheme.colorScheme.onSurface)
        Slider(retries.toFloat(), { retries = it.toInt() }, valueRange = 0f..3f, steps = 2, onValueChangeFinished = { p.networkRetries = retries })
        SwitchRow("Try next source when a search is empty", "Off by default: an empty result is a real answer, not an outage.", emptyFb) { emptyFb = it; p.fallbackOnEmpty = it }
        OutlinedButton(onClick = vm::clearCache) { Text("Clear cache") }

        GroupLabel("Notifications")
        SwitchRow("Allow notifications", "Reminders never include titles.", notif) { on ->
            if (!on) { notif = false; p.notifEnabled = false; vm.applyNotifications() }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.hasPermission(ctx)) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { notif = true; p.notifEnabled = true; vm.applyNotifications() }
        }
        SwitchRow("New episode alerts", "Tells you when a show you're watching has a new episode out (with a +1 Watched button on the notification).", airing, notif) { airing = it; p.notifAiring = it; vm.applyNotifications() }
        SwitchRow("Watch reminders", "A daily nudge when shows have sat untouched for a few days.", reminders, notif) { reminders = it; p.notifReminders = it; vm.applyNotifications() }
        if (notif) {
            Text("Quiet hours: ${"%02d".format(qs)}:00 – ${"%02d".format(qe)}:00", color = MaterialTheme.colorScheme.onSurface)
            Slider(qs.toFloat(), { qs = it.toInt() }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { p.quietStartHour = qs })
            Slider(qe.toFloat(), { qe = it.toInt() }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { p.quietEndHour = qe })
        }

        GroupLabel("Diagnostics")
        Note("A short in-memory list of recent connection results. It contains no tokens, searches or private titles and is cleared when the app closes.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showLog = true }) { Text("View (${entries.size})") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.diagnosticsText())
                ctx.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, enabled = entries.isNotEmpty()) { Text("Export") }
            OutlinedButton(onClick = vm::clearDiagnostics, enabled = entries.isNotEmpty()) { Text("Clear") }
        }

        GroupLabel("Reset")
        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset preferences") }
        Note("Does not touch your library, accounts or API sources.")
    }
    if (confirmReset) ConfirmDialog("Reset preferences?", "Theme, tracking and network settings return to defaults.", "Reset", { confirmReset = false; vm.resetPrefs(); onBack() }, { confirmReset = false }, destructive = true)
    if (showLog) AlertDialog(onDismissRequest = { showLog = false }, title = { Text("Recent activity") }, text = {
        TrackGlassOverlay()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (entries.isEmpty()) Text("Nothing yet.")
            entries.forEach { e -> Text("${if (e.ok) "✓" else "✗"} ${e.source} · ${e.operation} · ${e.durationMs} ms" + (e.message?.let { "\n   $it" } ?: ""), fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } })
}
