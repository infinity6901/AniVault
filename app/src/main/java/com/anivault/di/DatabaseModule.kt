package com.anivault.di

import android.content.Context
import androidx.room.Room
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        // Do not silently erase a user's vault when the schema changes.
        // Add explicit Room migrations before incrementing AppDatabase.version.
        Room.databaseBuilder(ctx, AppDatabase::class.java, "anivault.db")
            .build()

    @Provides
    fun provideAnimeDao(db: AppDatabase): AnimeDao = db.animeDao()

    @Provides
    fun provideHVaultDao(db: AppDatabase): HVaultDao = db.hVaultDao()
}
