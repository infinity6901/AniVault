package com.anivault.di

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.anivault.core.Sanitizer
import com.anivault.data.local.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MigrationStatus {
    /** Nothing has run yet. */
    data object Idle : MigrationStatus
    /** No legacy plaintext database exists (new install, or already removed). */
    data object NotNeeded : MigrationStatus
    data object Running : MigrationStatus
    /** Every legacy row exists in the encrypted database. The legacy copy is still on disk. */
    data class Verified(val animeCount: Int, val vaultCount: Int) : MigrationStatus
    data class Failed(val message: String) : MigrationStatus
}

/**
 * Copies the pre-encryption plaintext database (anivault.db) into the SQLCipher database.
 *
 *  - Runs in the background; a failure never blocks or crashes startup.
 *  - Copy uses INSERT-IGNORE so rows the user already created/edited in the new database win.
 *  - The legacy file is never deleted automatically. [removeLegacy] is only allowed once the copy
 *    has been verified, and is triggered by the user from Settings → H-Vault.
 */
@Singleton
class LegacyMigrator @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase
) {
    private val prefs = ctx.getSharedPreferences(DatabaseKeyManager.PREFS, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<MigrationStatus>(MigrationStatus.Idle)
    val status: StateFlow<MigrationStatus> = _status.asStateFlow()

    fun legacyFile(): File = ctx.getDatabasePath(DatabaseModule.LEGACY_DB)
    fun legacyExists(): Boolean = legacyFile().exists()

    suspend fun migrateIfNeeded() {
        if (!legacyExists()) { _status.value = MigrationStatus.NotNeeded; return }
        migrate()
    }

    suspend fun migrate() {
        if (!legacyExists()) { _status.value = MigrationStatus.NotNeeded; return }
        _status.value = MigrationStatus.Running
        val legacy = Room.databaseBuilder(ctx, AppDatabase::class.java, DatabaseModule.LEGACY_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        try {
            val anime = legacy.animeDao().getAll()
            val vault = legacy.hVaultDao().getAll()
            db.withTransaction {
                db.animeDao().insertAllIgnore(anime)
                db.hVaultDao().insertAllIgnore(vault)
            }
            val animeIds = db.animeDao().getAll().map { it.id }.toHashSet()
            val vaultIds = db.hVaultDao().getAll().map { it.id }.toHashSet()
            val missing = anime.count { it.id !in animeIds } + vault.count { it.id !in vaultIds }
            if (missing == 0) {
                prefs.edit().putBoolean(KEY_VERIFIED, true).apply()
                _status.value = MigrationStatus.Verified(anime.size, vault.size)
            } else {
                _status.value = MigrationStatus.Failed("$missing entries could not be copied. The original database was kept.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = MigrationStatus.Failed(
                "Could not copy your existing data into encrypted storage. The original database was kept. " +
                    Sanitizer.clean(e.message, 100)
            )
        } finally {
            runCatching { legacy.close() }
        }
    }

    fun canRemoveLegacy(): Boolean = legacyExists() && prefs.getBoolean(KEY_VERIFIED, false) &&
        _status.value is MigrationStatus.Verified

    /**
     * Deletes the plaintext copy. Only after verification. NOTE: this removes the file; flash
     * storage may retain remnants, so AniVault does not claim secure erasure.
     */
    fun removeLegacy(): Boolean {
        if (!canRemoveLegacy()) return false
        val ok = ctx.deleteDatabase(DatabaseModule.LEGACY_DB)
        if (ok) _status.value = MigrationStatus.NotNeeded
        return ok
    }

    private companion object { const val KEY_VERIFIED = "legacy_verified_v2" }
}
