package com.anivault.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for whether H-Vault is unlocked. Authentication itself is performed by
 * the UI (see ui/vault/VaultUnlock.kt); this class only records the result and enforces the
 * auto-lock policy. Note: a biometric prompt is an access gate – the data is protected at rest
 * by the SQLCipher database, not by the prompt.
 */
@Singleton
class VaultSession @Inject constructor(private val prefs: AppPrefs) {

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    @Volatile private var backgroundedAt = 0L

    fun unlock() { _unlocked.value = true }
    fun lock() { _unlocked.value = false }

    /** Call from Activity.onStop (not for configuration changes). */
    fun onBackgrounded() { backgroundedAt = SystemClock.elapsedRealtime() }

    /** Call from Activity.onStart. Re-locks when the configured timeout elapsed. */
    fun onForegrounded() {
        val since = backgroundedAt
        backgroundedAt = 0L
        if (since == 0L || !_unlocked.value || !prefs.vaultAutoLock) return
        val elapsedSec = (SystemClock.elapsedRealtime() - since) / 1000L
        if (elapsedSec >= prefs.vaultLockTimeoutSec) lock()
    }
}
