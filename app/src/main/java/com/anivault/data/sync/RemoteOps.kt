package com.anivault.data.sync

import android.content.Context
import com.anivault.data.local.entity.RemoteIds
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Written into the notes of a service entry so any device knows the title belongs in H-Vault. */
const val VAULT_MARK = "[AniVault:H-Vault]"

enum class OpType { DELETE, MARK, UNMARK }

/** A change to an account that has to be made later (offline, rate limited, or just not part of a normal sync). */
data class RemoteOp(val type: OpType, val service: String, val mediaId: String)

/** Pure helpers, unit-tested. */
object VaultOwnership {
    private val SERVICES = listOf("anilist", "mal", "kitsu")

    /** "service:mediaId" keys for every account link a library/vault row carries. */
    fun keysOf(remoteIds: String): Set<String> {
        val ids = RemoteIds.parse(remoteIds)
        return SERVICES.mapNotNull { s -> ids[s]?.takeIf { it.isNotBlank() }?.let { "$s:$it" } }.toSet()
    }

    /** Keys carried by a "sync:<service>:<id>" vault id. */
    fun keysOfVaultId(id: String): Set<String> =
        if (id.startsWith("sync:")) setOf(id.removePrefix("sync:")) else emptySet()

    fun withMark(notes: String, vault: Boolean): String {
        val base = notes.replace(VAULT_MARK, "").trim()
        return if (vault) (if (base.isEmpty()) VAULT_MARK else "$base $VAULT_MARK") else base
    }

    fun hasMark(notes: String?): Boolean = notes?.contains(VAULT_MARK) == true
}

/** Small persistent store: queued account operations, vault↔account links and the vault's own last-sync time. */
@Singleton
class RemoteOps @Inject constructor(@ApplicationContext ctx: Context) {
    private val sp = ctx.getSharedPreferences("anivault_remote_ops", Context.MODE_PRIVATE)

    @Synchronized fun pending(service: String): List<RemoteOp> = readOps().filter { it.service == service }

    @Synchronized fun enqueue(op: RemoteOp) {
        val ops = readOps().toMutableList()
        // A newer instruction about the same title replaces older ones (move there and back = just the last one).
        ops.removeAll { it.service == op.service && it.mediaId == op.mediaId }
        ops += op
        writeOps(ops)
    }

    @Synchronized fun cancel(service: String, mediaId: String, type: OpType) {
        writeOps(readOps().filterNot { it.service == service && it.mediaId == mediaId && it.type == type })
    }

    @Synchronized fun complete(op: RemoteOp) {
        writeOps(readOps().filterNot { it == op })
    }

    /** Queues [type] for every account the row is linked to. */
    fun enqueueAll(remoteIds: String, type: OpType) {
        VaultOwnership.keysOf(remoteIds).forEach { k -> val (s, m) = k.split(":", limit = 2); enqueue(RemoteOp(type, s, m)) }
    }

    fun cancelAll(remoteIds: String, type: OpType) {
        VaultOwnership.keysOf(remoteIds).forEach { k -> val (s, m) = k.split(":", limit = 2); cancel(s, m, type) }
    }

    // ── vault links: vaultId -> every account id the title had when it moved into the vault ──
    @Synchronized fun setLink(vaultId: String, remoteIds: String) {
        val o = JSONObject(sp.getString("links", "{}") ?: "{}")
        if (remoteIds.isBlank()) o.remove(vaultId) else o.put(vaultId, remoteIds)
        sp.edit().putString("links", o.toString()).apply()
    }

    @Synchronized fun link(vaultId: String): String = JSONObject(sp.getString("links", "{}") ?: "{}").optString(vaultId, "")

    @Synchronized fun links(): Map<String, String> {
        val o = JSONObject(sp.getString("links", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun vaultLastSync(service: String): Long = sp.getLong("vault_last:$service", 0L)
    fun setVaultLastSync(service: String, at: Long) { sp.edit().putLong("vault_last:$service", at).apply() }

    private fun readOps(): List<RemoteOp> {
        val a = JSONArray(sp.getString("ops", "[]") ?: "[]")
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = runCatching { OpType.valueOf(o.optString("t")) }.getOrNull() ?: return@mapNotNull null
            RemoteOp(t, o.optString("s"), o.optString("m"))
        }
    }

    private fun writeOps(ops: List<RemoteOp>) {
        val a = JSONArray()
        ops.forEach { a.put(JSONObject().put("t", it.type.name).put("s", it.service).put("m", it.mediaId)) }
        sp.edit().putString("ops", a.toString()).apply()
    }
}
