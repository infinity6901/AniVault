package com.anivault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anivault.data.local.model.WatchStatus

@Entity(tableName = "hvault_entries")
data class HVaultEntry(
    @PrimaryKey val id: String,
    val title: String,
    val imageUrl: String,
    val totalEpisodes: Int,
    val watchedEpisodes: Int,
    val status: WatchStatus,
    val rating: Float,
    val studio: String?,
    val isCensored: Boolean,
    val tags: List<String>,
    val notes: String,
    val durationMinutes: Int,
    val updatedAt: Long = System.currentTimeMillis()
)
