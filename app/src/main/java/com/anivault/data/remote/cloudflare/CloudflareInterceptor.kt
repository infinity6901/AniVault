package com.anivault.data.remote.cloudflare

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp [Interceptor] that attaches a Cloudflare clearance cookie and the
 * matching User-Agent string to every request whose host matches [targetHost].
 *
 * Thread-safety: [_cache] is guarded by [synchronized] because OkHttp may
 * dispatch calls on multiple threads concurrently.
 */
@Singleton
class CloudflareInterceptor @Inject constructor(
    private val bypassManager: CloudflareBypassManager
) : Interceptor {

    /** The scraping host, e.g. "hanime.tv". Set before first use. */
    var targetHost: String = ""

    private val lock = Any()

    @Volatile
    private var _cache: CloudflareBypassManager.ClearanceResult? = null

    /**
     * Pre-warm the clearance cache.  Call once before scraping begins.
     */
    fun prewarm(baseUrl: String) {
        synchronized(lock) {
            if (_cache == null) {
                _cache = runBlocking { bypassManager.obtainClearance(baseUrl) }
            }
        }
    }

    /** Force a fresh challenge resolution on the next request. */
    fun invalidate() {
        synchronized(lock) { _cache = null }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Pass-through for unrelated hosts
        if (request.url.host != targetHost) return chain.proceed(request)

        val result = synchronized(lock) { _cache }
            ?: runBlocking { bypassManager.obtainClearance(request.url.toString()) }
                .also { synchronized(lock) { _cache = it } }

        val enriched = request.newBuilder()
            .header("User-Agent",      result.userAgent)
            .header("Cookie",          mergeCookies(request.header("Cookie"), result))
            .header("Referer",         "https://$targetHost/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val response = chain.proceed(enriched)

        if (response.code == 403 || response.code == 503) {
            // Clearance likely expired – invalidate so next call re-solves
            response.close()
            invalidate()
            throw CloudflareBypassException(
                "Cloudflare rejected request (${response.code}). Clearance invalidated – please retry."
            )
        }

        return response
    }

    /**
     * Merge existing request cookies with the [CloudflareBypassManager.COOKIE_CF_CLEARANCE]
     * value so that any existing session cookies are preserved.
     */
    private fun mergeCookies(
        existing: String?,
        result: CloudflareBypassManager.ClearanceResult
    ): String {
        val cfPair = "${CloudflareBypassManager.COOKIE_CF_CLEARANCE}=${result.cfClearanceCookie}"
        return when {
            existing.isNullOrBlank() -> cfPair
            existing.contains(CloudflareBypassManager.COOKIE_CF_CLEARANCE) -> existing // already present
            else -> "$existing; $cfPair"
        }
    }
}
