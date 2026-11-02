package com.anivault.data.remote.model

import com.google.gson.annotations.SerializedName

// ── Jikan v4 response wrappers ─────────────────────────────────────────────

data class JikanSearchResponse(
    @SerializedName("data")       val data: List<JikanAnime>,
    @SerializedName("pagination") val pagination: JikanPagination?
)

data class JikanPagination(
    @SerializedName("has_next_page") val hasNextPage: Boolean,
    @SerializedName("current_page")  val currentPage: Int,
    @SerializedName("last_visible_page") val lastPage: Int
)

data class JikanAnime(
    @SerializedName("mal_id")       val malId: Int,
    @SerializedName("title")        val title: String,
    @SerializedName("title_english") val titleEnglish: String?,
    @SerializedName("images")       val images: JikanImages?,
    @SerializedName("episodes")     val episodes: Int?,
    @SerializedName("status")       val status: String?,
    @SerializedName("score")        val score: Float?,
    @SerializedName("year")         val year: Int?,
    @SerializedName("season")       val season: String?,
    @SerializedName("duration")     val duration: String?,
    @SerializedName("synopsis")     val synopsis: String?,
    @SerializedName("genres")       val genres: List<JikanGenre>?
)

data class JikanImages(
    @SerializedName("jpg") val jpg: JikanImageUrls?
)

data class JikanImageUrls(
    @SerializedName("image_url")       val imageUrl: String?,
    @SerializedName("large_image_url") val largeImageUrl: String?
)

data class JikanGenre(
    @SerializedName("name") val name: String
)

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
