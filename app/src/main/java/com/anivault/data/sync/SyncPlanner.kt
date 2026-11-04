package com.anivault.data.sync

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.TitleMatcher
import com.anivault.data.provider.toEntry
import kotlin.math.abs

/**
 * Pure decision logic for list synchronisation (spec §12). Nothing here touches the network or
 * the database, so every rule is unit-tested.
 *
 * "Changed" means edited after [lastSyncAt] (the time of the last successful sync with that
 * provider). With lastSyncAt == 0 (first sync) both sides count as changed, so the conflict
 * policy decides instead of silently overwriting either side.
 */
object SyncPlanner {

    data class Pull(val updated: AnimeEntry, val wasConflict: Boolean)

    data class Plan(
        /** On the account but not on this device: will be added locally. */
        val imports: List<AnimeEntry>,
        /** Account version replaces the local tracking fields. */
        val pulls: List<Pull>,
        /** Local version is sent to the account. */
        val pushes: List<AnimeEntry>,
        val conflicts: Int,
        val unchanged: Int,
        /** Local changes that can't be sent because the entry has no id for this service. */
        val skippedUnmatched: Int
    ) {
        val isEmpty: Boolean get() = imports.isEmpty() && pulls.isEmpty() && pushes.isEmpty()
    }

    fun plan(
        provider: AccountProvider,
        local: List<AnimeEntry>,
        remote: List<RemoteListEntry>,
        lastSyncAt: Long,
        policy: ConflictPolicy
    ): Plan {
        val key = provider.key
        val localByRemoteId = HashMap<String, AnimeEntry>()
        local.forEach { e -> RemoteIds.parse(e.remoteIds)[key]?.let { localByRemoteId[it] = e } }
        val localByName = local.groupBy { TitleMatcher.key(it.title, it.year) }

        val matched = HashSet<String>()
        val imports = ArrayList<AnimeEntry>()
        val pulls = ArrayList<Pull>()
        val pushes = ArrayList<AnimeEntry>()
        var conflicts = 0
        var unchanged = 0

        for (r in remote) {
            val byId = localByRemoteId[r.remoteMediaId]
            val l = byId ?: localByName[TitleMatcher.key(r.media.title, r.media.year)]
                ?.singleOrNull { it.id !in matched }
            if (l == null) {
                imports += entryFromRemote(r)
                continue
            }
            matched += l.id
            val remoteView = applyRemote(l, r, key)
            if (sameTracking(l, remoteView)) {
                unchanged++
                // Still remember the remote id so future syncs match by id.
                if (RemoteIds.parse(l.remoteIds)[key] != r.remoteMediaId) pulls += Pull(remoteView.copy(updatedAt = l.updatedAt), false)
                continue
            }
            val localChanged = l.updatedAt > lastSyncAt
            val remoteChanged = r.updatedAt > lastSyncAt
            when {
                remoteChanged && !localChanged -> pulls += Pull(remoteView, false)
                localChanged && !remoteChanged -> pushes += withRemoteIds(l, r, key)
                else -> {
                    conflicts++
                    val takeRemote = when (policy) {
                        ConflictPolicy.REMOTE -> true
                        ConflictPolicy.LOCAL -> false
                        ConflictPolicy.NEWEST -> r.updatedAt > l.updatedAt
                    }
                    if (takeRemote) pulls += Pull(remoteView, true) else pushes += withRemoteIds(l, r, key)
                }
            }
        }

        // Local entries the account doesn't have (yet).
        var skipped = 0
        for (l in local) {
            if (l.id in matched) continue
            if (l.updatedAt <= lastSyncAt) continue
            if (RemoteIds.parse(l.remoteIds)[key].isNullOrBlank()) skipped++ else pushes += l
        }
        return Plan(imports, pulls, pushes, conflicts, unchanged, skipped)
    }

    fun sameTracking(a: AnimeEntry, b: AnimeEntry): Boolean =
        a.status == b.status &&
            a.watchedEpisodes == b.watchedEpisodes &&
            abs(a.rating - b.rating) < 0.05f &&
            a.startDate == b.startDate &&
            a.endDate == b.endDate

    /** [local] with the account's tracking fields applied (what a pull would produce). */
    fun applyRemote(local: AnimeEntry, r: RemoteListEntry, key: String): AnimeEntry {
        val extra = buildMap {
            put(key, r.remoteMediaId)
            r.remoteEntryId?.let { put("${key}Entry", it) }
        }
        val stamp = if (r.updatedAt > 0) r.updatedAt else System.currentTimeMillis()
        return local.copy(
            status = r.status,
            watchedEpisodes = if (local.totalEpisodes > 0) r.progress.coerceAtMost(local.totalEpisodes) else r.progress,
            rating = r.score10.coerceIn(0f, 10f),
            startDate = r.startedAt,
            endDate = r.completedAt,
            remoteIds = RemoteIds.merge(local.remoteIds, extra),
            remoteUpdatedAt = r.updatedAt,
            updatedAt = stamp,
            pendingSync = false
        )
    }

    private fun withRemoteIds(l: AnimeEntry, r: RemoteListEntry, key: String): AnimeEntry {
        val extra = buildMap {
            put(key, r.remoteMediaId)
            r.remoteEntryId?.let { put("${key}Entry", it) }
        }
        return l.copy(remoteIds = RemoteIds.merge(l.remoteIds, extra))
    }

    fun entryFromRemote(r: RemoteListEntry): AnimeEntry {
        val stamp = if (r.updatedAt > 0) r.updatedAt else System.currentTimeMillis()
        val base = r.media.toEntry(WatchStatus.PLAN_TO_WATCH, stamp)
        val extra = buildMap {
            putAll(r.media.remoteIds)
            r.remoteEntryId?.let { put("${r.media.provider}Entry", it) }
        }
        return base.copy(
            status = r.status,
            watchedEpisodes = if (base.totalEpisodes > 0) r.progress.coerceAtMost(base.totalEpisodes) else r.progress,
            rating = r.score10.coerceIn(0f, 10f),
            startDate = r.startedAt,
            endDate = r.completedAt,
            remoteIds = RemoteIds.merge(base.remoteIds, extra),
            remoteUpdatedAt = r.updatedAt,
            updatedAt = stamp,
            pendingSync = false
        )
    }
}
