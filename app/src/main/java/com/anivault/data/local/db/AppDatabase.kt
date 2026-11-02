package com.anivault.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry

@Database(
    entities  = [AnimeEntry::class, HVaultEntry::class],
    version   = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun hVaultDao(): HVaultDao
}
