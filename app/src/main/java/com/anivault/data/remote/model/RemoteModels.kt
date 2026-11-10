package com.anivault.data.remote.model

import com.anivault.data.provider.CharacterCredit
import com.anivault.data.provider.RelatedTitle
import com.anivault.data.provider.StreamingLink

/**
 * A single episode (or gallery page) preview fetched from an H-Vault scraper
 * (`hanime.tv`, `watchhentai.net` / mirrors, `nhentai.net`).
 */
data class VaultEpisodePreview(
    val episodeNumber: Int,
    val title: String,
    val previewImageUrl: String,
    val durationMinutes: Int = 0,
    val releaseDate: String = "",
    val detailUrl: String = "",
    val sourceName: String = ""
)

/**
 * Combined rich metadata for an H-Vault entry, merged from stashed library metadata,
 * uncensored scrapers (including episode preview images), and 18+ catalog lookups.
 */
data class VaultRichDetails(
    val synopsis: String = "",
    val format: String = "",
    val year: Int? = null,
    val season: String = "",
    val airingStatus: String = "",
    val communityScore: Float = 0f,
    val sourceLabel: String = "",
    val studios: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val altTitles: List<String> = emptyList(),
    val episodeCount: Int = 0,
    val durationMinutes: Int = 0,
    val episodePreviews: List<VaultEpisodePreview> = emptyList(),
    val galleryImages: List<String> = emptyList(),
    val characters: List<CharacterCredit> = emptyList(),
    val relations: List<RelatedTitle> = emptyList(),
    val links: List<StreamingLink> = emptyList()
)

data class ScrapedEntry(
    val id: String,
    val title: String,
    val coverUrl: String,
    val tags: List<String>,
    val studio: String?,
    val isCensored: Boolean,
    val episodeCount: Int,
    val durationMinutes: Int,
    val detailUrl: String,
    val sourceName: String = "",
    val synopsis: String = "",
    val previewUrl: String = "",
    val releaseYear: Int? = null,
    val ratingScore: Float = 0f,
    val episodePreviews: List<VaultEpisodePreview> = emptyList()
) {
    /** Human-readable source label shown under covers in H-Vault Search and Find Uncensored Cover. */
    val displaySource: String
        get() = sourceName.ifBlank {
            when {
                id.startsWith("hanime:") || detailUrl.contains("hanime.tv", ignoreCase = true) -> "hanime.tv"
                id.startsWith("nhentai:") || detailUrl.contains("nhentai.net", ignoreCase = true) -> "nhentai.net"
                detailUrl.startsWith("http") -> runCatching {
                    java.net.URI(detailUrl).host?.removePrefix("www.").orEmpty()
                }.getOrDefault("").ifBlank { "watchhentai.net" }
                else -> "Scraper"
            }
        }
}
