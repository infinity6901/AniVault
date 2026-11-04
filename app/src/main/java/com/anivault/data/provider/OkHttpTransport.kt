package com.anivault.data.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
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
        } else {
            builder.get()
        }
        val secs = timeoutSeconds().coerceIn(5, 60)
        val call = client.newBuilder()
            .connectTimeout(secs.toLong(), TimeUnit.SECONDS)
            .readTimeout(secs.toLong(), TimeUnit.SECONDS)
            .callTimeout((secs + 5).toLong(), TimeUnit.SECONDS)
            .build()
            .newCall(builder.build())

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
                            val length = r.body?.contentLength() ?: -1L
                            if (length > MAX_BODY_BYTES) {
                                cont.resumeWithException(ProviderException(FailureKind.MALFORMED, "Response too large"))
                                return
                            }
                            val body = r.body?.string().orEmpty()
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
        const val USER_AGENT = "AniVault/1.1 (Android)"
    }
}

/**
 * Blocks DNS answers that resolve to loopback / private / link-local addresses so a configured
 * source can't be pointed at the user's local network (SSRF-style abuse).
 */
object SafeDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.any { it.isLoopbackAddress || it.isAnyLocalAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }) {
            throw UnknownHostException("Blocked a private network address")
        }
        return addresses
    }
}
