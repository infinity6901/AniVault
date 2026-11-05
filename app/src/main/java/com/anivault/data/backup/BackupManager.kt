package com.anivault.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.anivault.BuildConfig
import com.anivault.core.AppPrefs
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.db.AppDatabase
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Rows that were replaced by the last restore, kept in memory so it can be undone this session. */
private class Snapshot(val anime: List<AnimeEntry>, val hvault: List<HVaultEntry>?, val settings: Map<String, String>?, val secrets: Map<String, String>? = null)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
    private val animeDao: AnimeDao,
    private val vaultDao: HVaultDao,
    private val prefs: AppPrefs,
    private val accounts: com.anivault.data.sync.AccountManager
) {
    @Volatile private var snapshot: Snapshot? = null
    val canUndoRestore: Boolean get() = snapshot != null

    suspend fun export(uri: Uri, password: CharArray, includeHVault: Boolean, includeSettings: Boolean, includeSecrets: Boolean = false) = withContext(Dispatchers.IO) {
        val payload = BackupPayload(
            appVersion = BuildConfig.VERSION_NAME,
            anime = animeDao.getAll().map { it.copy(pendingSync = false) },
            hvault = if (includeHVault) vaultDao.getAll() else null,
            settings = if (includeSettings) readSettings() else null,
            secrets = if (includeSecrets) accounts.exportCredentials() else null
        )
        val bytes = BackupCodec.encrypt(payload, password)
        // "wt" truncates; encrypt happens first so a bad password can never leave an empty file behind.
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw BackupException("Could not write to the chosen location.")
    }

    /** Decrypts and compares with the library. Nothing is changed. */
    suspend fun open(uri: Uri, password: CharArray): Pair<BackupPayload, BackupPreview> = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupException("Unable to read the selected backup file.")
        val payload = BackupCodec.decrypt(bytes, password)
        payload to BackupPlanner.preview(payload, animeDao.getAll(), payload.hvault?.let { vaultDao.getAll() })
    }

    /**
     * Applies [payload] inside one database transaction: if anything fails the library is exactly as it was.
     * H-Vault rows are only touched when [includeHVault] is true AND the backup contains them.
     */
    suspend fun restore(payload: BackupPayload, mode: RestoreMode, includeHVault: Boolean, includeSettings: Boolean, includeSecrets: Boolean = false) =
        withContext(Dispatchers.IO) {
            val before = Snapshot(
                animeDao.getAll(),
                if (includeHVault && payload.hvault != null) vaultDao.getAll() else null,
                if (includeSettings && payload.settings != null) readSettings() else null,
                if (includeSecrets && payload.secrets != null) accounts.exportCredentials() else null
            )
            db.withTransaction {
                when (mode) {
                    RestoreMode.REPLACE -> {
                        animeDao.deleteAll()
                        animeDao.upsertAll(payload.anime)
                        if (before.hvault != null) { vaultDao.deleteAll(); vaultDao.upsertAll(payload.hvault!!) }
                    }
                    RestoreMode.MERGE -> {
                        animeDao.upsertAll(BackupPlanner.mergeAnime(payload, before.anime))
                        if (before.hvault != null) vaultDao.upsertAll(BackupPlanner.mergeVault(payload, before.hvault))
                    }
                }
            }
            if (before.settings != null) writeSettings(payload.settings!!)
            if (before.secrets != null) accounts.importCredentials(payload.secrets!!)
            snapshot = before
        }

    /** Puts back what the last restore replaced. */
    suspend fun undoRestore(): Boolean = withContext(Dispatchers.IO) {
        val s = snapshot ?: return@withContext false
        db.withTransaction {
            animeDao.deleteAll(); animeDao.upsertAll(s.anime)
            if (s.hvault != null) { vaultDao.deleteAll(); vaultDao.upsertAll(s.hvault) }
        }
        s.settings?.let { writeSettings(it) }
        s.secrets?.let { accounts.importCredentials(it) }
        snapshot = null
        true
    }

    private fun readSettings(): Map<String, String> =
        prefs.sp.all.filterKeys { it in AppPrefs.BACKUP_KEYS }.mapValues { it.value.toString() }

    private fun writeSettings(values: Map<String, String>) {
        val e = prefs.sp.edit()
        values.filterKeys { it in AppPrefs.BACKUP_KEYS }.forEach { (k, v) ->
            val current = prefs.sp.all[k]
            when {
                v == "true" || v == "false" -> e.putBoolean(k, v.toBoolean())
                current is Int || (current == null && v.toIntOrNull() != null) -> e.putInt(k, v.toIntOrNull() ?: return@forEach)
                else -> e.putString(k, v)
            }
        }
        e.apply()
    }
}
