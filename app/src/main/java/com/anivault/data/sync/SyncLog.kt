package com.anivault.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SyncLogEntry(val time: Long, val label: String, val ok: Boolean, val summary: String, val lines: List<String>)

/** The last few sync runs with what each one did, so "what happened?" has an answer. Titles only; no tokens or notes. */
@Singleton
class SyncLogStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val sp = ctx.getSharedPreferences("anivault_sync_log", Context.MODE_PRIVATE)

    @Synchronized fun add(e: SyncLogEntry) {
        val all = (listOf(e) + recent()).take(MAX_RUNS)
        val a = JSONArray()
        all.forEach { r ->
            a.put(JSONObject().put("t", r.time).put("l", r.label).put("ok", r.ok).put("s", r.summary).put("d", JSONArray(r.lines.take(MAX_LINES))))
        }
        sp.edit().putString("runs", a.toString()).apply()
    }

    @Synchronized fun recent(): List<SyncLogEntry> {
        val a = runCatching { JSONArray(sp.getString("runs", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val d = o.optJSONArray("d") ?: JSONArray()
            SyncLogEntry(o.optLong("t"), o.optString("l"), o.optBoolean("ok"), o.optString("s"), (0 until d.length()).map { d.optString(it) })
        }
    }

    @Synchronized fun clear() { sp.edit().remove("runs").apply() }

    private companion object { const val MAX_RUNS = 25; const val MAX_LINES = 120 }
}
