package com.anivault.data.provider

import android.content.Context
import com.anivault.core.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the configured API sources (priority = list order), the Auto-Fallback switch and
 * per-source health from REAL connection tests. Credentials (MAL Client ID) live in [SecureStore].
 */
@Singleton
class SourceStore @Inject constructor(
    @ApplicationContext ctx: Context,
    private val secure: SecureStore
) {
    private val prefs = ctx.getSharedPreferences("anime_api_sources", Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<ApiSource>> = _sources.asStateFlow()

    private val _autoFallback = MutableStateFlow(prefs.getBoolean(KEY_AUTO_FALLBACK, true))
    val autoFallback: StateFlow<Boolean> = _autoFallback.asStateFlow()

    fun snapshot(): List<ApiSource> = _sources.value
    fun enabled(): List<ApiSource> = _sources.value.filter { it.enabled }
    fun find(id: String): ApiSource? = _sources.value.firstOrNull { it.id == id }

    fun setAutoFallback(enabled: Boolean) {
        _autoFallback.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK, enabled).apply()
    }

    // ── Mutations (each persists immediately) ──────────────────────────────

    /** New sources start DISABLED and unverified until the user tests/enables them (spec §6). */
    fun add(name: String, baseUrl: String, type: AnimeApiType, clientId: String = ""): ApiSource {
        val source = ApiSource(
            id = UUID.randomUUID().toString(), name = name.trim(), baseUrl = baseUrl,
            type = type, enabled = false, builtIn = false, verified = false
        )
        commit(_sources.value + source)
        if (clientId.isNotBlank()) setClientId(source.id, clientId)
        return source
    }

    fun update(source: ApiSource) = commit(_sources.value.map { if (it.id == source.id) source else it })

    fun setEnabled(id: String, enabled: Boolean) =
        commit(_sources.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun remove(id: String) {
        commit(_sources.value.filterNot { it.id == id })
        secure.remove(clientKey(id))
    }

    /** Moves a source by [delta] positions (negative = higher priority). */
    fun move(id: String, delta: Int) {
        val list = _sources.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        commit(list)
    }

    fun recordCheck(id: String, result: CheckResult) {
        commit(_sources.value.map {
            if (it.id != id) it else it.copy(
                lastCheckedAt = System.currentTimeMillis(),
                lastStatus = result.kind.label,
                lastError = if (result.kind == CheckKind.OK) "" else result.message,
                verified = result.kind == CheckKind.OK
            )
        })
    }

    fun resetToDefaults() {
        _sources.value.forEach { secure.remove(clientKey(it.id)) }
        commit(defaults())
    }

    // ── Credentials ────────────────────────────────────────────────────────

    fun clientId(id: String): String? = secure.get(clientKey(id))
    fun hasClientId(id: String): Boolean = !clientId(id).isNullOrBlank()
    fun setClientId(id: String, value: String) {
        if (value.isBlank()) secure.remove(clientKey(id)) else secure.put(clientKey(id), value.trim())
    }

    // ── Persistence ────────────────────────────────────────────────────────

    @Synchronized private fun commit(list: List<ApiSource>) {
        _sources.value = list
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("baseUrl", s.baseUrl)
                    .put("type", s.type.name).put("enabled", s.enabled).put("builtIn", s.builtIn)
                    .put("verified", s.verified).put("lastCheckedAt", s.lastCheckedAt)
                    .put("lastStatus", s.lastStatus).put("lastError", s.lastError)
            )
        }
        prefs.edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    private fun load(): List<ApiSource> {
        val raw = prefs.getString(KEY_SOURCES, null)
        if (raw == null) {
            val d = defaults()
            // Can't call commit() here (property initialisers run before the flow exists).
            prefs.edit().putString(KEY_SOURCES, encode(d)).apply()
            return d
        }
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return defaults()
        val out = ArrayList<ApiSource>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Tolerate unknown/old entries instead of discarding the whole list.
            val type = runCatching { AnimeApiType.valueOf(o.getString("type")) }.getOrNull() ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            // Older builds stored the MAL Client ID in plain JSON; move it into encrypted storage.
            val legacyClientId = o.optString("clientId", "")
            if (legacyClientId.isNotBlank() && !hasClientId(id)) setClientId(id, legacyClientId)
            // The built-in Jikan-Edge entry used to point at Jikan v4 URLs; Jikan-Edge has its own /v1 schema.
            val migrated = id == "jikan-edge" && o.optString("type") != AnimeApiType.JIKAN_EDGE.name
            out += ApiSource(
                id = id,
                name = if (migrated) "Jikan-Edge" else o.optString("name", id),
                baseUrl = if (migrated) JIKAN_EDGE_URL else o.optString("baseUrl"),
                type = if (migrated) AnimeApiType.JIKAN_EDGE else type,
                enabled = o.optBoolean("enabled", true), builtIn = o.optBoolean("builtIn", false),
                verified = !migrated && o.optBoolean("verified", false), lastCheckedAt = o.optLong("lastCheckedAt", 0L),
                lastStatus = o.optString("lastStatus", ""), lastError = o.optString("lastError", "")
            )
        }
        return out
    }

    private fun encode(list: List<ApiSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("baseUrl", s.baseUrl)
                    .put("type", s.type.name).put("enabled", s.enabled).put("builtIn", s.builtIn)
                    .put("verified", s.verified)
            )
        }
        return arr.toString()
    }

    private fun clientKey(id: String) = "mal_client_id:$id"

    companion object {
        const val JIKAN_EDGE_URL = "https://jikan-edge.lucas-hdo.workers.dev/v1"
        private const val KEY_SOURCES = "sources"
        private const val KEY_AUTO_FALLBACK = "auto_fallback"

        /**
         * Out-of-the-box list. Only AniList and Kitsu (public, documented, key-less APIs) are
         * enabled. MyAnimeList needs the user's Client ID. The community endpoints from the design
         * mockup are listed but disabled and unverified until the user tests them.
         */
        fun defaults(): List<ApiSource> = listOf(
            ApiSource("anilist", "AniList", "https://graphql.anilist.co", AnimeApiType.ANILIST_GRAPHQL, enabled = true, builtIn = true),
            ApiSource("mal", "MyAnimeList (MAL)", "https://api.myanimelist.net/v2", AnimeApiType.MAL_V2, enabled = false, builtIn = true),
            ApiSource("kitsu", "Kitsu", "https://kitsu.io/api/edge", AnimeApiType.KITSU_JSON_API, enabled = true, builtIn = true),
            ApiSource("tenrai", "Tenrai API", "https://api.tenrai.org/v1", AnimeApiType.JIKAN_COMPATIBLE, enabled = false, builtIn = true),
            ApiSource("jikan-edge", "Jikan-Edge", JIKAN_EDGE_URL, AnimeApiType.JIKAN_EDGE, enabled = false, builtIn = true)
        )
    }
}
