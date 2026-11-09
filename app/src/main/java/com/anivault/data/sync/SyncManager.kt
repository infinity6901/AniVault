package com.anivault.data.sync

import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.core.Sanitizer
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.library.VaultMapping
import com.anivault.data.library.VaultRules
import com.anivault.data.local.entity.AnimeEntry
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
    val error: String? = null,
    /** True when transient network/rate-limit/push failures occurred and WorkManager should retry. */
    val retryable: Boolean = false,
    /** IDs of local entries whose push failed during this run. */
    val failedEntryIds: Set<String> = emptySet(),
    /** Human-readable lines for the sync log (titles added, updated, sent, failed). */
    val details: List<String> = emptyList()
) {
    /** A sync is only fully successful when there is no top-level error AND no individual push failed. */
    val ok: Boolean get() = error == null && failedPushes == 0
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
    private val remoteOps: RemoteOps,
    private val syncLog: SyncLogStore
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Syncs every connected account. Local changes made while offline are in the pending queue and go out here. */
    suspend fun syncAll(): List<SyncReport> = mutex.withLock {
        _running.value = true
        try {
            val started = System.currentTimeMillis()
            val providers = accounts.connectedProviders()
            val reports = providers.map { run(it, false, multiProvider = providers.size > 1) }
            val failedIds = reports.flatMap { it.failedEntryIds }.toSet()
            if (reports.isNotEmpty() && reports.all { it.ok }) {
                // Everything that could be sent has been; don't leave never-matchable rows pending forever.
                val ids = dao.getPendingSync().filter { it.updatedAt <= started && it.id !in failedIds }.map { it.id }
                if (ids.isNotEmpty()) dao.clearPending(ids)
            }
            reports
        } finally { _running.value = false }
    }

    suspend fun sync(provider: AccountProvider): SyncReport = mutex.withLock {
        _running.value = true
        try { run(provider, false, multiProvider = accounts.connectedProviders().size > 1) } finally { _running.value = false }
    }

    /** Two-way sync of the H-Vault entries that are linked to accounts. Only the (unlocked) H-Vault screen calls this. */
    suspend fun syncVault(): List<SyncReport> = mutex.withLock {
        _running.value = true
        try {
            val providers = accounts.connectedProviders()
            providers.map { run(it, true, multiProvider = providers.size > 1) }
        } finally { _running.value = false }
    }

    private class OpsOutcome(val view: Map<String, RemoteListEntry>, val dropped: Set<String>, val unmarking: Set<String>, val deleted: Int, val hadFailures: Boolean)

    /** Carries out queued deletes / H-Vault tags for [p]. A rate limit or sign-in problem aborts the sync (the rest stays queued). */
    private suspend fun applyOps(p: AccountProvider, api: AccountApi, token: String, who: Identity, remoteAll: List<RemoteListEntry>, log: MutableList<String> = ArrayList()): OpsOutcome {
        val view = LinkedHashMap<String, RemoteListEntry>().apply { remoteAll.forEach { put(it.remoteMediaId, it) } }
        val dropped = HashSet<String>()
        val unmarking = HashSet<String>()
        var deleted = 0
        var hadFailures = false
        for (op in remoteOps.pending(p.key)) {
            val r = view[op.mediaId]
            try {
                when (op.type) {
                    OpType.DELETE -> {
                        if (r != null) { api.delete(token, who, op.mediaId, r.remoteEntryId); deleted++; log += "✕ Removed \"${r.media.title}\" from ${p.label}" }
                        view.remove(op.mediaId)
                    }
                    OpType.MARK -> if (r != null) {
                        if (!VaultOwnership.hasMark(r.notes) || p == AccountProvider.ANILIST || p == AccountProvider.KITSU) {
                            api.mark(token, who, r, true)
                            log += "# Tagged \"${r.media.title}\" as H-Vault on ${p.label}"
                            view[op.mediaId] = r.copy(notes = VaultOwnership.withMark(r.notes, true))
                        }
                    }
                    OpType.UNMARK -> if (r != null) {
                        api.mark(token, who, r, false)
                        log += "# Cleared the H-Vault tag on \"${r.media.title}\""
                        view[op.mediaId] = r.copy(notes = VaultOwnership.withMark(r.notes, false))
                    }
                }
                remoteOps.complete(op)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                if (e.kind == FailureKind.AUTH || e.kind == FailureKind.RATE_LIMITED) throw e
                hadFailures = true
                log += "✗ ${op.type.name.lowercase()} failed for title ${op.mediaId}: ${Sanitizer.clean(e.message)}"
                if (op.type == OpType.DELETE) dropped += op.mediaId
                if (op.type == OpType.UNMARK) unmarking += "${p.key}:${op.mediaId}"
            }
        }
        return OpsOutcome(view, dropped, unmarking, deleted, hadFailures)
    }

    private suspend fun run(p: AccountProvider, vaultMode: Boolean, multiProvider: Boolean = false): SyncReport {
        val t0 = System.currentTimeMillis()
        val log = ArrayList<String>()
        return try {
            val report = accounts.withToken(p) { token, who ->
                val api = accounts.api(p)
                val fetched = api.fetchList(token, who)
                val o = applyOps(p, api, token, who, fetched, log)
                val remoteAll = o.view.values.filter { it.remoteMediaId !in o.dropped }

                // ── who owns each title? ──────────────────────────────────────────
                val libraryAll = dao.getAll()
                val vaultAll = vaultDao.getAll()
                val ownership = Ownership(p, libraryAll, vaultAll, o.unmarking, record = true)
                val (vaultRemote, libRemote) = remoteAll.partition(ownership::isVault)
                val toVault = routeToVault(p, vaultRemote)
                val policy = ConflictPolicy.parse(prefs.conflictPolicy)
                val key = p.key
                var pushed = 0
                var failed = 0
                val failedIds = HashSet<String>()
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
                        val stamped = if (multiProvider) maxOf(pull.updated.updatedAt, t0) else pull.updated.updatedAt
                        vaultDao.upsert(
                            VaultRules.normalize(
                                v.copy(
                                    status = pull.updated.status,
                                    watchedEpisodes = pull.updated.watchedEpisodes,
                                    rating = pull.updated.rating,
                                    notes = if (p == AccountProvider.MAL) v.notes else pull.updated.notes,
                                    updatedAt = stamped
                                )
                            )
                        )
                    }
                    val notesByMedia = vaultRemote.associateBy { it.remoteMediaId }
                    val mark = prefs.markVaultOnAccounts
                    val items = plan.pushes.map { e ->
                        val mediaId = RemoteIds.parse(e.remoteIds)[key]
                        val baseNotes = e.notes.ifBlank { notesByMedia[mediaId]?.notes.orEmpty() }
                        val extra = if (mark) {
                            PushExtra(notes = VaultOwnership.withMark(baseNotes, true), private = true)
                        } else if (p != AccountProvider.MAL) {
                            PushExtra(notes = VaultOwnership.withMark(baseNotes, false))
                        } else PushExtra()
                        PushItem(e, extra)
                    }
                    plan.pulls.forEach { log += "↓ Updated \"${it.updated.title}\" in H-Vault" }
                    for (out in api.pushMany(token, who, items)) {
                        val entry = out.item.entry
                        if (out.result != null) {
                            pushed++
                            log += "↑ Sent \"${entry.title}\""
                        } else if (out.error?.kind == FailureKind.RATE_LIMITED) {
                            limited = out.error
                            failedIds += entry.id
                            log += "⏸ Rate limited at \"${entry.title}\""
                        } else {
                            failed++
                            failedIds += entry.id
                            log += "✗ \"${entry.title}\": ${Sanitizer.clean(out.error?.message)}"
                        }
                    }
                    if (limited == null && failed == 0 && !o.hadFailures) remoteOps.setVaultLastSync(key, t0)
                    report = SyncReport(
                        p, 0, plan.pulls.size, pushed, failed, plan.conflicts, plan.skippedUnmatched,
                        toVault, o.deleted, vaultMode = true, retryable = failed > 0 || o.hadFailures, failedEntryIds = failedIds
                    )
                } else {
                    val last = accounts.lastSyncAt(p)
                    val plan = SyncPlanner.plan(p, libraryAll, libRemote, last, policy)
                    val incoming = (plan.imports + plan.pulls.map { it.updated }).map { e ->
                        // When multiple accounts are connected, stamp pulled/imported rows at >= t0 so other accounts receive them too.
                        if (multiProvider) e.copy(updatedAt = maxOf(e.updatedAt, t0), pendingSync = true) else e
                    }
                    if (incoming.isNotEmpty()) dao.upsertAll(incoming)
                    plan.imports.forEach { log += "+ Added \"${it.title}\"" }
                    plan.pulls.forEach { log += "↓ Updated \"${it.updated.title}\" (${it.updated.watchedEpisodes}/${it.updated.totalEpisodes}, ${it.updated.status.label})" }
                    val byId = plan.pushes.associateBy { it.id }
                    val pushItems = plan.pushes.map { e ->
                        val extra = if (p != AccountProvider.MAL) PushExtra(notes = VaultOwnership.withMark(e.notes, false)) else PushExtra()
                        PushItem(e, extra)
                    }
                    for (out in api.pushMany(token, who, pushItems)) {
                        val entry = out.item.entry
                        if (out.result != null) {
                            pushed++
                            log += "↑ Sent \"${entry.title}\""
                            val ids = out.result.remoteEntryId?.let { mapOf("${key}Entry" to it) } ?: emptyMap()
                            val current = byId[entry.id] ?: entry
                            // Keep updatedAt so the pushed row isn't mistaken for a new local edit; clear pendingSync if single-provider.
                            dao.upsert(current.copy(remoteIds = RemoteIds.merge(entry.remoteIds, ids), pendingSync = if (multiProvider) current.pendingSync else false))
                        } else if (out.error?.kind == FailureKind.RATE_LIMITED) {
                            limited = out.error
                            failedIds += entry.id
                            log += "⏸ Rate limited at \"${entry.title}\""
                        } else {
                            failed++
                            failedIds += entry.id
                            // Ensure the failed row keeps pendingSync = true so it is retried on the next sync.
                            val current = byId[entry.id] ?: entry
                            dao.upsert(current.copy(pendingSync = true))
                            log += "✗ \"${entry.title}\": ${Sanitizer.clean(out.error?.message)}"
                        }
                    }
                    report = SyncReport(
                        p, plan.imports.size, plan.pulls.size, pushed, failed, plan.conflicts, plan.skippedUnmatched,
                        toVault, o.deleted, retryable = failed > 0 || o.hadFailures, failedEntryIds = failedIds
                    )
                }
                // Partial progress is kept; the rest is retried automatically.
                limited?.let { throw it }
                report
            }
            if (!vaultMode) {
                if (report.ok) accounts.recordSync(p, t0)
                else accounts.recordError(p, "${report.failedPushes} title(s) could not be sent")
            }
            diagnostics.record(p.label, if (vaultMode) "vault sync" else "sync", report.ok, System.currentTimeMillis() - t0)
            val done = report.copy(details = log)
            syncLog.add(SyncLogEntry(t0, p.label + if (vaultMode) " (H-Vault)" else "", report.ok, done.summary(), log))
            done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = (e as? ProviderException)?.kind
            if (kind == FailureKind.RATE_LIMITED) SyncWorker.runLater(ctx, 6)
            val retryable = kind == FailureKind.NETWORK || kind == FailureKind.TIMEOUT ||
                kind == FailureKind.RATE_LIMITED || kind == FailureKind.SERVER
            val msg = when (kind) {
                FailureKind.NETWORK, FailureKind.TIMEOUT -> "You appear to be offline. Changes stay queued."
                FailureKind.RATE_LIMITED -> "${p.label} is limiting requests. What was sent is kept; the rest goes out automatically in a few minutes."
                FailureKind.AUTH -> "Sign in again to continue."
                else -> Sanitizer.clean(e.message).ifBlank { "Sync failed" }
            }
            accounts.recordError(p, msg)
            log += "✗ $msg"
            syncLog.add(SyncLogEntry(t0, p.label + if (vaultMode) " (H-Vault)" else "", false, "${p.label}: $msg", log))
            diagnostics.record(p.label, if (vaultMode) "vault sync" else "sync", false, System.currentTimeMillis() - t0, e.message)
            SyncReport(p, error = msg, vaultMode = vaultMode, retryable = retryable)
        }
    }

    /** Decides, for each entry on an account, whether it belongs in H-Vault. [record] remembers links found by title. */
    private inner class Ownership(
        private val p: AccountProvider,
        libraryAll: List<AnimeEntry>,
        vaultAll: List<HVaultEntry>,
        private val unmarking: Set<String>,
        private val record: Boolean
    ) {
        private val owned = HashSet<String>()
        private val libraryKeys = libraryAll.flatMap { VaultOwnership.keysOf(it.remoteIds) }.toSet()
        private val vaultByTitle = vaultAll.groupBy { TitleMatcher.normalize(it.title) }.filterKeys { it.isNotEmpty() }

        init {
            val links = remoteOps.links()
            vaultAll.forEach { v -> owned += VaultOwnership.keysOfVaultId(v.id); links[v.id]?.let { owned += VaultOwnership.keysOf(it) } }
        }

        fun isVault(r: RemoteListEntry): Boolean {
            val k = "${p.key}:${r.remoteMediaId}"
            if (k in unmarking) return false
            if (r.media.adult || VaultOwnership.hasMark(r.notes) || k in owned) return true
            // Moved to H-Vault before every account link was recorded: recognise it by title and remember the link.
            if (k !in libraryKeys) {
                val v = vaultByTitle[TitleMatcher.normalize(r.media.title)]?.firstOrNull()
                if (v != null) {
                    owned += k
                    if (record) remoteOps.setLink(v.id, RemoteIds.merge(remoteOps.link(v.id), mapOf(p.key to r.remoteMediaId)))
                    return true
                }
            }
            return false
        }
    }

    /** What [syncAll] would do right now, without changing anything here or on the accounts. */
    suspend fun preview(): List<SyncReport> = mutex.withLock {
        _running.value = true
        try {
            accounts.connectedProviders().map { p ->
                try {
                    accounts.withToken(p) { token, who ->
                        val api = accounts.api(p)
                        val remoteAll = api.fetchList(token, who)
                        val byMedia = remoteAll.associateBy { it.remoteMediaId }
                        val lines = ArrayList<String>()
                        val ops = remoteOps.pending(p.key)
                        var dels = 0
                        ops.forEach { op ->
                            val title = byMedia[op.mediaId]?.media?.title ?: "title ${op.mediaId}"
                            when (op.type) {
                                OpType.DELETE -> { dels++; lines += "✕ Remove \"$title\" from ${p.label}" }
                                OpType.MARK -> lines += "# Tag \"$title\" as H-Vault on ${p.label}"
                                OpType.UNMARK -> lines += "# Remove the H-Vault tag from \"$title\""
                            }
                        }
                        val dropped = ops.filter { it.type == OpType.DELETE }.map { it.mediaId }.toSet()
                        val unmarking = ops.filter { it.type == OpType.UNMARK }.map { "${p.key}:${it.mediaId}" }.toSet()
                        val libraryAll = dao.getAll()
                        val vaultAll = vaultDao.getAll()
                        val ownership = Ownership(p, libraryAll, vaultAll, unmarking, record = false)
                        val (vaultRemote, libRemote) = remoteAll.filter { it.remoteMediaId !in dropped }.partition(ownership::isVault)
                        val known = HashSet<String>()
                        val links = remoteOps.links()
                        vaultAll.forEach { v -> known += VaultOwnership.keysOfVaultId(v.id); links[v.id]?.let { known += VaultOwnership.keysOf(it) } }
                        val newVault = vaultRemote.filter { "${p.key}:${it.remoteMediaId}" !in known }
                        newVault.forEach { lines += "🔒 \"${it.media.title}\" goes to H-Vault" }
                        val plan = SyncPlanner.plan(p, libraryAll, libRemote, accounts.lastSyncAt(p), ConflictPolicy.parse(prefs.conflictPolicy))
                        val localById = libraryAll.associateBy { it.id }
                        plan.imports.forEach { lines += "+ Add \"${it.title}\" (${it.watchedEpisodes}/${it.totalEpisodes})" }
                        plan.pulls.forEach { lines += "↓ Update \"${it.updated.title}\"${localById[it.updated.id]?.let { l -> ": ${l.watchedEpisodes} → ${it.updated.watchedEpisodes} eps, ${l.status.label} → ${it.updated.status.label}" } ?: ""}" }
                        plan.pushes.forEach { lines += "↑ Send \"${it.title}\" (${it.watchedEpisodes}/${it.totalEpisodes}, ${it.status.label})" }
                        if (plan.conflicts > 0) lines += "⚠ ${plan.conflicts} conflict(s) resolved by your policy"
                        if (plan.skippedUnmatched > 0) lines += "… ${plan.skippedUnmatched} local title(s) have no match on ${p.label} and are skipped"
                        if (lines.isEmpty()) lines += "Nothing to do. Everything matches."
                        SyncReport(p, plan.imports.size, plan.pulls.size, plan.pushes.size, 0, plan.conflicts, plan.skippedUnmatched, newVault.size, dels, details = lines)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SyncReport(p, error = if ((e as? ProviderException)?.kind == FailureKind.AUTH) "Sign in again to continue." else Sanitizer.clean(e.message).ifBlank { "Preview failed" })
                }
            }
        } finally { _running.value = false }
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
                        rating = r.score10, studio = null, isCensored = false, tags = m.genres,
                        notes = VaultOwnership.withMark(r.notes, false),
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
