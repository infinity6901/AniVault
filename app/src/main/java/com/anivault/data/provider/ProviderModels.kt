package com.anivault.data.provider

/** Why a provider call failed. Drives fallback, cooldowns and what the UI says. */
enum class FailureKind(val label: String) {
    NETWORK("Network error"),
    TIMEOUT("Timed out"),
    RATE_LIMITED("Rate limited"),
    AUTH("Authentication required"),
    SERVER("Server error"),
    /** Response arrived but is not in the shape this adapter expects. */
    MALFORMED("Unexpected response"),
    /** The provider does not support the requested operation. */
    UNSUPPORTED("Not supported"),
    /** Other 4xx (bad request / wrong path). */
    CLIENT("Request rejected")
}

class ProviderException(
    val kind: FailureKind,
    message: String,
    val httpCode: Int? = null,
    val retryAfterMs: Long? = null
) : Exception(message)

enum class BrowseSection(val label: String) {
    TRENDING("Trending"),
    POPULAR("Popular"),
    SEASONAL("This season"),
    TOP_RATED("Top rated"),
    RECENTLY_UPDATED("Recently updated")
}

enum class AnimeApiType(val label: String) {
    ANILIST_GRAPHQL("AniList (GraphQL compatible)"),
    MAL_V2("MyAnimeList v2"),
    JIKAN_COMPATIBLE("Jikan-compatible REST"),
    KITSU_JSON_API("Kitsu (JSON:API)"),
    JIKAN_EDGE("Jikan-Edge (v1 REST)")
}

/** A configured provider (spec §5/§6). Credentials are NOT stored here – see SourceStore. */
data class ApiSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val type: AnimeApiType,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /** True only after a successful, schema-validating connection test. */
    val verified: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val lastStatus: String = "",
    val lastError: String = "",
    /** H-Vault sources ask the catalog for adult-rated titles instead of hiding them. */
    val adult: Boolean = false
)

/** The one model every adapter produces (spec §7 "Normalized anime model"). */
data class NormalizedAnime(
    /** ApiSource.id of the provider that returned this. */
    val provider: String,
    val providerName: String,
    val providerId: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val coverUrl: String = "",
    val bannerUrl: String = "",
    val synopsis: String = "",
    val year: Int? = null,
    val season: String = "",
    /** Epoch millis (UTC midnight) or null. */
    val startDate: Long? = null,
    val format: String = "",
    val episodes: Int = 0,
    val durationMinutes: Int = 0,
    val status: String = "",
    val genres: List<String> = emptyList(),
    /** Community score on a 0–10 scale, or null. */
    val score: Float? = null,
    /** ISO country of origin (JP/CN/KR/TW) when the provider reports it, else blank. */
    val country: String = "",
    /** True when the service marks the title as adult (AniList isAdult, MAL nsfw/rx, Kitsu R18/nsfw). */
    val adult: Boolean = false,
    /** Cross-service ids: "anilist", "mal", "kitsu". */
    val remoteIds: Map<String, String> = emptyMap()
) {
    /** Canonical AniVault id (local primary key). */
    val canonicalId: String get() = "$provider:$providerId"
}

data class PagedResult(val items: List<NormalizedAnime>, val hasNext: Boolean)

/** Client-side filters applied to any provider's results so behavior is identical across adapters. */
data class SearchFilters(
    val year: Int? = null,
    val format: String? = null,
    val minScore: Float? = null,
    val genres: Set<String> = emptySet(),
    /** Normalised airing status: "Releasing", "Finished", "Not yet released" or null for any. */
    val airing: String? = null,
    /** Country of origin code (JP, CN, KR, TW); applied only to results that report one. */
    val country: String? = null,
    val minEpisodes: Int? = null,
    val maxEpisodes: Int? = null
) {
    val activeCount: Int get() = listOfNotNull(year, format, minScore, airing, country, minEpisodes, maxEpisodes).size + (if (genres.isEmpty()) 0 else 1)

    fun apply(items: List<NormalizedAnime>): List<NormalizedAnime> = items.filter { a ->
        (year == null || a.year == year) &&
            (format.isNullOrBlank() || a.format.equals(format, ignoreCase = true)) &&
            (minScore == null || (a.score ?: 0f) >= minScore) &&
            (genres.isEmpty() || a.genres.isEmpty() || genres.all { g -> a.genres.any { it.equals(g, ignoreCase = true) } }) &&
            (airing == null || a.status.isBlank() || normalizeAiring(a.status) == airing) &&
            (country == null || a.country.isBlank() || a.country.equals(country, ignoreCase = true)) &&
            (minEpisodes == null || a.episodes >= minEpisodes) &&
            (maxEpisodes == null || a.episodes == 0 || a.episodes <= maxEpisodes)
    }

    companion object {
        fun normalizeAiring(raw: String): String {
            val s = raw.lowercase()
            return when {
                "finish" in s || "complet" in s -> "Finished"
                "not yet" in s || "not_yet" in s || "upcoming" in s || "tba" in s || "unreleased" in s -> "Not yet released"
                "releas" in s || "airing" in s || "ongoing" in s || "current" in s -> "Releasing"
                else -> ""
            }
        }
    }
}

enum class CheckKind(val label: String) {
    OK("Connected"),
    UNSUPPORTED_SCHEMA("Unsupported response"),
    AUTH_REQUIRED("Authentication required"),
    RATE_LIMITED("Rate limited"),
    UNREACHABLE("Unreachable")
}

data class CheckResult(val kind: CheckKind, val message: String, val durationMs: Long)

interface ProviderAdapter {
    /** Browse sections this provider can serve. Empty = search only. */
    val sections: Set<BrowseSection>
    suspend fun search(query: String, page: Int): PagedResult
    suspend fun browse(section: BrowseSection, page: Int): PagedResult
    /** Browses a specific archive [season] ("WINTER", "SPRING", "SUMMER", "FALL") and [year]. */
    suspend fun browseSeason(year: Int, season: String, page: Int): PagedResult = browse(BrowseSection.SEASONAL, page)
    /** Lightweight request that also validates the response shape. Throws [ProviderException]. */
    suspend fun check()
}

// ── Transport abstraction (so adapters are testable without a network) ──────

data class HttpRequestSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json; charset=utf-8"
)

data class HttpResult(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

interface HttpTransport {
    /** Throws [ProviderException] for network/timeout failures. HTTP error codes are returned, not thrown. */
    suspend fun execute(request: HttpRequestSpec): HttpResult
}

// ── Engine outcomes ─────────────────────────────────────────────────────────

data class SourceFailure(val sourceName: String, val kind: FailureKind, val message: String)

sealed class Outcome<out T> {
    data class Ok<T>(val value: T, val source: ApiSource, val skipped: List<SourceFailure>) : Outcome<T>()
    data class AllFailed(val failures: List<SourceFailure>) : Outcome<Nothing>()
    data object NoSources : Outcome<Nothing>()
}
