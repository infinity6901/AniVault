package com.anivault.data.sync

import android.content.Context
import android.net.Uri
import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.core.Sanitizer
import com.anivault.core.SecureStore
import com.anivault.data.provider.FailureKind
import com.anivault.data.provider.HttpErrors
import com.anivault.data.provider.HttpRequestSpec
import com.anivault.data.provider.HttpTransport
import com.anivault.data.provider.OkHttpTransport
import com.anivault.data.provider.ProviderException
import com.anivault.data.provider.asObjOrNull
import com.anivault.data.provider.str
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Pure, JVM-testable OAuth security helpers for PKCE S256 and mandatory state validation.
 */
object OAuthSecurity {
    private val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }

    /** Computes RFC 7636 S256 challenge: BASE64URL-UNPADDED(SHA256(ASCII(verifier))). */
    fun pkceS256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAniListAuthUrl(clientId: String, state: String): String =
        "https://anilist.co/api/v2/oauth/authorize?client_id=${enc(clientId)}&response_type=token&state=${enc(state)}"

    fun buildMalAuthUrl(clientId: String, verifier: String, state: String, redirectUri: String): String {
        val challenge = pkceS256Challenge(verifier)
        return "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=${enc(clientId)}" +
            "&code_challenge=${enc(challenge)}&code_challenge_method=S256&state=${enc(state)}" +
            "&redirect_uri=${enc(redirectUri)}"
    }

    /**
     * Enforces mandatory, non-expired, constant-time state verification for OAuth callbacks.
     * Rejects missing `pendingRecord`, missing `returnedState`, expired TTLs, and state mismatches.
     */
    fun verifyCallbackState(
        pendingRecord: String?,
        returnedStateRaw: String?,
        nowMs: Long,
        ttlMs: Long = AccountManager.AUTH_TTL_MS
    ): Boolean {
        if (pendingRecord.isNullOrBlank() || returnedStateRaw.isNullOrBlank()) return false
        val parts = pendingRecord.split(':', limit = 2)
        if (parts.size != 2) return false
        val issuedAt = parts[0].toLongOrNull() ?: return false
        val expectedState = parts[1]
        if (expectedState.isBlank()) return false
        if (nowMs - issuedAt !in 0..ttlMs) return false
        val returnedState = runCatching { URLDecoder.decode(returnedStateRaw, "UTF-8") }.getOrDefault(returnedStateRaw)
        return MessageDigest.isEqual(
            expectedState.toByteArray(Charsets.UTF_8),
            returnedState.toByteArray(Charsets.UTF_8)
        )
    }
}

/**
 * Owns sign-in for AniList / MyAnimeList / Kitsu (spec §12).
 *
 *  • AniList: browser sign-in (implicit grant) → token arrives in the redirect fragment (mandatory state check).
 *  • MyAnimeList: browser sign-in with PKCE (S256) → code exchanged for access + refresh token.
 *  • Kitsu: password grant. The password is sent once to Kitsu and never stored or logged.
 *
 * Tokens and client ids are kept only in [SecureStore]. Client ids are supplied by the user –
 * nothing is bundled with the app.
 */
