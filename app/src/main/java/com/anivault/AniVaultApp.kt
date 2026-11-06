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
class AniVaultApp : Application() {
    @Inject lateinit var migrator: LegacyMigrator
    @Inject lateinit var prefs: AppPrefs

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Notifications.applySettings(this, prefs)
        SyncWorker.schedule(this)
        // Copies the old plaintext database into the encrypted one in the background (never blocks launch).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { migrator.migrateIfNeeded() }
    }
}
