package com.anivault.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.dao.WatchHistoryDao
import com.anivault.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Startup facts the UI may need to surface (spec §4 H-Vault: "make migration failure recoverable"). */
@Singleton
class DatabaseHealth @Inject constructor() {
    private val _keyLost = MutableStateFlow(false)
    /** True when the device key was unavailable and the unreadable database was set aside. */
    val keyLost: StateFlow<Boolean> = _keyLost.asStateFlow()
    fun markKeyLost() { _keyLost.value = true }
    fun dismissKeyLost() { _keyLost.value = false }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    const val SECURE_DB = "anivault-secure.db"
    const val LEGACY_DB = "anivault.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context, health: DatabaseHealth): AppDatabase {
        SQLiteDatabase.loadLibs(ctx)
        val passphrase = try {
            DatabaseKeyManager.passphrase(ctx)
        } catch (e: Exception) {
            // The Keystore key can't unwrap the stored passphrase. Never crash-loop and never
            // delete: set the unreadable database aside and start a fresh encrypted one.
            DatabaseKeyManager.quarantineUnreadable(ctx, SECURE_DB)
            health.markKeyLost()
            DatabaseKeyManager.passphrase(ctx)
        }
        // SupportFactory retains this byte array for opening the DB lazily; do not zero it here.
        val factory: SupportSQLiteOpenHelper.Factory = SupportFactory(passphrase, null, false)
        return Room.databaseBuilder(ctx, AppDatabase::class.java, SECURE_DB)
            .openHelperFactory(factory)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
    }

    @Provides fun provideAnimeDao(db: AppDatabase): AnimeDao = db.animeDao()
    @Provides fun provideHVaultDao(db: AppDatabase): HVaultDao = db.hVaultDao()
    @Provides fun provideWatchHistoryDao(db: AppDatabase): WatchHistoryDao = db.watchHistoryDao()
}
