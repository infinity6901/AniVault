package com.anivault.data.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validates the Add/Edit API source form (spec §6): syntax, scheme, safe destination, no secrets in URLs. */
object SourceValidator {

    data class Result(val ok: Boolean, val error: String? = null, val normalizedUrl: String? = null)

    private val sensitiveParams = setOf(
        "key", "apikey", "api_key", "token", "access_token", "secret", "client_secret", "password", "auth", "authorization"
    )

    fun validate(
        name: String,
        url: String,
        type: AnimeApiType,
        existing: List<ApiSource> = emptyList(),
        editingId: String? = null
    ): Result {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return fail("Enter a name for this source.")
        if (cleanName.length > 40) return fail("Keep the name under 40 characters.")

        val raw = url.trim()
        if (raw.isEmpty()) return fail("Enter the base URL.")
        if (raw.startsWith("http://", ignoreCase = true)) return fail("Use HTTPS. Plain HTTP endpoints are not allowed.")
        val parsed = raw.toHttpUrlOrNull() ?: return fail("That doesn't look like a valid URL (example: https://api.example.org/v1).")
        if (!parsed.scheme.equals("https", ignoreCase = true)) return fail("Use HTTPS.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return fail("Don't put credentials in the URL. Use the provider's dedicated field instead.")
        }
        if (isPrivateHost(parsed.host)) return fail("Local and private network addresses are not allowed.")
        if (parsed.fragment != null) return fail("Remove the #fragment from the URL.")
        if (parsed.query != null) {
            val names = parsed.queryParameterNames.map { it.lowercase() }
            if (names.any { it in sensitiveParams }) return fail("Don't put API keys or tokens in the URL.")
            return fail("The base URL shouldn't include a query string – AniVault adds the parameters it needs.")
        }
        if (type == AnimeApiType.MAL_V2 && !parsed.host.equals(MalAdapter.MAL_HOST, ignoreCase = true)) {
            return fail("MyAnimeList sources must use ${MalAdapter.MAL_HOST} so your Client ID is never sent elsewhere.")
        }
        val normalized = parsed.toString().trimEnd('/')
        if (existing.any { it.id != editingId && it.type == type && it.baseUrl.trimEnd('/').equals(normalized, ignoreCase = true) }) {
            return fail("This endpoint is already configured.")
        }
        return Result(true, null, normalized)
    }

    private fun fail(msg: String) = Result(false, msg, null)

    /** Rejects loopback, link-local, private ranges and obviously local host names (no DNS lookups here). */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa") || !h.contains('.') && !h.contains(':')
        ) return true
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
        if (v4 != null) {
            val o = v4.groupValues.drop(1).map { it.toInt() }
            return o[0] == 10 || o[0] == 127 || o[0] == 0 ||
                (o[0] == 169 && o[1] == 254) ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 100 && o[1] in 64..127)
        }
        if (h.contains(':')) {
            return h == "::1" || h == "::" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        return false
    }
}
