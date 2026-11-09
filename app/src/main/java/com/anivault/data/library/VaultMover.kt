package com.anivault.data.library

import androidx.room.withTransaction
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.db.AppDatabase
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Pure conversions between library rows and H-Vault rows (unit-tested). */
object VaultMapping {
    private val SERVICES = listOf("anilist", "mal", "kitsu")

    /**
     * H-Vault id for a library entry. Titles linked to an account reuse the sync scheme ("sync:<service>:<id>") so a
     * later account sync sees that the title already lives in H-Vault and does not add it back to the library.
     */
    fun vaultId(e: AnimeEntry): String {
        val ids = RemoteIds.parse(e.remoteIds)
        SERVICES.firstOrNull { !ids[it].isNullOrBlank() }?.let { return "sync:$it:${ids[it]}" }
        return "moved:${e.id}"
    }

    fun toVault(e: AnimeEntry, now: Long): HVaultEntry = HVaultEntry(
        id = vaultId(e), title = e.title, imageUrl = e.imageUrl, totalEpisodes = e.totalEpisodes,
        watchedEpisodes = e.watchedEpisodes, status = e.status, rating = e.rating, studio = null, isCensored = false,
        tags = e.genres, notes = e.notes, durationMinutes = e.durationMinutes, updatedAt = now
    )

    fun libraryId(v: HVaultEntry): String = when {
        v.id.startsWith("sync:") -> v.id.removePrefix("sync:")
        v.id.startsWith("moved:") -> v.id.removePrefix("moved:")
        else -> "vault:${v.id}"
    }

    fun toLibrary(v: HVaultEntry, now: Long, linked: String = "", stashed: AnimeEntry? = null): AnimeEntry {
        val fromId = if (v.id.startsWith("sync:")) {
            val parts = v.id.removePrefix("sync:").split(":", limit = 2)
            if (parts.size == 2) mapOf(parts[0] to parts[1]) else emptyMap()
        } else emptyMap()
        val remote = (stashed?.let { RemoteIds.parse(it.remoteIds) } ?: emptyMap()) + RemoteIds.parse(linked) + fromId
        if (stashed != null) {
            return stashed.copy(
                title = v.title.ifBlank { stashed.title },
                imageUrl = v.imageUrl.ifBlank { stashed.imageUrl },
                totalEpisodes = if (v.totalEpisodes > 0) v.totalEpisodes else stashed.totalEpisodes,
                watchedEpisodes = v.watchedEpisodes,
                status = v.status,
                rating = v.rating,
                notes = v.notes,
                durationMinutes = if (v.durationMinutes > 0) v.durationMinutes else stashed.durationMinutes,
                updatedAt = now,
                remoteIds = RemoteIds.encode(remote),
                pendingSync = remote.isNotEmpty()
            )
        }
        return AnimeEntry(
            id = libraryId(v), title = v.title, imageUrl = v.imageUrl, totalEpisodes = v.totalEpisodes,
            watchedEpisodes = v.watchedEpisodes, status = v.status, rating = v.rating, genres = v.tags, notes = v.notes,
            durationMinutes = v.durationMinutes, updatedAt = now, addedAt = now, sourceName = "H-Vault",
            remoteIds = RemoteIds.encode(remote), pendingSync = remote.isNotEmpty()
        )
    }

    /** Merges H-Vault tracking updates into an existing library row when both have the same library ID. */
    fun mergeIntoLibrary(existing: AnimeEntry, fromVault: AnimeEntry, now: Long): AnimeEntry {
        val mergedRemote = RemoteIds.merge(existing.remoteIds, RemoteIds.parse(fromVault.remoteIds))
        return existing.copy(
            watchedEpisodes = fromVault.watchedEpisodes,
            totalEpisodes = if (existing.totalEpisodes > 0) existing.totalEpisodes else fromVault.totalEpisodes,
            status = fromVault.status,
            rating = if (fromVault.rating > 0f) fromVault.rating else existing.rating,
            notes = fromVault.notes.ifBlank { existing.notes },
            remoteIds = mergedRemote,
            updatedAt = now,
            pendingSync = mergedRemote.isNotBlank()
        )
    }
}

