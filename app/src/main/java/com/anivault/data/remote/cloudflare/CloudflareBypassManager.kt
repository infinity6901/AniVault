package com.anivault.data.remote.cloudflare

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launches an invisible WebView on the Main thread to resolve Cloudflare's
 * IUAM / Turnstile JS challenge. Once the page title is no longer the
 * Cloudflare interstitial, the cf_clearance cookie and the exact User-Agent
 * used during verification are captured and returned.
 *
 * Must be called from a coroutine; internally marshals to [Dispatchers.Main]
 * because WebView creation requires the main thread.
 */
@Singleton
class CloudflareBypassManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val COOKIE_CF_CLEARANCE = "cf_clearance"
        private const val CHALLENGE_TITLE = "Just a moment"
        private const val TIMEOUT_MS      = 30_000L
    }

    data class ClearanceResult(
        val cfClearanceCookie: String,
        val userAgent: String,
        /** Full raw cookie string for the domain, ready to use as a Cookie header. */
        val rawCookies: String
    )

    /**
     * Obtain Cloudflare clearance for [url].
     *
     * @throws CloudflareBypassException on timeout or WebView error.
     */
    suspend fun obtainClearance(url: String): ClearanceResult =
        withContext(Dispatchers.Main) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                throw CloudflareBypassException("Refusing non-HTTPS URL for Cloudflare challenge")
            }
            val deferred = CompletableDeferred<ClearanceResult>()

            val cookieManager = CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }

            @SuppressLint("SetJavaScriptEnabled")
            val webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled    = true
                    domStorageEnabled    = true
                    useWideViewPort      = true
                    loadWithOverviewMode = true
                    allowFileAccess      = false
                    allowContentAccess   = false
                    // Disallow mixed HTTP content inside HTTPS pages
                    mixedContentMode     = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    blockNetworkImage    = false
                }

                webViewClient = object : WebViewClient() {

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        if (deferred.isCompleted) return

                        view.evaluateJavascript("(function(){ return document.title; })()") { raw ->
                            val title = raw?.trim('"') ?: ""
                            if (title.contains(CHALLENGE_TITLE, ignoreCase = true)) {
                                // Still on the challenge page – wait for the next onPageFinished
                                return@evaluateJavascript
                            }

                            val raw2 = cookieManager.getCookie(finishedUrl) ?: ""
                            val cfValue = raw2
                                .split(";")
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("$COOKIE_CF_CLEARANCE=") }
                                ?.substringAfter("=")
                                ?: ""

                            if (cfValue.isNotEmpty()) {
                                deferred.complete(
                                    ClearanceResult(
                                        cfClearanceCookie = cfValue,
                                        userAgent         = view.settings.userAgentString,
                                        rawCookies        = raw2
                                    )
                                )
                            }
                            // If cfValue still empty, another onPageFinished may follow
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame && !deferred.isCompleted) {
                            deferred.completeExceptionally(
                                CloudflareBypassException(
                                    "WebView error [${error.errorCode}]: ${error.description}"
                                )
                            )
                        }
                    }
                }
            }

            webView.loadUrl(url)

            // Watchdog – cancel and fail if challenge takes too long
            val watchdog = CoroutineScope(Dispatchers.Main).launch {
                delay(TIMEOUT_MS)
                if (!deferred.isCompleted) {
                    runCatching { webView.stopLoading(); webView.destroy() }
                    deferred.completeExceptionally(
                        CloudflareBypassException("Timed out after ${TIMEOUT_MS / 1000}s waiting for CF clearance")
                    )
                }
            }

            try {
                deferred.await().also {
                    watchdog.cancel()
                    runCatching { webView.stopLoading(); webView.destroy() }
                }
            } catch (e: Exception) {
                watchdog.cancel()
                runCatching { webView.stopLoading(); webView.destroy() }
                throw e
            }
        }
}

class CloudflareBypassException(message: String) : Exception(message)
