package com.anivault.data.provider

import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * MyAnimeList API v2 (catalog endpoints, authenticated with a user-supplied Client ID).
 * The Client ID is only ever sent to api.myanimelist.net – a MAL source pointing anywhere else
 * is refused so the credential can't leak to a third-party host.
 */
class MalAdapter(
    private val source: ApiSource,
    private val http: HttpTransport,
    private val clientId: () -> String?
) : ProviderAdapter {

    override val sections: Set<BrowseSection> =
        setOf(BrowseSection.TRENDING, BrowseSection.POPULAR, BrowseSection.SEASONAL, BrowseSection.TOP_RATED)

    private val base get() = source.baseUrl.trimEnd('/')

    override suspend fun search(query: String, page: Int): PagedResult {
        val q = query.trim()
        if (q.length < 3) throw ProviderException(FailureKind.CLIENT, "MyAnimeList search needs at least 3 characters")
        return get("$base/anime?q=${URLEncoder.encode(q, "UTF-8")}&limit=$PAGE_SIZE&offset=${(page - 1) * PAGE_SIZE}&fields=$FIELDS")
    }

    override suspend fun browse(section: BrowseSection, page: Int): PagedResult {
        val off = (page - 1) * PAGE_SIZE
        val url = when (section) {
            BrowseSection.TRENDING -> "$base/anime/ranking?ranking_type=airing&limit=$PAGE_SIZE&offset=$off&fields=$FIELDS"
            BrowseSection.POPULAR -> "$base/anime/ranking?ranking_type=bypopularity&limit=$PAGE_SIZE&offset=$off&fields=$FIELDS"
            BrowseSection.TOP_RATED -> "$base/anime/ranking?ranking_type=all&limit=$PAGE_SIZE&offset=$off&fields=$FIELDS"
            BrowseSection.SEASONAL -> {
                val s = Seasons.current()
                "$base/anime/season/${s.year}/${s.name.lowercase()}?sort=anime_num_list_users&limit=$PAGE_SIZE&offset=$off&fields=$FIELDS"
            }
            BrowseSection.RECENTLY_UPDATED ->
                throw ProviderException(FailureKind.UNSUPPORTED, "${source.name} does not offer “${section.label}”")
        }
        return get(url)
    }

    override suspend fun check() {
        get("$base/anime?q=naruto&limit=1&fields=id,title")
    }

    private suspend fun get(url: String): PagedResult {
        val host = runCatching { java.net.URI(source.baseUrl).host }.getOrNull()
        if (!host.equals(MAL_HOST, ignoreCase = true)) {
            throw ProviderException(FailureKind.UNSUPPORTED, "MyAnimeList sources must use $MAL_HOST")
        }
        val id = clientId()?.trim().orEmpty()
        if (id.isEmpty()) throw ProviderException(FailureKind.AUTH, "Add your MyAnimeList Client ID in this source's settings")
        val res = http.execute(HttpRequestSpec(url = url, headers = mapOf("Accept" to "application/json", "X-MAL-CLIENT-ID" to id)))
        HttpErrors.check(res, source.name)
        val root = HttpErrors.parse(res.body, source.name).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name} returned an unexpected response")
        val data = root.arr("data")
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name}: response has no data list")
        val objects = data.objects()
        val items = objects.mapNotNull { parse(it.obj("node") ?: it) }
        if (objects.isNotEmpty() && items.isEmpty()) {
            throw ProviderException(FailureKind.MALFORMED, "${source.name}: items do not match the MyAnimeList schema")
        }
        val hasNext = !root.obj("paging")?.str("next").isNullOrBlank()
        return PagedResult(items, hasNext)
    }

    internal fun parse(n: JsonObject): NormalizedAnime? {
        val id = n.str("id") ?: return null
        val title = n.str("title") ?: return null
        val alt = n.obj("alternative_titles")
        val pic = n.obj("main_picture")
        val season = n.obj("start_season")
        val startMs = Dates.parseToMillis(n.str("start_date"))
        return NormalizedAnime(
            provider = source.id,
            providerName = source.name,
            providerId = id,
            title = title,
            altTitles = distinctTitles(title, listOf(alt?.str("en"), alt?.str("ja")), alt?.arr("synonyms")?.strings() ?: emptyList()),
            coverUrl = pic?.str("large") ?: pic?.str("medium") ?: "",
            synopsis = n.str("synopsis").orEmpty().trim(),
            year = season?.int("year")
                ?: startMs?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).year },
            season = prettyEnum(season?.str("season")),
            startDate = startMs,
            format = prettyEnum(n.str("media_type")),
            episodes = n.int("num_episodes") ?: 0,
            durationMinutes = ((n.int("average_episode_duration") ?: 0) / 60),
            status = prettyEnum(n.str("status")),
            genres = n.arr("genres")?.objects()?.mapNotNull { it.str("name") } ?: emptyList(),
            score = n.dbl("mean")?.toFloat(),
            remoteIds = mapOf("mal" to id)
        )
    }

    companion object {
        /** Parses a MAL anime node outside of a search (used by account list sync). */
        fun parseNode(providerId: String, providerName: String, n: JsonObject): NormalizedAnime? =
            MalAdapter(ApiSource(providerId, providerName, "https://api.myanimelist.net/v2", AnimeApiType.MAL_V2), NoTransport) { null }.parse(n)

        const val MAL_HOST = "api.myanimelist.net"
        private const val PAGE_SIZE = 20
        const val FIELDS =
            "id,title,alternative_titles,main_picture,synopsis,mean,genres,num_episodes," +
                "average_episode_duration,start_season,start_date,media_type,status"
    }
}
