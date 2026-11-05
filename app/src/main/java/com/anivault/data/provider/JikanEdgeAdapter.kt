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
    private val http: HttpTransport,
    private val adult: Boolean = false
) : ProviderAdapter {

    override val sections: Set<BrowseSection> =
        setOf(BrowseSection.TRENDING, BrowseSection.SEASONAL, BrowseSection.TOP_RATED)

    private val base get() = source.baseUrl.trimEnd('/')

    override suspend fun search(query: String, page: Int): PagedResult =
        get("$base/anime?q=${URLEncoder.encode(query, "UTF-8")}&page=$page")

    // Jikan-Edge answers HTTP 400 to `/seasons/now?page=…` (a season comes back in one response, with no
    // paging), so the season list is requested without paging. Trending uses the documented top list.
    override suspend fun browse(section: BrowseSection, page: Int): PagedResult = when (section) {
        BrowseSection.TOP_RATED, BrowseSection.TRENDING -> get("$base/top/anime?page=$page")
        BrowseSection.SEASONAL ->
            if (page > 1) PagedResult(emptyList(), false) else get("$base/seasons/now")
        else -> throw ProviderException(FailureKind.UNSUPPORTED, "${source.name} does not offer “${section.label}”")
    }

    override suspend fun check() {
        get("$base/anime?q=naruto")
    }

    private suspend fun get(url: String): PagedResult {
        val res = http.execute(HttpRequestSpec(url = url, headers = mapOf("Accept" to "application/json")))
        if (res.code == 400) {
            // Jikan-Edge explains itself as { "error": { "code", "message" } }; surface that instead of a bare 400.
            val detail = runCatching { HttpErrors.parse(res.body, source.name).asObjOrNull()?.obj("error")?.str("message") }.getOrNull()
            if (!detail.isNullOrBlank()) throw ProviderException(FailureKind.CLIENT, "${source.name} rejected the request: ${detail.take(140)}", 400)
        }
        HttpErrors.check(res, source.name)
        val root = HttpErrors.parse(res.body, source.name).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name} returned an unexpected response")
        val data = root.arr("data")
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name}: response has no data list (not Jikan-Edge?)")
        val objects = data.objects()
        // Normal lists hide adult-rated (“Rx”) titles; the H-Vault list keeps only those.
        fun isAdult(o: JsonObject) = o.str("rating").orEmpty().startsWith("Rx", ignoreCase = true) ||
            o.arr("genres")?.objects()?.any { it.str("name").equals("Hentai", ignoreCase = true) } == true
        val items = objects.filter { isAdult(it) == adult }.mapNotNull { parse(it) }.take(PAGE_CAP)
        if (objects.isNotEmpty() && items.isEmpty() && objects.none { isAdult(it) }) {
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
