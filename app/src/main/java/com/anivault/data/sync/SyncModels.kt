package com.anivault.data.sync

import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.NormalizedAnime

enum class AccountProvider(val label: String, val key: String, val signInHint: String) {
    ANILIST("AniList", "anilist", "Sign in through your browser. Needs your own AniList client ID."),
    MAL("MyAnimeList", "mal", "Sign in through your browser (PKCE). Needs your own MyAnimeList client ID."),
    KITSU("Kitsu", "kitsu", "Sign in with your Kitsu email and password. They are sent only to Kitsu and never stored.");

    /** Redirect URI the user must register with the provider (AniList / MAL). */
    val redirectUri: String get() = "anivault://auth/$key"
    /** True when the provider requires a client id configured by the user before sign-in. */
    val needsClientId: Boolean get() = this != KITSU

    companion object {
        fun fromKey(key: String?): AccountProvider? = values().firstOrNull { it.key == key }
    }
}

/** How to settle a difference when BOTH the device and the account changed since the last sync. */
enum class ConflictPolicy(val label: String, val explanation: String) {
    NEWEST("Newest change wins", "If both sides changed, the version edited most recently is kept."),
    REMOTE("Account wins", "If both sides changed, the account's version replaces this device's."),
    LOCAL("This device wins", "If both sides changed, this device's version is sent to the account.");

    companion object {
        fun parse(value: String?): ConflictPolicy = values().firstOrNull { it.name == value } ?: NEWEST
    }
}

data class AccountState(
    val provider: AccountProvider,
    val connected: Boolean = false,
    val accountName: String? = null,
    val clientIdSet: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null,
    /** Token expired or revoked: the user must sign in again. */
    val needsReauth: Boolean = false
) {
    /** The "Connect" button is only offered when the provider could actually work. */
    val canConnect: Boolean get() = !provider.needsClientId || clientIdSet
}

/** One line of the user's list on a remote service, already normalized. */
data class RemoteListEntry(
    val remoteMediaId: String,
    /** Kitsu's library-entry id (needed to update); null elsewhere. */
    val remoteEntryId: String?,
    val status: WatchStatus,
    val progress: Int,
    /** 0–10, 0 = unrated. */
    val score10: Float,
    val startedAt: Long?,
    val completedAt: Long?,
    /** Epoch millis of the remote's own last-modified time (0 if unknown). */
    val updatedAt: Long,
    val media: NormalizedAnime
)
