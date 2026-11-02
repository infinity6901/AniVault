package com.anivault.data.remote.scraper

import com.anivault.data.remote.model.ScrapedEntry

/**
 * Common contract for any H-Vault scraping source (hanime.tv, watchhentai.net, etc.).
 *
 * Each implementation is responsible for:
 *  - fetching a paginated search results page
 *  - optionally enriching a single entry with detail-page metadata
 *
 * [sourceName] is a human-readable label used for logging / debug UI.
 */
interface HentaiSource {

    val sourceName: String

    /**
     * Search for [query] on the given [page] (1-indexed).
     * Returns an empty list rather than throwing if no results are found.
     */
    suspend fun search(query: String, page: Int = 1): List<ScrapedEntry>

    /**
     * Fetch richer metadata for a single entry's detail page.
     * Returns null on any parse or network failure.
     */
    suspend fun fetchDetail(detailUrl: String): ScrapedEntry?
}
