package com.anivault.ui.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.data.provider.CheckResult
import com.anivault.data.provider.*
import com.anivault.ui.common.*
import com.anivault.ui.theme.AniVaultColors
import androidx.compose.ui.graphics.Color
import com.anivault.ui.theme.dim
import com.anivault.ui.theme.TrackGlassOverlay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
open class ApiSourcesViewModel @Inject constructor(
    val store: SourceStore,
    private val providers: ProviderRepository
) : ViewModel() {
    val sources = store.sources
    val autoFallback = store.autoFallback

    fun setAuto(v: Boolean) = store.setAutoFallback(v)
    fun setEnabled(id: String, v: Boolean) = store.setEnabled(id, v)
    fun move(id: String, d: Int) = store.move(id, d)
    fun remove(id: String) = store.remove(id)
    fun resetDefaults() { store.resetToDefaults(); providers.clearCache() }

    fun test(source: ApiSource, done: (CheckResult) -> Unit) {
        viewModelScope.launch { done(providers.test(source)) }
    }

    /** Tests the form's current values without saving them. A MAL Client ID is held under a unique throw-away key. */
    fun testDraft(type: AnimeApiType, name: String, url: String, clientId: String, existingId: String?, done: (CheckResult) -> Unit) {
        viewModelScope.launch {
            val v = SourceValidator.validate(name, url, type, store.snapshot(), existingId)
            if (!v.ok) { done(CheckResult(CheckKind.UNSUPPORTED_SCHEMA, v.error ?: "Invalid source", 0)); return@launch }
            val tmp = "draft-test-${UUID.randomUUID()}"
            val cid = clientId.ifBlank { existingId?.let { store.clientId(it) }.orEmpty() }
            if (type == AnimeApiType.MAL_V2) store.setClientId(tmp, cid)
            try { done(providers.test(ApiSource(tmp, name.trim(), v.normalizedUrl!!, type, adult = store.adultScope))) }
            finally { store.setClientId(tmp, "") }
        }
    }

    /** Returns an error message, or null when saved. */
    fun save(existing: ApiSource?, type: AnimeApiType, name: String, url: String, clientId: String, lastTest: CheckResult?): String? {
        val v = SourceValidator.validate(name, url, type, store.snapshot(), existing?.id)
        if (!v.ok) return v.error
        val urlChanged = existing != null && (existing.baseUrl != v.normalizedUrl || existing.type != type)
        val id: String
        if (existing == null) {
            id = store.add(name, v.normalizedUrl!!, type, clientId).id
        } else {
            id = existing.id
            // Changing the endpoint invalidates earlier health data and requires a fresh test.
            store.update(existing.copy(name = name.trim(), baseUrl = v.normalizedUrl!!, type = type,
                verified = existing.verified && !urlChanged, lastStatus = if (urlChanged) "" else existing.lastStatus,
                lastError = if (urlChanged) "" else existing.lastError, lastCheckedAt = if (urlChanged) 0L else existing.lastCheckedAt))
            if (clientId.isNotBlank()) store.setClientId(id, clientId)
        }
        if (lastTest != null && lastTest.kind == CheckKind.OK) store.recordCheck(id, lastTest)
        providers.clearCache()
        return null
    }
}

