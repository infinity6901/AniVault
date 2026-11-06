package com.anivault.data.provider

import com.google.gson.JsonObject
import java.net.URLEncoder

/** Kitsu JSON:API catalog adapter (no key needed). Excludes R18-rated titles. */
class KitsuAdapter(
    private val source: ApiSource,
    private val http: HttpTransport,
    private val adult: Boolean = false
) : ProviderAdapter {

    override val sections: Set<BrowseSection> =
        setOf(BrowseSection.TRENDING, BrowseSection.POPULAR, BrowseSection.SEASONAL, BrowseSection.TOP_RATED)

    private val safe get() = if (adult) "filter[ageRating]=R18" else SAFE
    private val base get() = source.baseUrl.trimEnd('/')

    override suspend fun search(query: String, page: Int): PagedResult =
        get("$base/anime?filter[text]=${URLEncoder.encode(query, "UTF-8")}&page[limit]=$PAGE_SIZE&page[offset]=${offset(page)}&$safe")

    override suspend fun browse(section: BrowseSection, page: Int): PagedResult {
        val paging = "page[limit]=$PAGE_SIZE&page[offset]=${offset(page)}"
        val url = when (section) {
            BrowseSection.TRENDING -> if (adult) "$base/anime?sort=-userCount&$paging&$safe" else "$base/trending/anime?limit=$PAGE_SIZE"
            BrowseSection.POPULAR -> "$base/anime?sort=-userCount&$paging&$safe"
            BrowseSection.TOP_RATED -> "$base/anime?sort=-averageRating&$paging&$safe"
            BrowseSection.SEASONAL -> {
                val s = Seasons.current()
                "$base/anime?filter[season]=${s.name.lowercase()}&filter[seasonYear]=${s.year}&sort=-userCount&$paging&$safe"
            }
            BrowseSection.RECENTLY_UPDATED ->
                throw ProviderException(FailureKind.UNSUPPORTED, "${source.name} does not offer “${section.label}”")
        }
        return get(url)
    }

    override suspend fun check() {
        get("$base/anime?page[limit]=1&$safe")
    }

    private fun offset(page: Int) = (page - 1).coerceAtLeast(0) * PAGE_SIZE

    private suspend fun get(url: String): PagedResult {
        val res = http.execute(HttpRequestSpec(url = url, headers = mapOf("Accept" to "application/vnd.api+json")))
        HttpErrors.check(res, source.name)
        val root = HttpErrors.parse(res.body, source.name).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name} returned an unexpected response")
        val data = root.arr("data")
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name}: response has no data list (not JSON:API?)")
        val objects = data.objects()
        val items = objects.mapNotNull { parse(it) }
        if (objects.isNotEmpty() && items.isEmpty()) {
            throw ProviderException(FailureKind.MALFORMED, "${source.name}: items do not match the Kitsu schema")
        }
        val hasNext = !root.obj("links")?.str("next").isNullOrBlank()
        return PagedResult(items, hasNext)
    }

    internal fun parse(o: JsonObject): NormalizedAnime? {
        val id = o.str("id") ?: return null
        val a = o.obj("attributes") ?: return null
        val titles = a.obj("titles")
        val canonical = a.str("canonicalTitle")
        val title = titles?.str("en")?.takeIf { it.isNotBlank() } ?: canonical ?: return null
        val poster = a.obj("posterImage")
        val cover = a.obj("coverImage")
        val startMs = Dates.parseToMillis(a.str("startDate"))
        return NormalizedAnime(
            provider = source.id,
            providerName = source.name,
            providerId = id,
            title = title,
            altTitles = distinctTitles(title, listOf(canonical, titles?.str("en_jp"), titles?.str("ja_jp")), a.arr("abbreviatedTitles")?.strings() ?: emptyList()),
            coverUrl = poster?.str("large") ?: poster?.str("original") ?: poster?.str("medium") ?: "",
            bannerUrl = cover?.str("large") ?: cover?.str("original") ?: "",
            synopsis = a.str("synopsis").orEmpty().trim(),
            year = startMs?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).year },
            startDate = startMs,
            format = prettyEnum(a.str("subtype")),
            episodes = a.int("episodeCount") ?: 0,
            durationMinutes = a.int("episodeLength") ?: 0,
            status = prettyEnum(a.str("status")),
            score = a.str("averageRating")?.toDoubleOrNull()?.let { (it / 10.0).toFloat() },
            adult = a.str("nsfw") == "true" || a.str("ageRating").equals("R18", ignoreCase = true),
            remoteIds = mapOf("kitsu" to id)
        )
    }

    companion object {
        /** Parses a Kitsu `anime` resource object outside of a search (used by account list sync). */
        fun parseItem(providerId: String, providerName: String, o: JsonObject): NormalizedAnime? =
            KitsuAdapter(ApiSource(providerId, providerName, "https://kitsu.io/api/edge", AnimeApiType.KITSU_JSON_API), NoTransport).parse(o)

        private const val PAGE_SIZE = 20
        private const val SAFE = "filter[ageRating]=G,PG,R"
    }
}
