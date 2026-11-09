package com.anivault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One logged episode or rewatch event in the user's watch history timeline. */
@Entity(tableName = "watch_history")
data class WatchHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val entryId: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val episode: Int = 0,
    val rewatchCount: Int = 0,
    val watchedAt: Long = System.currentTimeMillis()
)
