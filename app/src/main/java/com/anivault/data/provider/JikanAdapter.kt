package com.anivault.data.provider

import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * Adapter for endpoints implementing the Jikan v4 response shape
 * (`{ "data": [ { "mal_id", "title", "images": { "jpg": … } } ], "pagination": { "has_next_page" } }`).
 * It is NOT a universal REST translator: a source that answers with another schema fails the
 * connection test with "Unsupported response".
 */
class JikanAdapter(
    private val source: ApiSource,
    private val http: HttpTransport,
    private val adult: Boolean = false
) : ProviderAdapter {

    override val sections: Set<BrowseSection> =
        setOf(BrowseSection.TRENDING, BrowseSection.POPULAR, BrowseSection.SEASONAL, BrowseSection.TOP_RATED)

    private val SAFE get() = if (adult) "sfw=false&rating=rx" else "sfw=true"
    private val base get() = source.baseUrl.trimEnd('/')

    override suspend fun search(query: String, page: Int): PagedResult =
        get("$base/anime?q=${URLEncoder.encode(query, "UTF-8")}&page=$page&limit=$PAGE_SIZE&$SAFE")

    override suspend fun browse(section: BrowseSection, page: Int): PagedResult {
        val common = "page=$page&limit=$PAGE_SIZE&$SAFE"
        val url = when (section) {
            BrowseSection.TRENDING -> "$base/top/anime?filter=airing&$common"
            BrowseSection.POPULAR -> "$base/top/anime?filter=bypopularity&$common"
            BrowseSection.TOP_RATED -> "$base/top/anime?$common"
            BrowseSection.SEASONAL -> "$base/seasons/now?$common"
            BrowseSection.RECENTLY_UPDATED ->
                throw ProviderException(FailureKind.UNSUPPORTED, "${source.name} does not offer “${section.label}”")
        }
        return get(url)
    }

    override suspend fun browseSeason(year: Int, season: String, page: Int): PagedResult {
        val common = "page=$page&limit=$PAGE_SIZE&$SAFE"
        return get("$base/seasons/$year/${season.lowercase()}?$common")
    }

    override suspend fun check() {
        // get() validates the Jikan shape (a `data` list whose items have `title`).
        get("$base/anime?q=naruto&limit=1&$SAFE")
    }

    private suspend fun get(url: String): PagedResult {
        val res = http.execute(HttpRequestSpec(url = url, headers = mapOf("Accept" to "application/json")))
        HttpErrors.check(res, source.name)
        val root = HttpErrors.parse(res.body, source.name).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name} returned an unexpected response")
        val data = root.arr("data")
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name}: response has no data list (not Jikan-compatible?)")
        val objects = data.objects()
        val items = objects.mapNotNull { parse(it) }
        if (objects.isNotEmpty() && items.isEmpty()) {
            throw ProviderException(FailureKind.MALFORMED, "${source.name}: items do not match the Jikan schema")
        }
        val hasNext = root.obj("pagination")?.get("has_next_page")
            ?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
        return PagedResult(items, hasNext)
    }

    private fun parse(o: JsonObject): NormalizedAnime? {
        val malId = o.str("mal_id")
        val id = malId ?: o.str("id") ?: return null
        val primary = o.str("title") ?: return null
        val english = o.str("title_english")
        val title = english?.takeIf { it.isNotBlank() } ?: primary
        val jpg = o.obj("images")?.obj("jpg")
        val aired = o.obj("aired")?.str("from")
        return NormalizedAnime(
            provider = source.id,
            providerName = source.name,
            providerId = id,
            title = title,
            altTitles = distinctTitles(title, listOf(primary, english, o.str("title_japanese")), o.arr("title_synonyms")?.strings() ?: emptyList()),
            coverUrl = jpg?.str("large_image_url") ?: jpg?.str("image_url") ?: "",
            synopsis = o.str("synopsis").orEmpty().trim(),
            year = o.int("year") ?: Dates.parseToMillis(aired)?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).year },
            season = prettyEnum(o.str("season")),
            startDate = Dates.parseToMillis(aired),
            format = o.str("type").orEmpty(),
            episodes = o.int("episodes") ?: 0,
            durationMinutes = firstInt(o.str("duration")) ?: 0,
            status = o.str("status").orEmpty(),
            genres = o.arr("genres")?.objects()?.mapNotNull { it.str("name") } ?: emptyList(),
            score = o.dbl("score")?.toFloat(),
            remoteIds = if (malId != null) mapOf("mal" to malId) else emptyMap()
        )
    }

    private companion object { const val PAGE_SIZE = 20 }
}
