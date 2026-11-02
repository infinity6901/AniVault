package com.anivault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anivault.data.local.model.WatchStatus

@Entity(tableName = "anime_entries")
data class AnimeEntry(
    @PrimaryKey val id: String,
    val title: String,
    val imageUrl: String,
    val totalEpisodes: Int,
    val watchedEpisodes: Int,
    val status: WatchStatus,
    val rating: Float,
    val year: Int?,
    val season: String,
    val genres: List<String>,
    val synopsis: String,
    val notes: String,
    val durationMinutes: Int,
    val updatedAt: Long = System.currentTimeMillis()
)