@Singleton
class AccountManager @Inject constructor(
    @ApplicationContext ctx: Context,
    private val secure: SecureStore,
    @Named("api") client: OkHttpClient,
    prefs: AppPrefs,
    private val diagnostics: DiagnosticsLog
) {
    private val meta = ctx.getSharedPreferences("anivault_accounts", Context.MODE_PRIVATE)
    private val transport: HttpTransport = OkHttpTransport(client) { prefs.networkTimeoutSec }
    private val apis: Map<AccountProvider, AccountApi> = mapOf(
        AccountProvider.ANILIST to AniListAccountApi(transport),
        AccountProvider.MAL to MalAccountApi(transport),
        AccountProvider.KITSU to KitsuAccountApi(transport)
    )

    private val _states = MutableStateFlow(readAll())
    val states: StateFlow<Map<AccountProvider, AccountState>> = _states.asStateFlow()

    fun api(provider: AccountProvider): AccountApi = apis.getValue(provider)
    fun state(provider: AccountProvider): AccountState = _states.value.getValue(provider)

    // ── Client ids ─────────────────────────────────────────────────────────
    fun clientId(p: AccountProvider): String? = secure.get("acct_client:${p.key}")
    fun setClientId(p: AccountProvider, value: String) {
        if (value.isBlank()) secure.remove("acct_client:${p.key}") else secure.put("acct_client:${p.key}", value.trim())
        refresh()
    }

    // ── Sign in ────────────────────────────────────────────────────────────

    /** URL to open in the browser, or null when the provider doesn't use a browser sign-in. */
    fun authorizeUrl(p: AccountProvider): String? {
        val id = clientId(p)?.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis().toString()
        return when (p) {
            AccountProvider.ANILIST -> {
                val state = randomString(24)
                secure.put("acct_pending:anilist", "$now:$state")
                OAuthSecurity.buildAniListAuthUrl(id, state)
            }
            AccountProvider.MAL -> {
                val verifier = randomString(64)
                val state = randomString(24)
                // Clean up any abandoned PKCE verifiers before storing the new one.
                secure.removeWithPrefix("acct_pkce:")
                secure.put("acct_pkce:$state", "$now:$verifier")
                OAuthSecurity.buildMalAuthUrl(id, verifier, state, p.redirectUri)
            }
            AccountProvider.KITSU -> null
        }
    }

    /** Handles `anivault://auth/<provider>…` delivered to MainActivity. Returns a user-facing message. */
    suspend fun handleRedirect(data: String): String? {
        val uri = Uri.parse(data)
        if (uri.scheme != "anivault" || uri.host != "auth") return null
        val p = AccountProvider.fromKey(uri.pathSegments.firstOrNull()) ?: return null
        return try {
            when (p) {
                AccountProvider.ANILIST -> {
                    val pending = secure.get("acct_pending:anilist")
                    // Always consume pending state once on any callback attempt.
                    secure.remove("acct_pending:anilist")
                    val frag = uri.fragment.orEmpty()
                    val params = frag.split('&').mapNotNull {
                        val kv = it.split('=', limit = 2)
                        if (kv.size == 2) {
                            val k = runCatching { URLDecoder.decode(kv[0], "UTF-8") }.getOrDefault(kv[0])
                            val v = runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
                            k to v
                        } else null
                    }.toMap()
                    val returnedState = params["state"] ?: uri.getQueryParameter("state")
                    if (!OAuthSecurity.verifyCallbackState(pending, returnedState, System.currentTimeMillis(), AUTH_TTL_MS)) {
                        throw ProviderException(FailureKind.AUTH, "Sign-in response state was missing, expired, or did not match this request. Try again.")
                    }
                    val token = params["access_token"]?.takeIf { it.isNotBlank() }
                        ?: throw ProviderException(FailureKind.AUTH, "AniList did not return a sign-in token")
                    val expiresIn = params["expires_in"]?.toLongOrNull()
                    finishSignIn(p, token, null, expiresIn)
                }
                AccountProvider.MAL -> {
                    val error = uri.getQueryParameter("error")
                    if (error != null) {
                        secure.removeWithPrefix("acct_pkce:")
                        throw ProviderException(FailureKind.AUTH, "MyAnimeList sign-in was cancelled or refused")
                    }
                    val code = uri.getQueryParameter("code")
                    val state = uri.getQueryParameter("state").orEmpty()
                    val stored = if (state.isNotBlank()) secure.get("acct_pkce:$state") else null
                    secure.removeWithPrefix("acct_pkce:")
                    val parts = stored?.split(':', limit = 2)
                    val issuedAt = parts?.getOrNull(0)?.toLongOrNull() ?: 0L
                    val verifier = parts?.getOrNull(1)
                    if (code.isNullOrBlank() || verifier.isNullOrBlank() || System.currentTimeMillis() - issuedAt !in 0..AUTH_TTL_MS) {
                        throw ProviderException(FailureKind.AUTH, "Sign-in response did not match this request or expired. Try again.")
                    }
                    val res = transport.execute(
                        HttpRequestSpec(
                            "https://myanimelist.net/v1/oauth2/token", "POST", mapOf("Accept" to "application/json"),
                            form(
                                "client_id" to clientId(p), "grant_type" to "authorization_code", "code" to code,
                                "redirect_uri" to p.redirectUri, "code_verifier" to verifier
                            ),
                            "application/x-www-form-urlencoded"
                        )
                    )
                    val t = parseToken(res, "MyAnimeList")
                    finishSignIn(p, t.access, t.refresh, t.expiresIn)
                }
                AccountProvider.KITSU -> null
            }
            "Connected to ${p.label}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(p, e)
            "Couldn't connect to ${p.label}: ${Sanitizer.clean(e.message)}"
        }
    }

    suspend fun signInKitsu(email: String, password: String): String? {
        val p = AccountProvider.KITSU
        return try {
            val res = transport.execute(
                HttpRequestSpec(
                    "https://kitsu.io/api/oauth/token", "POST", mapOf("Accept" to "application/json"),
                    form("grant_type" to "password", "username" to email.trim(), "password" to password),
                    "application/x-www-form-urlencoded"
                )
            )
            val t = parseToken(res, "Kitsu")
            finishSignIn(p, t.access, t.refresh, t.expiresIn)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            val m = if (e.kind == FailureKind.AUTH || e.httpCode == 400) "Kitsu rejected that email or password" else Sanitizer.clean(e.message)
            fail(p, e); m
        } catch (e: Exception) {
            fail(p, e); Sanitizer.clean(e.message)
        }
    }

    fun disconnect(p: AccountProvider) {
        secure.remove("acct_token:${p.key}"); secure.remove("acct_refresh:${p.key}"); secure.remove("acct_expires:${p.key}")
        meta.edit().remove("name:${p.key}").remove("id:${p.key}").remove("error:${p.key}").remove("reauth:${p.key}").remove("last:${p.key}").apply()
        refresh()
    }

    // ── Tokens ─────────────────────────────────────────────────────────────

    /** Runs [block] with a valid token, refreshing once on 401/expiry. Marks the account for re-sign-in if that fails. */
    suspend fun <T> withToken(p: AccountProvider, block: suspend (token: String, who: Identity) -> T): T {
        val who = Identity(meta.getString("id:${p.key}", null) ?: "", meta.getString("name:${p.key}", null) ?: "")
        var token = secure.get("acct_token:${p.key}") ?: throw needReauth(p)
        val expires = secure.get("acct_expires:${p.key}")?.toLongOrNull() ?: 0L
        if (expires in 1 until System.currentTimeMillis() + 60_000L) token = refreshToken(p) ?: throw needReauth(p)
        return try {
            block(token, who)
        } catch (e: ProviderException) {
            if (e.kind != FailureKind.AUTH) throw e
            val fresh = refreshToken(p) ?: throw needReauth(p)
            try { block(fresh, who) } catch (e2: ProviderException) { if (e2.kind == FailureKind.AUTH) throw needReauth(p) else throw e2 }
        }
    }

    private suspend fun refreshToken(p: AccountProvider): String? {
        val refresh = secure.get("acct_refresh:${p.key}") ?: return null
        return try {
            val res = when (p) {
                AccountProvider.MAL -> transport.execute(
                    HttpRequestSpec(
                        "https://myanimelist.net/v1/oauth2/token", "POST", mapOf("Accept" to "application/json"),
                        form("client_id" to clientId(p), "grant_type" to "refresh_token", "refresh_token" to refresh),
                        "application/x-www-form-urlencoded"
                    )
                )
                AccountProvider.KITSU -> transport.execute(
                    HttpRequestSpec(
                        "https://kitsu.io/api/oauth/token", "POST", mapOf("Accept" to "application/json"),
                        form("grant_type" to "refresh_token", "refresh_token" to refresh),
                        "application/x-www-form-urlencoded"
                    )
                )
                AccountProvider.ANILIST -> return null
            }
            val t = parseToken(res, p.label)
            storeTokens(p, t.access, t.refresh ?: refresh, t.expiresIn)
            t.access
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun needReauth(p: AccountProvider): ProviderException {
        meta.edit().putBoolean("reauth:${p.key}", true).apply()
        refresh()
        return ProviderException(FailureKind.AUTH, "${p.label} sign-in expired. Sign in again.")
    }

    // ── Sync bookkeeping ───────────────────────────────────────────────────
    fun recordSync(p: AccountProvider, at: Long) {
        meta.edit().putLong("last:${p.key}", at).remove("error:${p.key}").apply(); refresh()
    }
    fun recordError(p: AccountProvider, message: String?) {
        meta.edit().putString("error:${p.key}", Sanitizer.clean(message)).apply(); refresh()
    }
    fun lastSyncAt(p: AccountProvider): Long = meta.getLong("last:${p.key}", 0L)
    fun connectedProviders(): List<AccountProvider> = _states.value.values.filter { it.connected && !it.needsReauth }.map { it.provider }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun finishSignIn(p: AccountProvider, access: String, refresh: String?, expiresInSec: Long?) {
        storeTokens(p, access, refresh, expiresInSec)
        val who = try { apis.getValue(p).identify(access) } catch (e: Exception) {
            secure.remove("acct_token:${p.key}"); secure.remove("acct_refresh:${p.key}"); secure.remove("acct_expires:${p.key}")
            throw e
        }
        meta.edit().putString("id:${p.key}", who.accountId).putString("name:${p.key}", who.name)
            .remove("error:${p.key}").remove("reauth:${p.key}").apply()
        refresh()
        diagnostics.record(p.label, "sign-in", true, 0)
    }

    private fun storeTokens(p: AccountProvider, access: String, refresh: String?, expiresInSec: Long?) {
        secure.put("acct_token:${p.key}", access)
        if (refresh != null) secure.put("acct_refresh:${p.key}", refresh)
        if (expiresInSec != null && expiresInSec > 0) secure.put("acct_expires:${p.key}", (System.currentTimeMillis() + expiresInSec * 1000L).toString())
        else secure.remove("acct_expires:${p.key}")
    }

    private fun fail(p: AccountProvider, e: Exception) {
        diagnostics.record(p.label, "sign-in", false, 0, e.message)
        meta.edit().putString("error:${p.key}", Sanitizer.clean(e.message)).apply()
        refresh()
    }

    private class Tokens(val access: String, val refresh: String?, val expiresIn: Long?)

    private fun parseToken(res: com.anivault.data.provider.HttpResult, what: String): Tokens {
        HttpErrors.check(res, what)
        val o = HttpErrors.parse(res.body, what).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "$what returned an unexpected response")
        val access = o.str("access_token") ?: throw ProviderException(FailureKind.MALFORMED, "$what returned no token")
        return Tokens(access, o.str("refresh_token"), o.str("expires_in")?.toLongOrNull())
    }

    private fun refresh() { _states.value = readAll() }

    /**
     * Sign-ins and client IDs for an encrypted backup: tokens, refresh tokens, expiry, client IDs
     * (accounts and MyAnimeList sources) plus the account names they belong to ("meta:" keys).
     */
    fun exportCredentials(): Map<String, String> {
        val out = LinkedHashMap(secure.snapshot(SECRET_PREFIXES))
        meta.all.forEach { (k, v) -> if (k.startsWith("name:") || k.startsWith("id:")) out["meta:$k"] = v.toString() }
        return out
    }

    fun importCredentials(values: Map<String, String>) {
        secure.restore(values, SECRET_PREFIXES)
        val e = meta.edit()
        values.forEach { (k, v) -> if (k.startsWith("meta:")) e.putString(k.removePrefix("meta:"), v) }
        values.keys.filter { it.startsWith("acct_token:") }.forEach { e.remove("reauth:${it.removePrefix("acct_token:")}") }
        e.apply()
        refresh()
    }

    private fun readAll(): Map<AccountProvider, AccountState> = AccountProvider.values().associateWith { p ->
        AccountState(
            provider = p,
            connected = secure.get("acct_token:${p.key}") != null && meta.getString("name:${p.key}", null) != null,
            accountName = meta.getString("name:${p.key}", null),
            clientIdSet = !clientId(p).isNullOrBlank(),
            lastSyncAt = meta.getLong("last:${p.key}", 0L),
            lastError = meta.getString("error:${p.key}", null),
            needsReauth = meta.getBoolean("reauth:${p.key}", false)
        )
    }

    private fun randomString(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        return buildString { repeat(len) { append(chars[rnd.nextInt(chars.length)]) } }
    }

    companion object {
        const val AUTH_TTL_MS = 10 * 60_000L
        val SECRET_PREFIXES = listOf("acct_token:", "acct_refresh:", "acct_expires:", "acct_client:", "mal_client_id:")
    }
}
