package com.anivault.data.provider

import com.anivault.core.DiagnosticEntry
import com.anivault.core.Sanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Implements the spec's request algorithm (§7):
 *
 *  - providers are tried in priority order (caller passes the enabled ones, already ordered);
 *  - timeout / network / 5xx / malformed / auth / 429 → record a sanitized diagnostic and try the
 *    next provider **only if auto-fallback is on**;
 *  - HTTP 429 puts that provider on a cool-down (honoring Retry-After) so it is skipped next time;
 *  - a *successful empty* result is final by default (configurable) – it is not an outage;
 *  - if every provider fails the caller gets [Outcome.AllFailed] with one entry per provider.
 *
 * Pure Kotlin + coroutines: the clock, the sleeper and the diagnostics sink are injectable so the
 * whole policy is unit-testable without Android.
 */
class FallbackEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sink: (DiagnosticEntry) -> Unit = {},
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun clearCooldowns() = cooldowns.clear()

    suspend fun <T> run(
        sources: List<ApiSource>,
        autoFallback: Boolean,
        emptyIsFinal: Boolean,
        retries: Int,
        operation: String,
        isEmpty: (T) -> Boolean,
        call: suspend (ApiSource) -> T
    ): Outcome<T> {
        if (sources.isEmpty()) return Outcome.NoSources
        val candidates = if (autoFallback) sources else sources.take(1)
        val failures = mutableListOf<SourceFailure>()
        var firstEmpty: Outcome.Ok<T>? = null

        for (src in candidates) {
            val until = cooldowns[src.id] ?: 0L
            if (until > clock()) {
                failures += SourceFailure(src.name, FailureKind.RATE_LIMITED, "Cooling down after a rate limit")
                continue
            }
            val started = clock()
            try {
                val value = callWithRetry(src, retries, call)
                sink(DiagnosticEntry(clock(), src.name, operation, true, clock() - started, null))
                if (isEmpty(value) && !emptyIsFinal && autoFallback) {
                    if (firstEmpty == null) firstEmpty = Outcome.Ok(value, src, emptyList())
                    continue
                }
                return Outcome.Ok(value, src, failures.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val pe = e as? ProviderException
                    ?: ProviderException(FailureKind.MALFORMED, e.message ?: "Unexpected error")
                val clean = Sanitizer.clean(pe.message)
                sink(DiagnosticEntry(clock(), src.name, operation, false, clock() - started, "${pe.kind.label}: $clean"))
                if (pe.kind == FailureKind.RATE_LIMITED) {
                    val wait = (pe.retryAfterMs ?: DEFAULT_COOLDOWN_MS).coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
                    cooldowns[src.id] = clock() + wait
                }
                failures += SourceFailure(src.name, pe.kind, clean)
            }
        }
        firstEmpty?.let { return it.copy(skipped = failures.toList()) }
        return Outcome.AllFailed(failures)
    }

    private suspend fun <T> callWithRetry(src: ApiSource, retries: Int, call: suspend (ApiSource) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return call(src)
            } catch (e: ProviderException) {
                val retryable = e.kind == FailureKind.NETWORK || e.kind == FailureKind.TIMEOUT || e.kind == FailureKind.SERVER
                if (!retryable || attempt >= retries) throw e
                attempt++
                sleeper(BACKOFF_MS * attempt)
            }
        }
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val MIN_COOLDOWN_MS = 1_000L
        const val MAX_COOLDOWN_MS = 300_000L
        const val BACKOFF_MS = 300L
    }
}

/** Tiny access-ordered cache with a time-to-live. Failures are never cached by callers. */
class TtlCache<K, V>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private class Slot<V>(val value: V, val storedAt: Long)

    private val map = object : LinkedHashMap<K, Slot<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Slot<V>>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: K): V? {
        val slot = map[key] ?: return null
        if (clock() - slot.storedAt > ttlMs) { map.remove(key); return null }
        return slot.value
    }

    @Synchronized fun put(key: K, value: V) { map[key] = Slot(value, clock()) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}