@Composable
fun ApiSourcesScreen(onBack: () -> Unit, embedded: Boolean = false, vm: ApiSourcesViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val auto by vm.autoFallback.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ApiSource?>(null) }
    var adding by remember { mutableStateOf(false) }
    var errorFor by remember { mutableStateOf<ApiSource?>(null) }
    var removing by remember { mutableStateOf<ApiSource?>(null) }
    var testing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmReset by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(title = { Text(if (vm.store.adultScope) "H-Vault API Sources" else "API Sources", fontWeight = FontWeight.Bold) },
                windowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { confirmReset = true }) { Text("Reset") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AniCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    SwitchRow("Auto Fallback", "If a source fails, try the next enabled source in priority order.", auto) { vm.setAuto(it) }
                }
            }
            Text("Priority: top is tried first. Long-press the handle to drag, or use ⋮ → Move up/down.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            sources.forEachIndexed { index, s ->
                key(s.id) {
                    SourceRow(
                        s, index, sources.size, testing = s.id in testing,
                        onToggle = { vm.setEnabled(s.id, it) },
                        onMove = { vm.move(s.id, it) },
                        onEdit = { editing = s },
                        onTest = {
                            testing = testing + s.id
                            vm.test(s) { r -> testing = testing - s.id; message = "${s.name}: ${r.kind.label}" }
                        },
                        onError = { errorFor = s },
                        onRemove = { removing = s }
                    )
                }
            }
            if (sources.isEmpty()) EmptyState("No sources", "Add one, or tap Reset to restore the defaults.")
            Button(onClick = { adding = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add API Source") }
            Text("Health shows only what real connection tests found. “Unverified” means it has not been tested yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
        }
    }

    if (adding || editing != null) SourceSheet(editing, vm, onDone = { adding = false; editing = null; it?.let { m -> message = m } })
    errorFor?.let { s ->
        AlertDialog(onDismissRequest = { errorFor = null }, title = { Text("${s.name}: last error") },
            text = { Text(if (s.lastError.isBlank()) "No error recorded. Run Test to check this source." else s.lastError) },
            confirmButton = { TextButton(onClick = { errorFor = null }) { Text("Close") } })
    }
    removing?.let { s ->
        ConfirmDialog("Remove ${s.name}?", "Its settings and any saved Client ID will be deleted.", "Remove", { vm.remove(s.id); removing = null }, { removing = null }, destructive = true)
    }
    if (confirmReset) ConfirmDialog("Reset API sources?", "Restores the built-in list and order and removes custom sources.", "Reset", { vm.resetDefaults(); confirmReset = false }, { confirmReset = false }, destructive = true)
}

