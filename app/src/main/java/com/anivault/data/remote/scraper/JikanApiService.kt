package com.anivault.data.remote.scraper

import com.anivault.data.remote.model.JikanSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Jikan REST API v4  –  https://api.jikan.moe/v4
 * No API key required. Rate-limited to ~3 req/s by the upstream server.
 */
interface JikanApiService {

    /**
     * Search for anime by keyword.
     *
     * @param q       Search query string.
     * @param page    1-indexed page number.
     * @param limit   Results per page (max 25 per Jikan's free tier).
     * @param sfw     Pass `true` to filter explicit results (optional).
     */
    @GET("anime")
    suspend fun searchAnime(
        @Query("q")     q:     String,
        @Query("page")  page:  Int  = 1,
        @Query("limit") limit: Int  = 20,
        @Query("sfw")   sfw:   Boolean = true
    ): JikanSearchResponse
}
