package com.anivault.data.provider

import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * Adapter for Jikan-Edge (`/v1`, `{ "data": …, "meta": { "pagination": { "hasNextPage" } } }`, camelCase
 * fields such as `malId`, `titleEnglish`, `imageUrl`). It is deliberately not the Jikan v4 adapter:
 * Jikan-Edge documents that Jikan clients cannot be pointed at it.
 */
class JikanEdgeAdapter(
    private val source: ApiSource,
    private val http: HttpTransport
) : ProviderAdapter {

    override val sections: Set<BrowseSection> =
        setOf(BrowseSection.TRENDING, BrowseSection.SEASONAL, BrowseSection.TOP_RATED)

    private val base get() = source.baseUrl.trimEnd('/')

    override suspend fun search(query: String, page: Int): PagedResult =
        get("$base/anime?q=${URLEncoder.encode(query, "UTF-8")}&page=$page")

    override suspend fun browse(section: BrowseSection, page: Int): PagedResult = when (section) {
        BrowseSection.TOP_RATED -> get("$base/top/anime?page=$page")
        BrowseSection.TRENDING, BrowseSection.SEASONAL -> get("$base/seasons/now?page=$page")
        else -> throw ProviderException(FailureKind.UNSUPPORTED, "${source.name} does not offer “${section.label}”")
    }

    override suspend fun check() {
        get("$base/anime?q=naruto")
    }

    private suspend fun get(url: String): PagedResult {
        val res = http.execute(HttpRequestSpec(url = url, headers = mapOf("Accept" to "application/json")))
        HttpErrors.check(res, source.name)
        val root = HttpErrors.parse(res.body, source.name).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name} returned an unexpected response")
        val data = root.arr("data")
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name}: response has no data list (not Jikan-Edge?)")
        val objects = data.objects()
        // The catalogue is the safe-for-work list: adult-rated (“Rx”) titles are dropped client-side.
        val items = objects.filterNot { it.str("rating").orEmpty().startsWith("Rx", ignoreCase = true) }
            .mapNotNull { parse(it) }.take(PAGE_CAP)
        if (objects.isNotEmpty() && items.isEmpty() && objects.none { it.str("rating").orEmpty().startsWith("Rx", true) }) {
            throw ProviderException(FailureKind.MALFORMED, "${source.name}: items do not match the Jikan-Edge schema")
        }
        val hasNext = root.obj("meta")?.obj("pagination")?.get("hasNextPage")
            ?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
        return PagedResult(items, hasNext)
    }

    private fun parse(o: JsonObject): NormalizedAnime? {
        val malId = o.str("malId") ?: return null
        val primary = o.str("title") ?: return null
        val english = o.str("titleEnglish")
        val title = english?.takeIf { it.isNotBlank() } ?: primary
        val aired = o.obj("aired")?.str("from")
        val images = o.obj("images")
        return NormalizedAnime(
            provider = source.id,
            providerName = source.name,
            providerId = malId,
            title = title,
            altTitles = distinctTitles(title, listOf(primary, english, o.str("titleJapanese")), o.arr("titleSynonyms")?.strings() ?: emptyList()),
            coverUrl = images?.str("large") ?: o.str("imageUrl") ?: images?.str("medium") ?: "",
            synopsis = o.str("synopsis").orEmpty().trim(),
            year = o.int("year") ?: Dates.parseToMillis(aired)?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).year },
            season = prettyEnum(o.str("season")),
            startDate = Dates.parseToMillis(aired),
            format = o.str("type").orEmpty(),
            episodes = o.int("episodes") ?: 0,
            durationMinutes = o.int("duration") ?: firstInt(o.str("duration")) ?: 0,
            status = o.str("status").orEmpty(),
            genres = o.arr("genres")?.objects()?.mapNotNull { it.str("name") } ?: emptyList(),
            score = o.dbl("score")?.toFloat(),
            remoteIds = mapOf("mal" to malId)
        )
    }

    private companion object { const val PAGE_CAP = 25 }
}
