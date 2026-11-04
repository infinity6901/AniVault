package com.anivault.data.provider

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus

/** Catalog result → a new local library entry. The user's own rating/dates/notes start empty. */
fun NormalizedAnime.toEntry(
    watchStatus: WatchStatus = WatchStatus.PLAN_TO_WATCH,
    now: Long = System.currentTimeMillis()
): AnimeEntry = AnimeEntry(
    id = canonicalId,
    title = title,
    imageUrl = coverUrl,
    totalEpisodes = episodes,
    watchedEpisodes = 0,
    status = watchStatus,
    rating = 0f,
    year = year,
    season = season,
    genres = genres,
    synopsis = synopsis,
    notes = "",
    durationMinutes = durationMinutes,
    updatedAt = now,
    bannerUrl = bannerUrl,
    altTitles = altTitles,
    format = format,
    airingStatus = this.status,
    sourceName = providerName,
    remoteIds = RemoteIds.encode(remoteIds),
    startDate = null,
    endDate = null,
    addedAt = now,
    pendingSync = true,
    providerScore = score ?: 0f
)
