package com.anivault.data.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production [HttpTransport]. Cancelling the calling coroutine cancels the HTTP call (so stale
 * searches never keep running), and the per-call timeout comes from the user's setting.
 */
class OkHttpTransport(
    baseClient: OkHttpClient,
    private val timeoutSeconds: () -> Int
) : HttpTransport {

    private val client: OkHttpClient = baseClient.newBuilder()
        .dns(SafeDns)
        .followRedirects(true)
        .followSslRedirects(false) // never follow https → http
        .addNetworkInterceptor(RedirectGuardInterceptor)
        .build()

    override suspend fun execute(request: HttpRequestSpec): HttpResult {
        val httpUrl = runCatching { Request.Builder().url(request.url) }.getOrNull()
            ?: throw ProviderException(FailureKind.CLIENT, "Invalid request URL")
        val builder = httpUrl.header("User-Agent", USER_AGENT)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        if (request.method.equals("POST", ignoreCase = true) || request.method.equals("PATCH", ignoreCase = true) ||
            request.method.equals("PUT", ignoreCase = true)
        ) {
            builder.method(request.method.uppercase(), (request.body ?: "").toRequestBody(request.contentType.toMediaType()))
        } else if (request.method.equals("DELETE", ignoreCase = true)) {
            // Previously fell through to GET, so account deletes were sent as reads (MAL answered 405).
            builder.delete()
        } else {
            builder.get()
        }
        val secs = timeoutSeconds().coerceIn(5, 60)
        val builtRequest = builder.build()
        val initialHost = builtRequest.url.host
        val call = client.newBuilder()
            .connectTimeout(secs.toLong(), TimeUnit.SECONDS)
            .readTimeout(secs.toLong(), TimeUnit.SECONDS)
            .callTimeout((secs + 5).toLong(), TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val sanitized = if (!req.url.host.equals(initialHost, ignoreCase = true)) {
                    req.newBuilder().removeHeader("Authorization").removeHeader("X-MAL-CLIENT-ID").build()
                } else req
                chain.proceed(sanitized)
            }
            .build()
            .newCall(builtRequest)

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    val kind = when (e) {
                        is SocketTimeoutException -> FailureKind.TIMEOUT
                        is java.io.InterruptedIOException -> FailureKind.TIMEOUT
                        is UnknownHostException -> FailureKind.NETWORK
                        else -> FailureKind.NETWORK
                    }
                    val msg = when (kind) {
                        FailureKind.TIMEOUT -> "The request timed out"
                        else -> "Couldn't reach the server"
                    }
                    cont.resumeWithException(ProviderException(kind, msg))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try {
                            val bodyObj = r.body
                            val length = bodyObj?.contentLength() ?: -1L
                            if (length > MAX_BODY_BYTES) {
                                cont.resumeWithException(ProviderException(FailureKind.MALFORMED, "Response too large"))
                                return
                            }
                            val source = bodyObj?.source()
                            if (source != null) {
                                source.request(MAX_BODY_BYTES + 1L)
                                if (source.buffer.size > MAX_BODY_BYTES) {
                                    cont.resumeWithException(ProviderException(FailureKind.MALFORMED, "Response too large"))
                                    return
                                }
                            }
                            val body = bodyObj?.string().orEmpty()
                            val headers = r.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                            cont.resume(HttpResult(r.code, body, headers))
                        } catch (e: IOException) {
                            cont.resumeWithException(ProviderException(FailureKind.NETWORK, "Connection interrupted"))
                        }
                    }
                }
            })
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val USER_AGENT = "AniVault/1.2 (Android)"
    }
}

/** Rejects redirects to plain HTTP or private/local hosts before sending the redirected request. */
private object RedirectGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.scheme.equals("https", ignoreCase = true)) {
            throw IOException("Blocked non-HTTPS request or redirect")
        }
        if (SourceValidator.isPrivateHost(req.url.host)) {
            throw UnknownHostException("Blocked a private network address")
        }
        return chain.proceed(req)
    }
}

/**
 * Blocks DNS answers that resolve to loopback / private / link-local / CGNAT / unique-local addresses
 * (including IPv4-mapped IPv6 addresses) so a configured source can't be pointed at the user's local network.
 */
object SafeDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (SourceValidator.isPrivateHost(hostname)) {
            throw UnknownHostException("Blocked a private network address")
        }
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.any { isPrivateAddress(it) }) {
            throw UnknownHostException("Blocked a private network address")
        }
        return addresses
    }

    fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return true
        val raw = addr.address
        if (raw.size == 4) {
            return isPrivateIpv4Bytes(raw[0].toInt() and 0xff, raw[1].toInt() and 0xff)
        }
        if (raw.size == 16) {
            val b0 = raw[0].toInt() and 0xff
            val b1 = raw[1].toInt() and 0xff
            // Unique local fc00::/7 or link-local fe80::/10
            if ((b0 and 0xfe) == 0xfc) return true
            if (b0 == 0xfe && (b1 and 0xc0) == 0x80) return true
            // IPv4-mapped (::ffff:a.b.c.d) or IPv4-compatible (::a.b.c.d)
            val prefixZeros = (0 until 10).all { raw[it].toInt() == 0 }
            val mapped = prefixZeros && (raw[10].toInt() and 0xff) == 0xff && (raw[11].toInt() and 0xff) == 0xff
            val compat = prefixZeros && raw[10].toInt() == 0 && raw[11].toInt() == 0
            if (mapped || compat) {
                return isPrivateIpv4Bytes(raw[12].toInt() and 0xff, raw[13].toInt() and 0xff)
            }
        }
        return false
    }

    private fun isPrivateIpv4Bytes(o0: Int, o1: Int): Boolean =
        o0 == 0 || o0 == 10 || o0 == 127 ||
            (o0 == 169 && o1 == 254) ||
            (o0 == 172 && o1 in 16..31) ||
            (o0 == 192 && o1 == 168) ||
            (o0 == 100 && o1 in 64..127) ||
            o0 >= 224
}
