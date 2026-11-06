package com.anivault.data.sync

import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.core.Sanitizer
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.library.VaultMapping
import com.anivault.data.library.VaultRules
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.provider.TitleMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import com.anivault.data.provider.FailureKind
import com.anivault.data.provider.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(
    val provider: AccountProvider,
    val imported: Int = 0,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val failedPushes: Int = 0,
    val conflicts: Int = 0,
    val skippedUnmatched: Int = 0,
    /** Titles from the account that went to (or stayed in) H-Vault instead of the library. */
    val toVault: Int = 0,
    val deletedOnAccount: Int = 0,
    val vaultMode: Boolean = false,
    val error: String? = null
) {
    val ok: Boolean get() = error == null
    fun summary(): String =
        if (error != null) "${provider.label}: $error"
        else "${provider.label}${if (vaultMode) " (H-Vault)" else ""}: ${imported} added, ${pulled} updated here, ${pushed} sent" +
            (if (conflicts > 0) ", $conflicts conflict(s) resolved" else "") +
            (if (failedPushes > 0) ", $failedPushes could not be sent" else "") +
            (if (deletedOnAccount > 0) ", $deletedOnAccount removed on the account" else "") +
            (if (toVault > 0) ", $toVault title(s) kept in H-Vault" else "")
}

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val ctx: android.content.Context,
    private val dao: AnimeDao,
    private val vaultDao: com.anivault.data.local.dao.HVaultDao,
    private val accounts: AccountManager,
    private val prefs: AppPrefs,
    private val diagnostics: DiagnosticsLog,
    private val remoteOps: RemoteOps
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Syncs every connected account. Local changes made while offline are in the pending queue and go out here. */
    suspend fun syncAll(): List<SyncReport> = mutex.withLock {
        _running.value = true
        try {
            val started = System.currentTimeMillis()
            val reports = accounts.connectedProviders().map { run(it, false) }
            if (reports.isNotEmpty() && reports.all { it.ok }) {
                // Everything that could be sent has been; don't leave never-matchable rows pending forever.
                val ids = dao.getPendingSync().filter { it.updatedAt <= started }.map { it.id }
                if (ids.isNotEmpty()) dao.clearPending(ids)
            }
            reports
        } finally { _running.value = false }
    }

    suspend fun sync(provider: AccountProvider): SyncReport = mutex.withLock {
        _running.value = true
        try { run(provider, false) } finally { _running.value = false }
    }

    /** Two-way sync of the H-Vault entries that are linked to accounts. Only the (unlocked) H-Vault screen calls this. */
    suspend fun syncVault(): List<SyncReport> = mutex.withLock {
        _running.value = true
        try { accounts.connectedProviders().map { run(it, true) } } finally { _running.value = false }
    }

    private class OpsOutcome(val view: Map<String, RemoteListEntry>, val dropped: Set<String>, val unmarking: Set<String>, val deleted: Int)

    /** Carries out queued deletes / H-Vault tags for [p]. A rate limit or sign-in problem aborts the sync (the rest stays queued). */
    private suspend fun applyOps(p: AccountProvider, api: AccountApi, token: String, who: Identity, remoteAll: List<RemoteListEntry>): OpsOutcome {
        val view = LinkedHashMap<String, RemoteListEntry>().apply { remoteAll.forEach { put(it.remoteMediaId, it) } }
        val dropped = HashSet<String>()
        val unmarking = HashSet<String>()
        var deleted = 0
        for (op in remoteOps.pending(p.key)) {
            val r = view[op.mediaId]
            try {
                when (op.type) {
                    OpType.DELETE -> {
                        if (r != null) { api.delete(token, who, op.mediaId, r.remoteEntryId); deleted++ }
                        view.remove(op.mediaId)
                    }
                    OpType.MARK -> if (r != null) {
                        if (!VaultOwnership.hasMark(r.notes) || p == AccountProvider.ANILIST || p == AccountProvider.KITSU) {
                            api.mark(token, who, r, true)
                            view[op.mediaId] = r.copy(notes = VaultOwnership.withMark(r.notes, true))
                        }
                    }
                    OpType.UNMARK -> if (r != null) {
                        api.mark(token, who, r, false)
                        view[op.mediaId] = r.copy(notes = VaultOwnership.withMark(r.notes, false))
                    }
                }
                remoteOps.complete(op)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                if (e.kind == FailureKind.AUTH || e.kind == FailureKind.RATE_LIMITED) throw e
                if (op.type == OpType.DELETE) dropped += op.mediaId
                if (op.type == OpType.UNMARK) unmarking += "${p.key}:${op.mediaId}"
            }
        }
        return OpsOutcome(view, dropped, unmarking, deleted)
    }

    private suspend fun run(p: AccountProvider, vaultMode: Boolean): SyncReport {
        val t0 = System.currentTimeMillis()
        return try {
            val report = accounts.withToken(p) { token, who ->
                val api = accounts.api(p)
                val fetched = api.fetchList(token, who)
                val o = applyOps(p, api, token, who, fetched)
                val remoteAll = o.view.values.filter { it.remoteMediaId !in o.dropped }

                // ── who owns each title? ──────────────────────────────────────────
                val libraryAll = dao.getAll()
                val vaultAll = vaultDao.getAll()
                val links = remoteOps.links()
                val owned = HashSet<String>()
                vaultAll.forEach { v -> owned += VaultOwnership.keysOfVaultId(v.id); links[v.id]?.let { owned += VaultOwnership.keysOf(it) } }
                val libraryKeys = libraryAll.flatMap { VaultOwnership.keysOf(it.remoteIds) }.toSet()
                val vaultByTitle = vaultAll.groupBy { TitleMatcher.normalize(it.title) }.filterKeys { it.isNotEmpty() }

                fun isVault(r: RemoteListEntry): Boolean {
                    val k = "${p.key}:${r.remoteMediaId}"
                    if (k in o.unmarking) return false
                    if (r.media.adult || VaultOwnership.hasMark(r.notes) || k in owned) return true
                    // Moved to H-Vault before every account link was recorded: recognise it by title and remember the link.
                    if (k !in libraryKeys) {
                        val v = vaultByTitle[TitleMatcher.normalize(r.media.title)]?.firstOrNull()
                        if (v != null) {
                            owned += k
                            remoteOps.setLink(v.id, RemoteIds.merge(remoteOps.link(v.id), mapOf(p.key to r.remoteMediaId)))
                            return true
                        }
                    }
                    return false
                }

                val (vaultRemote, libRemote) = remoteAll.partition(::isVault)
                val toVault = routeToVault(p, vaultRemote)
                val policy = ConflictPolicy.parse(prefs.conflictPolicy)
                val key = p.key
                var pushed = 0
                var failed = 0
                var limited: ProviderException? = null
                val report: SyncReport

                if (vaultMode) {
                    val vaultNow = vaultDao.getAll()
                    val linkNow = remoteOps.links()
                    val local = vaultNow.mapNotNull { v ->
                        val ids = RemoteIds.merge(RemoteIds.encode(emptyMap()), buildMap {
                            VaultOwnership.keysOfVaultId(v.id).forEach { k -> val (s, m) = k.split(":", limit = 2); put(s, m) }
                            VaultOwnership.keysOf(linkNow[v.id].orEmpty()).forEach { k -> val (s, m) = k.split(":", limit = 2); putIfAbsent(s, m) }
                        })
                        if (RemoteIds.parse(ids)[key].isNullOrBlank()) null
                        else VaultMapping.toLibrary(v, v.updatedAt).copy(id = v.id, remoteIds = ids, updatedAt = v.updatedAt)
                    }
                    val plan = SyncPlanner.plan(p, local, vaultRemote, remoteOps.vaultLastSync(key), policy)
                    val byId = vaultNow.associateBy { it.id }
                    plan.pulls.forEach { pull ->
                        val v = byId[pull.updated.id] ?: return@forEach
                        vaultDao.upsert(VaultRules.normalize(v.copy(status = pull.updated.status, watchedEpisodes = pull.updated.watchedEpisodes, rating = pull.updated.rating, updatedAt = pull.updated.updatedAt)))
                    }
                    val notesByMedia = vaultRemote.associateBy { it.remoteMediaId }
                    val mark = prefs.markVaultOnAccounts
                    val items = plan.pushes.map { e ->
                        val mediaId = RemoteIds.parse(e.remoteIds)[key]
                        val extra = if (mark) PushExtra(notes = VaultOwnership.withMark(notesByMedia[mediaId]?.notes.orEmpty(), true), private = true) else PushExtra()
                        PushItem(e, extra)
                    }
                    for (out in api.pushMany(token, who, items)) {
                        if (out.result != null) pushed++
                        else if (out.error?.kind == FailureKind.RATE_LIMITED) limited = out.error
                        else failed++
                    }
                    if (limited == null) remoteOps.setVaultLastSync(key, t0)
                    report = SyncReport(p, 0, plan.pulls.size, pushed, failed, plan.conflicts, plan.skippedUnmatched, toVault, o.deleted, true)
                } else {
                    val last = accounts.lastSyncAt(p)
                    val plan = SyncPlanner.plan(p, libraryAll, libRemote, last, policy)
                    val incoming = plan.imports + plan.pulls.map { it.updated }
                    if (incoming.isNotEmpty()) dao.upsertAll(incoming)
                    val byId = plan.pushes.associateBy { it.id }
                    for (out in api.pushMany(token, who, plan.pushes.map { PushItem(it) })) {
                        val entry = out.item.entry
                        if (out.result != null) {
                            pushed++
                            val ids = out.result.remoteEntryId?.let { mapOf("${key}Entry" to it) } ?: emptyMap()
                            // Keep updatedAt so the pushed row isn't mistaken for a new local edit.
                            dao.upsert((byId[entry.id] ?: entry).copy(remoteIds = RemoteIds.merge(entry.remoteIds, ids)))
                        } else if (out.error?.kind == FailureKind.RATE_LIMITED) limited = out.error
                        else failed++
                    }
                    report = SyncReport(p, plan.imports.size, plan.pulls.size, pushed, failed, plan.conflicts, plan.skippedUnmatched, toVault, o.deleted)
                }
                // Partial progress is kept; the rest is retried automatically.
                limited?.let { throw it }
                report
            }
            if (!vaultMode) accounts.recordSync(p, t0)
            diagnostics.record(p.label, if (vaultMode) "vault sync" else "sync", true, System.currentTimeMillis() - t0)
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = (e as? ProviderException)?.kind
            if (kind == FailureKind.RATE_LIMITED) SyncWorker.runLater(ctx, 6)
            val msg = when (kind) {
                FailureKind.NETWORK, FailureKind.TIMEOUT -> "You appear to be offline. Changes stay queued."
                FailureKind.RATE_LIMITED -> "${p.label} is limiting requests. What was sent is kept; the rest goes out automatically in a few minutes."
                FailureKind.AUTH -> "Sign in again to continue."
                else -> Sanitizer.clean(e.message).ifBlank { "Sync failed" }
            }
            accounts.recordError(p, msg)
            diagnostics.record(p.label, if (vaultMode) "vault sync" else "sync", false, System.currentTimeMillis() - t0, e.message)
            SyncReport(p, error = msg, vaultMode = vaultMode)
        }
    }

    /** Saves vault-owned remote entries to H-Vault (new ones only, so vault edits are never overwritten) and removes any library copy. */
    private suspend fun routeToVault(p: AccountProvider, vaultRemote: List<RemoteListEntry>): Int {
        if (vaultRemote.isEmpty()) return 0
        val library = dao.getAll()
        val links = remoteOps.links()
        val have = HashSet<String>()
        vaultDao.getAll().forEach { v -> have += VaultOwnership.keysOfVaultId(v.id); links[v.id]?.let { have += VaultOwnership.keysOf(it) } }
        val removeIds = HashSet<String>()
        var added = 0
        for (r in vaultRemote) {
            val m = r.media
            val k = "${p.key}:${r.remoteMediaId}"
            if (k !in have) {
                val vaultId = "sync:$k"
                vaultDao.upsert(
                    HVaultEntry(
                        id = vaultId, title = m.title, imageUrl = m.coverUrl,
                        totalEpisodes = m.episodes, watchedEpisodes = r.progress, status = r.status,
                        rating = r.score10, studio = null, isCensored = false, tags = m.genres, notes = "",
                        durationMinutes = m.durationMinutes, updatedAt = if (r.updatedAt > 0) r.updatedAt else System.currentTimeMillis()
                    )
                )
                remoteOps.setLink(vaultId, RemoteIds.encode(m.remoteIds + (p.key to r.remoteMediaId)))
                have += k
                added++
            }
            val ids = m.remoteIds + (p.key to r.remoteMediaId)
            library.filter { e -> RemoteIds.parse(e.remoteIds).any { (kk, v) -> ids[kk] == v } }.forEach { removeIds += it.id }
        }
        if (removeIds.isNotEmpty()) dao.deleteByIds(removeIds.toList())
        return maxOf(added, removeIds.size)
    }
}