/** Progress rules for H-Vault rows, mirroring [ProgressRules] so both libraries behave the same. */
object VaultRules {
    fun withStatus(e: HVaultEntry, status: WatchStatus): HVaultEntry =
        if (status == WatchStatus.COMPLETED && e.totalEpisodes > 0) e.copy(status = status, watchedEpisodes = e.totalEpisodes)
        else e.copy(status = status)

    fun withProgress(e: HVaultEntry, watched: Int): HVaultEntry {
        val max = if (e.totalEpisodes > 0) e.totalEpisodes else Int.MAX_VALUE
        val w = watched.coerceIn(0, max)
        val reachedEnd = e.totalEpisodes > 0 && w >= e.totalEpisodes
        val status = when {
            reachedEnd -> WatchStatus.COMPLETED
            w > 0 && (e.status == WatchStatus.PLAN_TO_WATCH || e.status == WatchStatus.COMPLETED) -> WatchStatus.WATCHING
            else -> e.status
        }
        return e.copy(watchedEpisodes = w, status = status)
    }

    /** Last line of defence for any save path: a Completed entry with a known total always shows all episodes. */
    fun normalize(e: HVaultEntry): HVaultEntry = when {
        e.totalEpisodes > 0 && e.watchedEpisodes >= e.totalEpisodes -> e.copy(watchedEpisodes = e.totalEpisodes, status = WatchStatus.COMPLETED)
        e.status == WatchStatus.COMPLETED && e.totalEpisodes > 0 -> e.copy(watchedEpisodes = e.totalEpisodes)
        else -> e
    }
}

@Singleton
class VaultMover @Inject constructor(
    private val db: AppDatabase,
    private val animeDao: AnimeDao,
    private val vaultDao: HVaultDao,
    private val remoteOps: com.anivault.data.sync.RemoteOps,
    private val prefs: com.anivault.core.AppPrefs
) {
    /** Moves library rows into H-Vault. Returns how many moved. */
    suspend fun toVault(ids: List<String>): Int {
        val now = System.currentTimeMillis()
        val rows = ids.mapNotNull { animeDao.getById(it) }
        if (rows.isEmpty()) return 0
        db.withTransaction {
            vaultDao.upsertAll(rows.map { VaultMapping.toVault(it, now) })
            animeDao.deleteByIds(rows.map { it.id })
        }
        // Remember every account link and original metadata so moving back loses nothing.
        rows.forEach { e ->
            val vId = VaultMapping.vaultId(e)
            remoteOps.setLink(vId, e.remoteIds)
            remoteOps.setStash(vId, e)
            if (prefs.markVaultOnAccounts) remoteOps.enqueueAll(e.remoteIds, com.anivault.data.sync.OpType.MARK)
        }
        return rows.size
    }

    /** Moves H-Vault rows into the normal library. Returns how many moved. */
    suspend fun toLibrary(ids: List<String>): Int {
        val now = System.currentTimeMillis()
        val rows = ids.mapNotNull { vaultDao.getById(it) }
        if (rows.isEmpty()) return 0
        val toUpsert = rows.map { v ->
            val mapped = VaultMapping.toLibrary(v, now, remoteOps.link(v.id), remoteOps.stash(v.id))
            val existing = animeDao.getById(mapped.id)
            if (existing != null) VaultMapping.mergeIntoLibrary(existing, mapped, now) else mapped
        }
        db.withTransaction {
            animeDao.upsertAll(toUpsert)
            vaultDao.deleteByIds(rows.map { it.id })
        }
        rows.forEach { v ->
            val stashed = remoteOps.stash(v.id)
            val idsToUnmark = RemoteIds.encode(
                RemoteIds.parse(remoteOps.link(v.id)) +
                    VaultMapping.toLibrary(v, now, remoteOps.link(v.id), stashed).let { RemoteIds.parse(it.remoteIds) }
            )
            remoteOps.setLink(v.id, "")
            remoteOps.setStash(v.id, null)
            if (prefs.markVaultOnAccounts) remoteOps.enqueueAll(idsToUnmark, com.anivault.data.sync.OpType.UNMARK)
        }
        return rows.size
    }
}
