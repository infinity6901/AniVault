package com.anivault.core

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed access to the non-secret user preferences ("anivault_preferences").
 * Secrets (tokens, client ids) never live here – see [SecureStore].
 */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {

    val sp: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Emits the key of every preference that changes. */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    // SharedPreferences keeps only a weak reference to listeners, so hold it in a field.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) _changes.tryEmit(key)
    }

    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
    private fun putBool(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    private fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }

    // ── Appearance ─────────────────────────────────────────────────────────
    /** "dark" | "light" | "system" */
    var theme: String
        get() = sp.getString(K_THEME, "dark") ?: "dark"
        set(v) = putString(K_THEME, v)
    /** Material You colors on Android 12+; off keeps the AniVault palette. */
    var dynamicColor: Boolean
        get() = sp.getBoolean(K_DYNAMIC, false)
        set(v) = putBool(K_DYNAMIC, v)

    // ── Library & tracking ─────────────────────────────────────────────────
    var confirmDelete: Boolean
        get() = sp.getBoolean(K_CONFIRM_DELETE, true)
        set(v) = putBool(K_CONFIRM_DELETE, v)
    /** Name of the [com.anivault.data.local.model.WatchStatus] used when adding from Discover. */
    var defaultAddStatus: String
        get() = sp.getString(K_DEFAULT_STATUS, "PLAN_TO_WATCH") ?: "PLAN_TO_WATCH"
        set(v) = putString(K_DEFAULT_STATUS, v)
    /** Mark an entry Completed when progress reaches a known total. */
    var autoCompleteOnLast: Boolean
        get() = sp.getBoolean(K_AUTO_COMPLETE, true)
        set(v) = putBool(K_AUTO_COMPLETE, v)
    var showPlusOne: Boolean
        get() = sp.getBoolean(K_PLUS_ONE, true)
        set(v) = putBool(K_PLUS_ONE, v)
    var showSynopsis: Boolean
        get() = sp.getBoolean(K_SYNOPSIS, true)
        set(v) = putBool(K_SYNOPSIS, v)
    /** "grid" | "list" */
    var libraryView: String
        get() = sp.getString(K_LIB_VIEW, "grid") ?: "grid"
        set(v) = putString(K_LIB_VIEW, v)
    var librarySort: String
        get() = sp.getString(K_LIB_SORT, "UPDATED") ?: "UPDATED"
        set(v) = putString(K_LIB_SORT, v)

    // ── H-Vault ────────────────────────────────────────────────────────────
    var vaultAutoLock: Boolean
        get() = sp.getBoolean(K_VAULT_AUTOLOCK, true)
        set(v) = putBool(K_VAULT_AUTOLOCK, v)
    /** Seconds the app may stay in the background before the vault re-locks (0 = immediately). */
    var vaultLockTimeoutSec: Int
        get() = sp.getInt(K_VAULT_TIMEOUT, 0)
        set(v) = putInt(K_VAULT_TIMEOUT, v)

    // ── Network / providers ────────────────────────────────────────────────
    var networkTimeoutSec: Int
        get() = sp.getInt(K_NET_TIMEOUT, 15)
        set(v) = putInt(K_NET_TIMEOUT, v.coerceIn(5, 60))
    var networkRetries: Int
        get() = sp.getInt(K_NET_RETRIES, 1)
        set(v) = putInt(K_NET_RETRIES, v.coerceIn(0, 3))
    /** When true a successful EMPTY result still tries the next source (default: empty is final). */
    var fallbackOnEmpty: Boolean
        get() = sp.getBoolean(K_FALLBACK_EMPTY, false)
        set(v) = putBool(K_FALLBACK_EMPTY, v)

    // ── Sync ───────────────────────────────────────────────────────────────
    /** "NEWEST" | "REMOTE" | "LOCAL" */
    var conflictPolicy: String
        get() = sp.getString(K_CONFLICT, "NEWEST") ?: "NEWEST"
        set(v) = putString(K_CONFLICT, v)

    // ── Notifications ──────────────────────────────────────────────────────
    var notifEnabled: Boolean
        get() = sp.getBoolean(K_NOTIF, false)
        set(v) = putBool(K_NOTIF, v)
    var notifReminders: Boolean
        get() = sp.getBoolean(K_NOTIF_REMINDERS, true)
        set(v) = putBool(K_NOTIF_REMINDERS, v)
    var notifSync: Boolean
        get() = sp.getBoolean(K_NOTIF_SYNC, true)
        set(v) = putBool(K_NOTIF_SYNC, v)
    var quietStartHour: Int
        get() = sp.getInt(K_QUIET_START, 22)
        set(v) = putInt(K_QUIET_START, v.coerceIn(0, 23))
    var quietEndHour: Int
        get() = sp.getInt(K_QUIET_END, 8)
        set(v) = putInt(K_QUIET_END, v.coerceIn(0, 23))

    /** Resets every preference (not library data, accounts or API sources). */
    fun resetAll() { sp.edit().clear().apply() }

    companion object {
        const val NAME = "anivault_preferences"

        const val K_THEME = "theme"
        const val K_DYNAMIC = "dynamic_color"
        const val K_CONFIRM_DELETE = "confirm_delete"
        const val K_DEFAULT_STATUS = "default_add_status"
        const val K_AUTO_COMPLETE = "auto_complete_on_last"
        const val K_PLUS_ONE = "show_plus_one"
        const val K_SYNOPSIS = "show_synopsis"
        const val K_LIB_VIEW = "library_view"
        const val K_LIB_SORT = "library_sort"
        const val K_VAULT_AUTOLOCK = "vault_auto_lock"
        const val K_VAULT_TIMEOUT = "vault_lock_timeout_s"
        const val K_NET_TIMEOUT = "net_timeout_s"
        const val K_NET_RETRIES = "net_retries"
        const val K_FALLBACK_EMPTY = "fallback_on_empty"
        const val K_CONFLICT = "conflict_policy"
        const val K_NOTIF = "notif_enabled"
        const val K_NOTIF_REMINDERS = "notif_reminders"
        const val K_NOTIF_SYNC = "notif_sync"
        const val K_QUIET_START = "notif_quiet_start"
        const val K_QUIET_END = "notif_quiet_end"

        /** Keys included in (and restored from) encrypted backups. Nothing secret. */
        val BACKUP_KEYS = setOf(
            K_THEME, K_DYNAMIC, K_CONFIRM_DELETE, K_DEFAULT_STATUS, K_AUTO_COMPLETE, K_PLUS_ONE,
            K_SYNOPSIS, K_LIB_VIEW, K_LIB_SORT, K_NET_TIMEOUT, K_NET_RETRIES, K_FALLBACK_EMPTY,
            K_CONFLICT, K_QUIET_START, K_QUIET_END
        )
    }
}
