package com.anivault.data.remote.model

import com.google.gson.annotations.SerializedName

// ── Scraped H-Vault result (not from Retrofit – built by JSoup) ────────────

data class ScrapedEntry(
    val id: String,
    val title: String,
    val coverUrl: String,
    val tags: List<String>,
    val studio: String?,
    val isCensored: Boolean,
    val episodeCount: Int,
    val durationMinutes: Int,
    val detailUrl: String
)
