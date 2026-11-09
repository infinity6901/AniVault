package com.anivault.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.dao.WatchHistoryDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.entity.WatchHistoryEntry

@Database(
    entities  = [AnimeEntry::class, HVaultEntry::class, WatchHistoryEntry::class],
    version   = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun hVaultDao(): HVaultDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        /** v3 → v4: rewatch count, last-watched timestamp, and episode watch history table. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN rewatchCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN lastWatchedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "entryId TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "imageUrl TEXT NOT NULL, " +
                        "episode INTEGER NOT NULL, " +
                        "rewatchCount INTEGER NOT NULL, " +
                        "watchedAt INTEGER NOT NULL)"
                )
            }
        }

        /** v2 → v3: the user's own tags. Purely additive. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v1 → v2: provider metadata, dates, sync bookkeeping. Purely additive. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN bannerUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN altTitles TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN format TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN airingStatus TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN sourceName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN remoteIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN startDate INTEGER")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN endDate INTEGER")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN remoteUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN providerScore REAL NOT NULL DEFAULT 0")
                db.execSQL("UPDATE anime_entries SET addedAt = updatedAt")
            }
        }
    }
}
