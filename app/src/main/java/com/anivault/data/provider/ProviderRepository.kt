package com.anivault.data.provider

import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.core.Sanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed class ProviderResult {
    data class Success(
        val items: List<NormalizedAnime>,
        val hasNext: Boolean,
        /** The source that actually answered. */
        val source: ApiSource,
        /** Sources that were tried first and failed (so the UI can say "served by X after Y failed"). */
        val skipped: List<SourceFailure>
    ) : ProviderResult()

    data class Failed(val failures: List<SourceFailure>) : ProviderResult()
    data object NoSources : ProviderResult()
}

@Singleton
class AdapterFactory @Inject constructor(
    private val store: SourceStore,
    @Named("api") client: OkHttpClient,
    private val prefs: AppPrefs
) {
    private val transport: HttpTransport = OkHttpTransport(client) { prefs.networkTimeoutSec }

    fun create(source: ApiSource): ProviderAdapter = when (source.type) {
        AnimeApiType.ANILIST_GRAPHQL -> AniListAdapter(source, transport, adult = source.adult)
        AnimeApiType.JIKAN_COMPATIBLE -> JikanAdapter(source, transport, adult = source.adult)
        AnimeApiType.MAL_V2 -> MalAdapter(source, transport) { store.clientId(source.id) }
        AnimeApiType.KITSU_JSON_API -> KitsuAdapter(source, transport, adult = source.adult)
        AnimeApiType.JIKAN_EDGE -> JikanEdgeAdapter(source, transport, adult = source.adult)
    }
}

/**
 * Entry point for all catalog access (search / browse / connection tests).
 * Policy lives in [FallbackEngine]; this class wires settings, cache and diagnostics to it.
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val store: SourceStore,
    private val factory: AdapterFactory,
    private val diagnostics: DiagnosticsLog,
    private val prefs: AppPrefs
) {
    private val engine = FallbackEngine(sink = { diagnostics.record(it) })
    private val cache = TtlCache<String, PagedResult>(maxEntries = 60, ttlMs = 10 * 60_000L)

    val sources: StateFlow<List<ApiSource>> get() = store.sources

    suspend fun search(query: String, page: Int = 1, filters: SearchFilters = SearchFilters()): ProviderResult {
        val q = query.trim()
        val outcome = engine.run(
            sources = store.enabled(),
            autoFallback = store.autoFallback.value,
            emptyIsFinal = !prefs.fallbackOnEmpty,
            retries = prefs.networkRetries,
            operation = "search",
            isEmpty = { r: PagedResult -> r.items.isEmpty() }
        ) { src ->
            cached("s|${src.id}|${q.lowercase()}|$page") { factory.create(src).search(q, page) }
        }
        return toResult(outcome, filters)
    }

    suspend fun browse(section: BrowseSection, page: Int = 1, filters: SearchFilters = SearchFilters()): ProviderResult {
        val capable = store.enabled().filter { factory.create(it).sections.contains(section) }
        val outcome = engine.run(
            sources = capable,
            autoFallback = store.autoFallback.value,
            emptyIsFinal = !prefs.fallbackOnEmpty,
            retries = prefs.networkRetries,
            operation = "browse:${section.name.lowercase()}",
            isEmpty = { r: PagedResult -> r.items.isEmpty() }
        ) { src ->
            cached("b|${src.id}|${section.name}|$page") { factory.create(src).browse(section, page) }
        }
        return toResult(outcome, filters)
    }

    suspend fun browseSeason(year: Int, season: String, page: Int = 1, filters: SearchFilters = SearchFilters()): ProviderResult {
        val s = season.uppercase()
        val capable = store.enabled().filter { factory.create(it).sections.contains(BrowseSection.SEASONAL) }
        val outcome = engine.run(
            sources = capable,
            autoFallback = store.autoFallback.value,
            emptyIsFinal = !prefs.fallbackOnEmpty,
            retries = prefs.networkRetries,
            operation = "season:$year:${s.lowercase()}",
            isEmpty = { r: PagedResult -> r.items.isEmpty() }
        ) { src ->
            cached("bs|${src.id}|$year|$s|$page") { factory.create(src).browseSeason(year, s, page) }
        }
        return toResult(outcome, filters)
    }

    /** True when at least one enabled source can serve [section]. */
    fun supports(section: BrowseSection): Boolean =
        store.enabled().any { factory.create(it).sections.contains(section) }

    /** Real connection test: network reachability AND the adapter's expected response shape. */
    suspend fun test(source: ApiSource): CheckResult {
        val started = System.currentTimeMillis()
        val validation = SourceValidator.validate(source.name, source.baseUrl, source.type, emptyList(), source.id)
        val result: CheckResult = if (!validation.ok) {
            CheckResult(CheckKind.UNSUPPORTED_SCHEMA, validation.error ?: "Invalid source", 0)
        } else {
            try {
                factory.create(source).check()
                CheckResult(CheckKind.OK, "Connected. The response format was recognised.", System.currentTimeMillis() - started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                val kind = when (e.kind) {
                    FailureKind.AUTH -> CheckKind.AUTH_REQUIRED
                    FailureKind.RATE_LIMITED -> CheckKind.RATE_LIMITED
                    FailureKind.MALFORMED, FailureKind.CLIENT, FailureKind.UNSUPPORTED -> CheckKind.UNSUPPORTED_SCHEMA
                    FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.SERVER -> CheckKind.UNREACHABLE
                }
                CheckResult(kind, Sanitizer.clean(e.message), System.currentTimeMillis() - started)
            } catch (e: Exception) {
                CheckResult(CheckKind.UNSUPPORTED_SCHEMA, Sanitizer.clean(e.message), System.currentTimeMillis() - started)
            }
        }
        store.recordCheck(source.id, result)
        diagnostics.record(source.name, "test", result.kind == CheckKind.OK, result.durationMs, if (result.kind == CheckKind.OK) null else result.message)
        return result
    }

    fun clearCache() { cache.clear(); engine.clearCooldowns() }

    private suspend fun cached(key: String, load: suspend () -> PagedResult): PagedResult {
        cache.get(key)?.let { return it }
        return load().also { cache.put(key, it) }
    }

    private fun toResult(outcome: Outcome<PagedResult>, filters: SearchFilters): ProviderResult = when (outcome) {
        is Outcome.Ok -> ProviderResult.Success(
            items = TitleMatcher.dedupe(filters.apply(outcome.value.items)),
            hasNext = outcome.value.hasNext,
            source = outcome.source,
            skipped = outcome.skipped
        )
        is Outcome.AllFailed -> ProviderResult.Failed(outcome.failures)
        Outcome.NoSources -> ProviderResult.NoSources
    }
}
