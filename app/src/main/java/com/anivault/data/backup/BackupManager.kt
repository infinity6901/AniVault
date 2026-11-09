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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rows and settings that were replaced by the last restore, kept in memory so it can be undone this session.
 * Each field is non-null ONLY when that section was actually modified by the restore.
 */
private class Snapshot(
    val anime: List<AnimeEntry>?,
    val hvault: List<HVaultEntry>?,
    val settings: Map<String, String>?,
    val secrets: Map<String, String>? = null
)

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

    suspend fun export(uri: Uri, password: CharArray, includeHVault: Boolean, includeSettings: Boolean, includeSecrets: Boolean = false, includeLibrary: Boolean = true) = withContext(Dispatchers.IO) {
        val payload = BackupPayload(
            appVersion = BuildConfig.VERSION_NAME,
            anime = if (includeLibrary) animeDao.getAll().map { it.copy(pendingSync = false) } else emptyList(),
            libraryExcluded = !includeLibrary,
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
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_BACKUP_BYTES) }
            ?: throw BackupException("Unable to read the selected backup file.")
        val payload = BackupCodec.decrypt(bytes, password)
        payload to BackupPlanner.preview(payload, animeDao.getAll(), payload.hvault?.let { vaultDao.getAll() })
    }

    /**
     * Applies [payload] with full rollback across database, settings, and credentials: if anything
     * fails, every touched section is restored to its pre-restore state.
     */
    suspend fun restore(payload: BackupPayload, mode: RestoreMode, includeHVault: Boolean, includeSettings: Boolean, includeSecrets: Boolean = false, includeLibrary: Boolean = true) =
        withContext(Dispatchers.IO) {
            val touchLibrary = includeLibrary && !payload.libraryExcluded
            val before = Snapshot(
                anime = if (touchLibrary) animeDao.getAll() else null,
                hvault = if (includeHVault && payload.hvault != null) vaultDao.getAll() else null,
                settings = if (includeSettings && payload.settings != null) readSettings() else null,
                secrets = if (includeSecrets && payload.secrets != null) accounts.exportCredentials() else null
            )
            try {
                db.withTransaction {
                    when (mode) {
                        RestoreMode.REPLACE -> {
                            if (before.anime != null) { animeDao.deleteAll(); animeDao.upsertAll(payload.anime) }
                            if (before.hvault != null) { vaultDao.deleteAll(); vaultDao.upsertAll(payload.hvault!!) }
                        }
                        RestoreMode.MERGE -> {
                            if (before.anime != null) animeDao.upsertAll(BackupPlanner.mergeAnime(payload, before.anime))
                            if (before.hvault != null) vaultDao.upsertAll(BackupPlanner.mergeVault(payload, before.hvault))
                        }
                    }
                }
                if (before.settings != null) writeSettings(payload.settings!!, clearMissing = mode == RestoreMode.REPLACE)
                if (before.secrets != null) accounts.importCredentials(payload.secrets!!)
                snapshot = before
            } catch (e: Throwable) {
                // Roll back any already-committed sections so a partial failure never leaves mixed state.
                runCatching {
                    db.withTransaction {
                        if (before.anime != null) { animeDao.deleteAll(); animeDao.upsertAll(before.anime) }
                        if (before.hvault != null) { vaultDao.deleteAll(); vaultDao.upsertAll(before.hvault) }
                    }
                }
                runCatching { before.settings?.let { writeSettings(it, clearMissing = true) } }
                runCatching { before.secrets?.let { accounts.importCredentials(it) } }
                throw e
            }
        }

    /** Puts back only the sections that the last restore actually modified. */
    suspend fun undoRestore(): Boolean = withContext(Dispatchers.IO) {
        val s = snapshot ?: return@withContext false
        db.withTransaction {
            if (s.anime != null) { animeDao.deleteAll(); animeDao.upsertAll(s.anime) }
            if (s.hvault != null) { vaultDao.deleteAll(); vaultDao.upsertAll(s.hvault) }
        }
        s.settings?.let { writeSettings(it, clearMissing = true) }
        s.secrets?.let { accounts.importCredentials(it) }
        snapshot = null
        true
    }

    private fun readSettings(): Map<String, String> =
        prefs.sp.all.filterKeys { it in AppPrefs.BACKUP_KEYS }.mapValues { it.value.toString() }

    private fun writeSettings(values: Map<String, String>, clearMissing: Boolean = false) {
        val e = prefs.sp.edit()
        if (clearMissing) {
            AppPrefs.BACKUP_KEYS.filter { it !in values }.forEach { e.remove(it) }
        }
        values.filterKeys { it in AppPrefs.BACKUP_KEYS }.forEach { (k, v) ->
            when (k) {
                in AppPrefs.BOOL_KEYS -> if (v == "true" || v == "false") e.putBoolean(k, v.toBoolean())
                in AppPrefs.INT_KEYS -> v.toIntOrNull()?.let { e.putInt(k, it) }
                else -> e.putString(k, v)
            }
        }
        e.apply()
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw BackupException("This backup file is too large.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    }
}
