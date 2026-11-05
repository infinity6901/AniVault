package com.anivault.data.sync

import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.core.Sanitizer
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.entity.RemoteIds
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
    /** Adult-rated titles from the account that went to H-Vault instead of the library. */
    val toVault: Int = 0,
    val error: String? = null
) {
    val ok: Boolean get() = error == null
    fun summary(): String =
        if (error != null) "${provider.label}: $error"
        else "${provider.label}: ${imported} added, ${pulled} updated here, ${pushed} sent" +
            (if (conflicts > 0) ", $conflicts conflict(s) resolved" else "") +
            (if (failedPushes > 0) ", $failedPushes could not be sent" else "") +
            (if (toVault > 0) ", $toVault adult title(s) kept in H-Vault" else "")
}

@Singleton
class SyncManager @Inject constructor(
    private val dao: AnimeDao,
    private val vaultDao: com.anivault.data.local.dao.HVaultDao,
    private val accounts: AccountManager,
    private val prefs: AppPrefs,
    private val diagnostics: DiagnosticsLog
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Syncs every connected account. Local changes made while offline are in the pending queue and go out here. */
    suspend fun syncAll(): List<SyncReport> = mutex.withLock {
        _running.value = true
        try {
            val started = System.currentTimeMillis()
            val reports = accounts.connectedProviders().map { run(it) }
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
        try { run(provider) } finally { _running.value = false }
    }

    private suspend fun run(p: AccountProvider): SyncReport {
        val t0 = System.currentTimeMillis()
        return try {
            val report = accounts.withToken(p) { token, who ->
                val api = accounts.api(p)
                val allRemote = api.fetchList(token, who)
                // Adult-rated titles never enter the normal library: they are kept in H-Vault (and any that an
                // earlier sync already put in the library are moved there).
                val (adultRemote, remote) = allRemote.partition { it.media.adult }
                val toVault = routeAdultToVault(p, adultRemote)
                val local = dao.getAll()
                val last = accounts.lastSyncAt(p)
                val policy = ConflictPolicy.parse(prefs.conflictPolicy)
                val plan = SyncPlanner.plan(p, local, remote, last, policy)

                val incoming = plan.imports + plan.pulls.map { it.updated }
                if (incoming.isNotEmpty()) dao.upsertAll(incoming)

                var pushed = 0
                var failed = 0
                val key = p.key
                for (entry in plan.pushes) {
                    try {
                        val result = api.push(token, who, entry)
                        pushed++
                        val ids = result.remoteEntryId?.let { mapOf("${key}Entry" to it) } ?: emptyMap()
                        // Keep updatedAt so the pushed row isn't mistaken for a new local edit.
                        dao.upsert(entry.copy(remoteIds = RemoteIds.merge(entry.remoteIds, ids)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderException) {
                        if (e.kind == FailureKind.AUTH || e.kind == FailureKind.RATE_LIMITED) throw e
                        failed++
                    }
                }
                SyncReport(p, plan.imports.size, plan.pulls.size, pushed, failed, plan.conflicts, plan.skippedUnmatched, toVault)
            }
            accounts.recordSync(p, t0)
            diagnostics.record(p.label, "sync", true, System.currentTimeMillis() - t0)
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = when ((e as? ProviderException)?.kind) {
                FailureKind.NETWORK, FailureKind.TIMEOUT -> "You appear to be offline. Changes stay queued."
                FailureKind.RATE_LIMITED -> "Rate limited. Will retry later."
                FailureKind.AUTH -> "Sign in again to continue."
                else -> Sanitizer.clean(e.message).ifBlank { "Sync failed" }
            }
            accounts.recordError(p, msg)
            diagnostics.record(p.label, "sync", false, System.currentTimeMillis() - t0, e.message)
            SyncReport(p, error = msg)
        }
    }

    /** Saves adult entries to H-Vault (new ones only, so vault edits are never overwritten) and removes them from the library. */
    private suspend fun routeAdultToVault(p: AccountProvider, adult: List<RemoteListEntry>): Int {
        if (adult.isEmpty()) return 0
        val library = dao.getAll()
        val removeIds = HashSet<String>()
        var added = 0
        for (r in adult) {
            val m = r.media
            val vaultId = "sync:${p.key}:${r.remoteMediaId}"
            if (vaultDao.getById(vaultId) == null) {
                vaultDao.upsert(
                    com.anivault.data.local.entity.HVaultEntry(
                        id = vaultId, title = m.title, imageUrl = m.coverUrl,
                        totalEpisodes = m.episodes, watchedEpisodes = r.progress, status = r.status,
                        rating = r.score10, studio = null, isCensored = false, tags = m.genres, notes = "",
                        durationMinutes = m.durationMinutes
                    )
                )
                added++
            }
            val ids = m.remoteIds + (p.key to r.remoteMediaId)
            library.filter { e -> RemoteIds.parse(e.remoteIds).any { (k, v) -> ids[k] == v } }.forEach { removeIds += it.id }
        }
        if (removeIds.isNotEmpty()) dao.deleteByIds(removeIds.toList())
        return maxOf(added, removeIds.size)
    }
}
