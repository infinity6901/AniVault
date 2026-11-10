package com.anivault

import android.app.Application
import com.anivault.core.AppPrefs
import com.anivault.data.provider.RedirectGuardInterceptor
import com.anivault.data.provider.SafeDns
import com.anivault.data.sync.SyncWorker
import com.anivault.di.LegacyMigrator
import com.anivault.notify.Notifications
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class AniVaultApp : Application(), coil.ImageLoaderFactory {
    @Inject lateinit var migrator: LegacyMigrator
    @Inject lateinit var prefs: AppPrefs

    /**
     * Covers are cached on disk (512 MB) and in memory, so lists open instantly and work offline once seen.
     * Uses [SafeDns] and [RedirectGuardInterceptor] on the image OkHttpClient so scraped or remote cover URLs
     * cannot be used for SSRF against loopback, LAN, or plain-HTTP endpoints.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .dns(SafeDns)
                .followRedirects(true)
                .followSslRedirects(false)
                .addNetworkInterceptor(RedirectGuardInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.30).strongReferencesEnabled(true).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(512L * 1024 * 1024).build() }
        // Cover CDNs often send short cache lifetimes; the artwork itself rarely changes, so keep it.
        .respectCacheHeaders(false)
        // Reuse any already-decoded bitmap that is big enough, instead of re-decoding for every slightly different size.
        .precision(coil.size.Precision.INEXACT)
        .allowRgb565(true)
        .crossfade(false)
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
