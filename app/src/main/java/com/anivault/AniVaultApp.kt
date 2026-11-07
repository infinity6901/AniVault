package com.anivault

import android.app.Application
import com.anivault.core.AppPrefs
import com.anivault.data.sync.SyncWorker
import com.anivault.di.LegacyMigrator
import com.anivault.notify.Notifications
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AniVaultApp : Application(), coil.ImageLoaderFactory {
    @Inject lateinit var migrator: LegacyMigrator
    @Inject lateinit var prefs: AppPrefs

    /** Covers are cached on disk (512 MB) and in memory, so lists open instantly and work offline once seen. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(512L * 1024 * 1024).build() }
        // Cover CDNs often send short cache lifetimes; the artwork itself rarely changes, so keep it.
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Notifications.applySettings(this, prefs)
        SyncWorker.schedule(this)
        // Copies the old plaintext database into the encrypted one in the background (never blocks launch).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { migrator.migrateIfNeeded() }
    }
}
