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
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa") || (!h.contains('.') && !h.contains(':'))
        ) return true
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
        if (v4 != null) {
            val o = v4.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return true
            return isPrivateIpv4(o[0], o[1])
        }
        // Reject obfuscated numeric/hex IPv4 hostnames (e.g. 0x7f.0.0.1 or 127.1).
        if (!h.contains(':') && h.split('.').all { part -> part.all { it.isDigit() } || part.startsWith("0x") }) {
            return true
        }
        if (h.contains(':')) {
            if (h == "::1" || h == "::" || h == "0:0:0:0:0:0:0:1" || h == "0:0:0:0:0:0:0:0" ||
                h.startsWith("fc") || h.startsWith("fd") ||
                h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
            ) return true
            // Check IPv4-mapped IPv6 in dotted form (e.g. ::ffff:127.0.0.1) or hex form (e.g. ::ffff:7f00:1)
            val tail = h.substringAfterLast(':')
            if (tail.contains('.')) {
                val mappedV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(tail)
                if (mappedV4 != null) {
                    val o = mappedV4.groupValues.drop(1).map { it.toInt() }
                    if (o.any { it > 255 } || isPrivateIpv4(o[0], o[1])) return true
                }
            } else if (h.contains("ffff:")) {
                val afterFfff = h.substringAfter("ffff:")
                val parts = afterFfff.split(':')
                if (parts.size == 2) {
                    val hi = parts[0].toIntOrNull(16)
                    val lo = parts[1].toIntOrNull(16)
                    if (hi != null && lo != null) {
                        val o0 = (hi shr 8) and 0xff
                        val o1 = hi and 0xff
                        if (isPrivateIpv4(o0, o1)) return true
                    }
                }
            }
        }
        return false
    }

    private fun isPrivateIpv4(o0: Int, o1: Int): Boolean =
        o0 == 10 || o0 == 127 || o0 == 0 ||
            (o0 == 169 && o1 == 254) ||
            (o0 == 172 && o1 in 16..31) ||
            (o0 == 192 && o1 == 168) ||
            (o0 == 100 && o1 in 64..127) ||
            o0 >= 224
}
