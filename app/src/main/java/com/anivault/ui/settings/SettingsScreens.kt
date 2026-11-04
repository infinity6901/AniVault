package com.anivault.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anivault.data.backup.RestoreMode
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.sync.AccountProvider
import com.anivault.data.sync.ConflictPolicy
import com.anivault.di.MigrationStatus
import com.anivault.notify.Notifications
import com.anivault.ui.common.*
import com.anivault.ui.theme.dim
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
    ) { pad -> Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
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
        GroupLabel("Details")
        SwitchRow("Show synopsis", null, syn) { syn = it; p.showSynopsis = it }
        SwitchRow("Confirm before deleting", "Deleting always offers Undo afterwards.", confirm) { confirm = it; p.confirmDelete = it }
    }
}

@Composable
fun PlayerSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var view by remember { mutableStateOf(vm.prefs.libraryView) }
    CategoryScaffold("Reader / Player", onBack, vm) {
        Note("AniVault is a tracker and catalog. It does not include a video player or stream content, so there are no playback settings here.")
        GroupLabel("Library layout")
        ChoiceGroup(listOf("grid" to "Poster grid", "list" to "Detailed list"), view, { view = it; vm.prefs.libraryView = it })
    }
}

// ── Appearance ──────────────────────────────────────────────────────────────

@Composable
fun AppearanceScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var theme by remember { mutableStateOf(vm.prefs.theme) }
    var dyn by remember { mutableStateOf(vm.prefs.dynamicColor) }
    val canDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    CategoryScaffold("Appearance", onBack, vm) {
        GroupLabel("Theme")
        ChoiceGroup(listOf("dark" to "Dark", "light" to "Light", "system" to "Follow system"), theme, { theme = it; vm.prefs.theme = it })
        GroupLabel("Colors")
        SwitchRow("Material You colors", if (canDynamic) "Use your wallpaper's colors instead of the AniVault purple." else "Requires Android 12 or newer.", dyn && canDynamic, canDynamic) { dyn = it; vm.prefs.dynamicColor = it }
    }
}

// ── Backup & Restore ────────────────────────────────────────────────────────

@Composable
fun BackupScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    var includeVault by remember { mutableStateOf(false) }
    var includeSettings by remember { mutableStateOf(true) }
    var pwDialog by remember { mutableStateOf<String?>(null) } // "export" | "restore"
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) { pendingUri = u; pwDialog = "export" } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { pendingUri = u; pwDialog = "restore" } }

    CategoryScaffold("Backup & Restore", onBack, vm) {
        GroupLabel("Encrypted backup")
        Note("Backups are encrypted with a password you choose (AES-256-GCM). If you forget it, the backup cannot be opened – there is no recovery.")
        SwitchRow("Include settings", "Theme and library preferences. Never sign-ins or client IDs.", includeSettings) { includeSettings = it }
        SwitchRow("Include H-Vault", "Off by default. Only turn on if you want private entries inside this file.", includeVault) { includeVault = it }
        Button(onClick = { create.launch("anivault-backup.avbak") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted backup") }
        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup…") }
        if (canUndo) OutlinedButton(onClick = vm::undoRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Undo last restore") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        GroupLabel("Export your list")
        Note("Readable copies of your anime library saved to Downloads. These are not encrypted.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.exportList("pdf") }, enabled = !busy) { Text("PDF") }
            OutlinedButton(onClick = { vm.exportList("docx") }, enabled = !busy) { Text("Word") }
            OutlinedButton(onClick = { vm.exportList("json") }, enabled = !busy) { Text("JSON") }
        }
    }

    pwDialog?.let { kind ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pwDialog = null }, title = { Text(if (kind == "export") "Choose a backup password" else "Backup password") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (kind == "export") Note("At least 8 characters.")
            }
        }, confirmButton = {
            TextButton(enabled = pw.isNotEmpty(), onClick = {
                val u = pendingUri; pwDialog = null
                if (u != null) { if (kind == "export") vm.exportBackup(u, pw, includeVault, includeSettings) else vm.openBackup(u, pw) }
            }) { Text(if (kind == "export") "Create" else "Open") }
        }, dismissButton = { TextButton(onClick = { pwDialog = null }) { Text("Cancel") } })
    }

    preview?.let { pv ->
        var mode by remember { mutableStateOf(RestoreMode.MERGE) }
        var withVault by remember { mutableStateOf(false) }
        var withSettings by remember { mutableStateOf(pv.hasSettings) }
        AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Restore preview") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Backup from ${DateFormat.getDateTimeInstance().format(Date(pv.createdAt))}", fontSize = 13.sp)
                Text("${pv.animeInBackup} titles: ${pv.animeNew} new, ${pv.animeConflicts} differ from yours, ${pv.animeIdentical} identical.")
                if (pv.animeOnlyHere > 0) Text("${pv.animeOnlyHere} titles exist only on this device.", fontSize = 13.sp)
                pv.hvaultInBackup?.let { Text("H-Vault: $it entries (${pv.hvaultNew} new, ${pv.hvaultConflicts} differ).") }
                ChoiceGroup(
                    listOf(RestoreMode.MERGE to "Merge", RestoreMode.REPLACE to "Replace"), mode, { mode = it },
                    { if (it == RestoreMode.MERGE) "Keep everything here; add new titles and take the backup's copy only where it is newer." else "Make the library exactly match the backup. ${pv.animeOnlyHere} local titles would be removed." }
                )
                if (pv.hvaultInBackup != null) SwitchRow("Restore H-Vault entries", null, withVault) { withVault = it }
                if (pv.hasSettings) SwitchRow("Restore settings", null, withSettings) { withSettings = it }
                Note("Everything is applied in one step; if it fails nothing changes, and you can undo afterwards.")
            }
        }, confirmButton = { TextButton(onClick = { vm.restore(mode, withVault, withSettings) }) { Text(if (mode == RestoreMode.REPLACE) "Replace" else "Merge") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } })
    }
}

// ── H-Vault ─────────────────────────────────────────────────────────────────

@Composable
fun VaultSettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var auto by remember { mutableStateOf(vm.prefs.vaultAutoLock) }
    var timeout by remember { mutableStateOf(vm.prefs.vaultLockTimeoutSec) }
    val migration by vm.migrationStatus.collectAsStateWithLifecycle()
    val keyLost by vm.health.keyLost.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    CategoryScaffold("H-Vault", onBack, vm) {
        Button(onClick = onOpenVault, Modifier.fillMaxWidth()) { Text("Open H-Vault") }
        Note("H-Vault entries are stored in an encrypted database. The biometric/device-credential prompt controls access in the app; the encryption protects the data on disk. Screenshots are blocked inside H-Vault, and nothing from it appears in notifications or diagnostics.")
        GroupLabel("Auto-lock")
        SwitchRow("Lock when leaving the app", null, auto) { auto = it; vm.prefs.vaultAutoLock = it }
        if (auto) ChoiceGroup(listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes"), timeout, { timeout = it; vm.prefs.vaultLockTimeoutSec = it })
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
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (entries.isEmpty()) Text("Nothing yet.")
            entries.forEach { e -> Text("${if (e.ok) "✓" else "✗"} ${e.source} · ${e.operation} · ${e.durationMs} ms" + (e.message?.let { "\n   $it" } ?: ""), fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } })
}