@Composable
private fun SourceRow(
    s: ApiSource, index: Int, count: Int, testing: Boolean,
    onToggle: (Boolean) -> Unit, onMove: (Int) -> Unit, onEdit: () -> Unit, onTest: () -> Unit, onError: () -> Unit, onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var rowHeight by remember { mutableIntStateOf(1) }
    var drag by remember { mutableFloatStateOf(0f) }
    val host = remember(s.baseUrl) { runCatching { java.net.URI(s.baseUrl).host }.getOrNull() ?: s.baseUrl }
    val health = healthOf(s)
    AniCard(Modifier.fillMaxWidth().onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DragHandle, "Reorder ${s.name}, priority ${index + 1} of $count",
                Modifier.padding(horizontal = 8.dp).size(24.dp).pointerInput(s.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag = 0f },
                        onDrag = { change, amount ->
                            change.consume(); drag += amount.y
                            if (drag > rowHeight * 0.6f) { onMove(1); drag -= rowHeight }
                            else if (drag < -rowHeight * 0.6f) { onMove(-1); drag += rowHeight }
                        },
                        onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }
                    )
                },
                tint = MaterialTheme.colorScheme.dim
            )
            Column(Modifier.weight(1f)) {
                Text("${index + 1}. ${s.name}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (testing) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("Testing…", fontSize = 12.sp) }
                    else {
                        Icon(health.icon, null, Modifier.size(14.dp), tint = health.color)
                        Spacer(Modifier.width(4.dp))
                        Text(health.label, fontSize = 12.sp, color = health.color)
                    }
                }
            }
            Switch(checked = s.enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = "Enable ${s.name}" })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More actions for ${s.name}") }
                DropdownMenu(menu, { menu = false }) {
                    if (menu) TrackGlassOverlay()
                    DropdownMenuItem({ Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem({ Text("Test") }, onClick = { menu = false; onTest() })
                    DropdownMenuItem({ Text("Move up") }, enabled = index > 0, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem({ Text("Move down") }, enabled = index < count - 1, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem({ Text("View last error") }, onClick = { menu = false; onError() })
                    DropdownMenuItem({ Text("Remove", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

private class Health(val label: String, val color: androidx.compose.ui.graphics.Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Health comes only from recorded real tests; never invented. */
@Composable
private fun healthOf(s: ApiSource): Health {
    val whenText = if (s.lastCheckedAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastCheckedAt)) else ""
    return when {
        s.lastCheckedAt == 0L -> Health("Unverified", MaterialTheme.colorScheme.dim, Icons.Default.HelpOutline)
        s.verified -> Health("Connected · tested $whenText", AniVaultColors.Success, Icons.Default.CheckCircle)
        else -> Health("${s.lastStatus.ifBlank { "Failed" }} · tested $whenText", AniVaultColors.Error, Icons.Default.ErrorOutline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(existing: ApiSource?, vm: ApiSourcesViewModel, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: AnimeApiType.JIKAN_COMPATIBLE) }
    var clientId by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    var discard by remember { mutableStateOf(false) }
    val hasSavedClientId = remember(existing) { existing?.let { vm.store.hasClientId(it.id) } == true }
    val dirty = name != existing?.name.orEmpty() || url != existing?.baseUrl.orEmpty() || type != (existing?.type ?: AnimeApiType.JIKAN_COMPATIBLE) || clientId.isNotBlank()

    fun invalidate() { result = null; error = null }

    ModalBottomSheet(onDismissRequest = { if (dirty) discard = true else onDone(null) }, containerColor = MaterialTheme.colorScheme.surface, scrimColor = Color.Black.copy(alpha = 0.62f)) {
        TrackGlassOverlay()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (existing == null) "Add API Source" else "Edit API Source", fontWeight = FontWeight.Bold, fontSize = 19.sp)
            OutlinedTextField(name, { name = it; invalidate() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; invalidate() }, label = { Text("Base URL (https)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(typeMenu, { typeMenu = it }) {
                OutlinedTextField(type.label, {}, readOnly = true, label = { Text("API type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(typeMenu, { typeMenu = false }) {
                    AnimeApiType.values().forEach { t -> DropdownMenuItem({ Text(t.label) }, onClick = { type = t; typeMenu = false; invalidate() }) }
                }
            }
            if (type == AnimeApiType.MAL_V2) {
                OutlinedTextField(
                    clientId, { clientId = it; invalidate() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (hasSavedClientId) "Client ID (saved – leave blank to keep)" else "MyAnimeList Client ID") },
                    visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide Client ID" else "Show Client ID") } },
                    supportingText = { Text("Stored encrypted on this device and sent only to ${MalAdapter.MAL_HOST}.") }
                )
            }
            Text("AniVault talks to each type through a dedicated adapter. A source that answers in another format fails the test as “Unsupported response”.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            result?.let { r ->
                val ok = r.kind == CheckKind.OK
                Text((if (ok) "✓ " else "✗ ") + r.kind.label + ": " + r.message, color = if (ok) AniVaultColors.Success else MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    error = null; testing = true; result = null
                    vm.testDraft(type, name, url, clientId, existing?.id) { r -> testing = false; result = r }
                }, enabled = !testing, modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Testing…") } else Text("Test Connection")
            }
            if (existing == null) Text("New sources are saved disabled and unverified. Turn them on after a successful test.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (dirty) discard = true else onDone(null) }, Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
                    val e = vm.save(existing, type, name, url, clientId, result)
                    if (e == null) onDone(if (existing == null) "Added “${name.trim()}” (disabled until you enable it)" else "Saved “${name.trim()}”") else error = e
                }, Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
    if (discard) ConfirmDialog("Discard changes?", "What you entered will be lost.", "Discard", { discard = false; onDone(null) }, { discard = false }, destructive = true)
}

/** The H-Vault list: separate from the main API Sources and used only by H-Vault search. */
@HiltViewModel
class VaultApiSourcesViewModel @Inject constructor(
    store: VaultSourceStore,
    @javax.inject.Named("vault") providers: ProviderRepository
) : ApiSourcesViewModel(store, providers)
