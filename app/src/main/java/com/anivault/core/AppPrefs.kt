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
    /** Colour palette key, see [com.anivault.ui.theme.Palette]. Falls back to the old Material You switch. */
    var palette: String
        get() = sp.getString(K_PALETTE, null) ?: if (sp.getBoolean(K_DYNAMIC, false)) "dynamic" else "anivault"
        set(v) = putString(K_PALETTE, v)

    // ── Liquid Glass Customization ─────────────────────────────────────────
    /** "aurora" | "crystal" | "sakura" | "emerald" | "amber" | "obsidian" */
    var glassTint: String
        get() = sp.getString(K_GLASS_TINT, "aurora") ?: "aurora"
        set(v) = putString(K_GLASS_TINT, v)
    /** Panel frosted opacity percentage (10..65). */
    var glassOpacity: Int
        get() = sp.getInt(K_GLASS_OPACITY, 24).coerceIn(10, 65)
        set(v) = putInt(K_GLASS_OPACITY, v.coerceIn(10, 65))
    /** Specular rim & refraction highlight intensity (0..100). */
    var glassSpecular: Int
        get() = sp.getInt(K_GLASS_SPECULAR, 78).coerceIn(0, 100)
        set(v) = putInt(K_GLASS_SPECULAR, v.coerceIn(0, 100))
    /** Backdrop Gaussian blur radius in dp (0..40). */
    var glassBlur: Int
        get() = sp.getInt(K_GLASS_BLUR, 22).coerceIn(0, 40)
        set(v) = putInt(K_GLASS_BLUR, v.coerceIn(0, 40))
    /** Animate floating refractive light orbs behind glass panels. */
    var glassAnimated: Boolean
        get() = sp.getBoolean(K_GLASS_ANIMATED, true)
        set(v) = putBool(K_GLASS_ANIMATED, v)
    /** Render bottom navigation bar as a floating Liquid Glass capsule dock. */
    var glassFloatingNav: Boolean
        get() = sp.getBoolean(K_GLASS_FLOATING_NAV, true)
        set(v) = putBool(K_GLASS_FLOATING_NAV, v)
    /** Render homescreen widgets with the Liquid Glass canvas engine. */
    var widgetGlassEnabled: Boolean
        get() = sp.getBoolean(K_WIDGET_GLASS, true)
        set(v) = putBool(K_WIDGET_GLASS, v)
    /** Homescreen widget glass opacity percentage (15..85). */
    var widgetGlassOpacity: Int
        get() = sp.getInt(K_WIDGET_GLASS_OPACITY, 42).coerceIn(15, 85)
        set(v) = putInt(K_WIDGET_GLASS_OPACITY, v.coerceIn(15, 85))
    /** Tint the Progress Widget's liquid glass glow with the current anime's cover art. */
    var widgetGlassMatchCover: Boolean
        get() = sp.getBoolean(K_WIDGET_GLASS_COVER, true)
        set(v) = putBool(K_WIDGET_GLASS_COVER, v)

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
    /** "compact" (4-col) | "standard" (3-col) | "large" (2-col) */
    var gridDensity: String
        get() = sp.getString(K_GRID_DENSITY, "standard") ?: "standard"
        set(v) = putString(K_GRID_DENSITY, v)
    /** "none" | "rating" | "unwatched" */
    var posterBadge: String
        get() = sp.getString(K_POSTER_BADGE, "rating") ?: "rating"
        set(v) = putString(K_POSTER_BADGE, v)
    /** Enable horizontal swipe actions (+1 ep / cycle status) in list mode. */
    var swipeGestures: Boolean
        get() = sp.getBoolean(K_SWIPE_GESTURES, true)
        set(v) = putBool(K_SWIPE_GESTURES, v)
    /** JSON array of pinned smart lists in LibraryScreen. */
    var savedSmartLists: String
        get() = sp.getString(K_SMART_LISTS, "") ?: ""
        set(v) = putString(K_SMART_LISTS, v)

    // ── H-Vault ────────────────────────────────────────────────────────────
    var vaultAutoLock: Boolean
        get() = sp.getBoolean(K_VAULT_AUTOLOCK, true)
        set(v) = putBool(K_VAULT_AUTOLOCK, v)
    /** Seconds the app may stay in the background before the vault re-locks (0 = immediately). */
    var vaultLockTimeoutSec: Int
        get() = sp.getInt(K_VAULT_TIMEOUT, 0)
        set(v) = putInt(K_VAULT_TIMEOUT, v)
    /** Query uncensored scrapers (Hanime API, WatchHentai/DooPlay, Nhentai) BEFORE censored API catalogs (AniList/MAL). */
    var vaultScraperPriority: Boolean
        get() = sp.getBoolean(K_VAULT_SCRAPER_PRIORITY, true)
        set(v) = putBool(K_VAULT_SCRAPER_PRIORITY, v)
    /** Automatically replace censored AniList/MAL/Kitsu covers with uncensored scraper covers when moving or searching in H-Vault. */
    var vaultAutoUncensorCovers: Boolean
        get() = sp.getBoolean(K_VAULT_AUTO_UNCENSOR, true)
        set(v) = putBool(K_VAULT_AUTO_UNCENSOR, v)
    /** Optional comma/newline-separated custom Aniyomi-compatible WordPress/DooPlay/Madara mirror base URLs. */
    var vaultCustomMirrors: String
        get() = sp.getString(K_VAULT_MIRRORS, "") ?: ""
        set(v) = putString(K_VAULT_MIRRORS, v)
    /** H-Vault library layout: "grid" | "list" (defaults to libraryView when unset). */
    var vaultView: String
        get() = sp.getString(K_VAULT_VIEW, null) ?: libraryView
        set(v) = putString(K_VAULT_VIEW, v)
    /** H-Vault library sort order ("UPDATED" | "TITLE" | "RATING" | "PROGRESS"). */
    var vaultSort: String
        get() = sp.getString(K_VAULT_SORT, "UPDATED") ?: "UPDATED"
        set(v) = putString(K_VAULT_SORT, v)
    /** H-Vault status tab filter ("ALL" or WatchStatus.name). */
    var vaultStatusFilter: String
        get() = sp.getString(K_VAULT_STATUS, "ALL") ?: "ALL"
        set(v) = putString(K_VAULT_STATUS, v)
    /** H-Vault series grouping toggle. */
    var vaultGrouped: Boolean
        get() = sp.getBoolean(K_VAULT_GROUPED, false)
        set(v) = putBool(K_VAULT_GROUPED, v)
    /** H-Vault uncensored-only filter toggle. */
    var vaultUncensoredOnly: Boolean
        get() = sp.getBoolean(K_VAULT_UNCENSORED_ONLY, false)
        set(v) = putBool(K_VAULT_UNCENSORED_ONLY, v)
    /** H-Vault stats summary banner visibility. */
    var vaultShowStats: Boolean
        get() = sp.getBoolean(K_VAULT_SHOW_STATS, true)
        set(v) = putBool(K_VAULT_SHOW_STATS, v)

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
    /** Deleting a title here also deletes it on the connected accounts. */
    var deleteOnAccounts: Boolean
        get() = sp.getBoolean(K_DELETE_REMOTE, true)
        set(v) = putBool(K_DELETE_REMOTE, v)

    /** Titles moved to H-Vault get a tag in their account notes (and are made private where supported). */
    var markVaultOnAccounts: Boolean
        get() = sp.getBoolean(K_MARK_VAULT, true)
        set(v) = putBool(K_MARK_VAULT, v)

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
    /** Alerts when a new episode of a show you're watching has aired. */
    var notifAiring: Boolean
        get() = sp.getBoolean(K_NOTIF_AIRING, true)
        set(v) = putBool(K_NOTIF_AIRING, v)
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
        const val K_PALETTE = "palette"
        const val K_GLASS_TINT = "glass_tint"
        const val K_GLASS_OPACITY = "glass_opacity"
        const val K_GLASS_SPECULAR = "glass_specular"
        const val K_GLASS_BLUR = "glass_blur"
        const val K_GLASS_ANIMATED = "glass_animated"
        const val K_GLASS_FLOATING_NAV = "glass_floating_nav"
        const val K_WIDGET_GLASS = "widget_glass_enabled"
        const val K_WIDGET_GLASS_OPACITY = "widget_glass_opacity"
        const val K_WIDGET_GLASS_COVER = "widget_glass_cover"
        const val K_CONFIRM_DELETE = "confirm_delete"
        const val K_DEFAULT_STATUS = "default_add_status"
        const val K_AUTO_COMPLETE = "auto_complete_on_last"
        const val K_PLUS_ONE = "show_plus_one"
        const val K_SYNOPSIS = "show_synopsis"
        const val K_LIB_VIEW = "library_view"
        const val K_LIB_SORT = "library_sort"
        const val K_GRID_DENSITY = "grid_density"
        const val K_POSTER_BADGE = "poster_badge"
        const val K_SWIPE_GESTURES = "swipe_gestures"
        const val K_SMART_LISTS = "saved_smart_lists"
        const val K_VAULT_AUTOLOCK = "vault_auto_lock"
        const val K_VAULT_TIMEOUT = "vault_lock_timeout_s"
        const val K_VAULT_SCRAPER_PRIORITY = "vault_scraper_priority"
        const val K_VAULT_AUTO_UNCENSOR = "vault_auto_uncensor_covers"
        const val K_VAULT_MIRRORS = "vault_custom_mirrors"
        const val K_VAULT_VIEW = "vault_view"
        const val K_VAULT_SORT = "vault_sort"
        const val K_VAULT_STATUS = "vault_status_filter"
        const val K_VAULT_GROUPED = "vault_grouped"
        const val K_VAULT_UNCENSORED_ONLY = "vault_uncensored_only"
        const val K_VAULT_SHOW_STATS = "vault_show_stats"
        const val K_NET_TIMEOUT = "net_timeout_s"
        const val K_NET_RETRIES = "net_retries"
        const val K_FALLBACK_EMPTY = "fallback_on_empty"
        const val K_CONFLICT = "conflict_policy"
        const val K_DELETE_REMOTE = "delete_on_accounts"
        const val K_MARK_VAULT = "mark_vault_on_accounts"
        const val K_NOTIF = "notif_enabled"
        const val K_NOTIF_REMINDERS = "notif_reminders"
        const val K_NOTIF_AIRING = "notif_airing"
        const val K_NOTIF_SYNC = "notif_sync"
        const val K_QUIET_START = "notif_quiet_start"
        const val K_QUIET_END = "notif_quiet_end"

        /** Keys included in (and restored from) encrypted backups. Nothing secret. */
        val BACKUP_KEYS = setOf(
            K_THEME, K_DYNAMIC, K_PALETTE,
            K_GLASS_TINT, K_GLASS_OPACITY, K_GLASS_SPECULAR, K_GLASS_BLUR, K_GLASS_ANIMATED, K_GLASS_FLOATING_NAV,
            K_WIDGET_GLASS, K_WIDGET_GLASS_OPACITY, K_WIDGET_GLASS_COVER,
            K_CONFIRM_DELETE, K_DEFAULT_STATUS, K_AUTO_COMPLETE, K_PLUS_ONE,
            K_SYNOPSIS, K_LIB_VIEW, K_LIB_SORT, K_GRID_DENSITY, K_POSTER_BADGE, K_SWIPE_GESTURES, K_SMART_LISTS, K_VAULT_SCRAPER_PRIORITY, K_VAULT_AUTO_UNCENSOR, K_VAULT_MIRRORS, K_VAULT_VIEW, K_VAULT_SORT, K_VAULT_STATUS, K_VAULT_GROUPED, K_VAULT_UNCENSORED_ONLY, K_VAULT_SHOW_STATS,
            K_NET_TIMEOUT, K_NET_RETRIES, K_FALLBACK_EMPTY,
            K_CONFLICT, K_DELETE_REMOTE, K_MARK_VAULT, K_QUIET_START, K_QUIET_END
        )

        val BOOL_KEYS = setOf(
            K_DYNAMIC, K_GLASS_ANIMATED, K_GLASS_FLOATING_NAV, K_WIDGET_GLASS, K_WIDGET_GLASS_COVER,
            K_CONFIRM_DELETE, K_SWIPE_GESTURES, K_AUTO_COMPLETE, K_PLUS_ONE, K_SYNOPSIS, K_VAULT_AUTOLOCK, K_VAULT_SCRAPER_PRIORITY, K_VAULT_AUTO_UNCENSOR, K_VAULT_GROUPED, K_VAULT_UNCENSORED_ONLY, K_VAULT_SHOW_STATS,
            K_FALLBACK_EMPTY, K_DELETE_REMOTE, K_MARK_VAULT, K_NOTIF, K_NOTIF_REMINDERS, K_NOTIF_AIRING, K_NOTIF_SYNC
        )

        val INT_KEYS = setOf(
            K_GLASS_OPACITY, K_GLASS_SPECULAR, K_GLASS_BLUR, K_WIDGET_GLASS_OPACITY,
            K_VAULT_TIMEOUT, K_NET_TIMEOUT, K_NET_RETRIES, K_QUIET_START, K_QUIET_END
        )
    }
}
