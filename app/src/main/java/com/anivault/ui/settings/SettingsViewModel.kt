package com.anivault.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anivault.BuildConfig
import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.core.VaultSession
import com.anivault.data.backup.*
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.sync.*
import com.anivault.di.DatabaseHealth
import com.anivault.di.LegacyMigrator
import com.anivault.notify.Notifications
import com.anivault.ui.export.DocxExportManager
import com.anivault.ui.export.JsonExportManager
import com.anivault.ui.export.PdfExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    val prefs: AppPrefs,
    val accounts: AccountManager,
    private val sync: SyncManager,
    private val backup: BackupManager,
    private val animeDao: AnimeDao,
    private val hvaultDao: com.anivault.data.local.dao.HVaultDao,
    private val providers: ProviderRepository,
    val diagnostics: DiagnosticsLog,
    val migrator: LegacyMigrator,
    val health: DatabaseHealth,
    val vault: VaultSession,
    private val pdf: PdfExportManager,
    private val docx: DocxExportManager,
    private val json: JsonExportManager
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun say(m: String) { _message.value = m }
    fun consumeMessage() { _message.value = null }

    val accountStates = accounts.states
    val syncing = sync.running
    val migrationStatus = migrator.status

    // ── Accounts ───────────────────────────────────────────────────────────
    fun setClientId(p: AccountProvider, v: String) = accounts.setClientId(p, v)
    fun authorizeUrl(p: AccountProvider): String? = accounts.authorizeUrl(p)
    fun signInKitsu(email: String, password: String) {
        viewModelScope.launch { say(accounts.signInKitsu(email, password) ?: "Connected to Kitsu") }
    }
    fun disconnect(p: AccountProvider) { accounts.disconnect(p); say("Disconnected from ${p.label}. Your local library was not changed.") }
    fun syncNow() {
        viewModelScope.launch {
            val reports = sync.syncAll()
            say(if (reports.isEmpty()) "No connected accounts to sync." else reports.joinToString("\n") { it.summary() })
        }
    }
    fun setConflictPolicy(p: ConflictPolicy) { prefs.conflictPolicy = p.name }

    // ── Backup ─────────────────────────────────────────────────────────────
    var pendingPayload: BackupPayload? = null
        private set
    private val _preview = MutableStateFlow<BackupPreview?>(null)
    val preview: StateFlow<BackupPreview?> = _preview.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _canUndo = MutableStateFlow(backup.canUndoRestore)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    fun exportBackup(uri: Uri, password: String, includeVault: Boolean, includeSettings: Boolean) = launchBusy {
        val chars = password.toCharArray()
        try { backup.export(uri, chars, includeVault, includeSettings); say("Encrypted backup saved.") }
        catch (e: BackupException) { say(e.message ?: "Backup failed.") }
        catch (e: Exception) { say("Backup failed. Nothing was written.") }
        finally { chars.fill('\u0000') }
    }

    fun openBackup(uri: Uri, password: String) = launchBusy {
        val chars = password.toCharArray()
        try { val (p, pv) = backup.open(uri, chars); pendingPayload = p; _preview.value = pv }
        catch (e: BackupException) { say(e.message ?: "Could not open the backup.") }
        catch (e: Exception) { say("Could not open the backup.") }
        finally { chars.fill('\u0000') }
    }

    fun cancelRestore() { pendingPayload = null; _preview.value = null }

    fun restore(mode: RestoreMode, includeVault: Boolean, includeSettings: Boolean) = launchBusy {
        val p = pendingPayload ?: return@launchBusy
        try {
            backup.restore(p, mode, includeVault, includeSettings)
            _canUndo.value = backup.canUndoRestore
            say(if (mode == RestoreMode.REPLACE) "Library replaced from backup." else "Backup merged into your library.")
        } catch (e: Exception) { say("Restore failed. Your library was left unchanged.") }
        cancelRestore()
    }

    fun undoRestore() = launchBusy {
        say(if (backup.undoRestore()) "Restore undone." else "Nothing to undo.")
        _canUndo.value = backup.canUndoRestore
    }

    fun exportList(kind: String) = launchBusy {
        val entries = animeDao.getAll()
        val ok = withContext(Dispatchers.IO) {
            when (kind) { "pdf" -> pdf.exportAnimeToPdf(entries); "docx" -> docx.exportAnimeToDocs(entries); else -> json.exportAnimeJson(entries) }
        }
        say(if (ok) "Saved to Downloads." else "Export failed.")
    }

    /** H-Vault export. The caller must have passed the device lock check first. */
    fun exportVault(kind: String) = launchBusy {
        val entries = hvaultDao.getAll()
        val ok = withContext(Dispatchers.IO) {
            when (kind) { "pdf" -> pdf.exportHVaultToPdf(entries); "docx" -> docx.exportHVaultToDocs(entries); else -> json.exportHVaultJson(entries) }
        }
        say(if (ok) "H-Vault export saved to Downloads." else "Export failed.")
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch { _busy.value = true; try { block() } finally { _busy.value = false } }
    }

    // ── Advanced ───────────────────────────────────────────────────────────
    fun clearCache() { providers.clearCache(); say("Cache cleared.") }
    fun clearDiagnostics() = diagnostics.clear()
    fun diagnosticsText(): String = diagnostics.exportText(BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE)
    fun resetPrefs() { prefs.resetAll(); Notifications.applySettings(ctx, prefs); say("Preferences reset.") }
    fun applyNotifications() = Notifications.applySettings(ctx, prefs)
    fun removeLegacy() { say(if (migrator.removeLegacy()) "Old unencrypted copy removed." else "Couldn't remove the old copy.") }
}
