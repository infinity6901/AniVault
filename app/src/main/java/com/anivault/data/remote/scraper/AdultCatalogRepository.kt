package com.anivault.data.remote.scraper

import com.anivault.core.AppPrefs
import com.anivault.data.provider.AniListAdapter
import com.anivault.data.provider.AnimeApiType
import com.anivault.data.provider.ApiSource
import com.anivault.data.provider.HttpTransport
import com.anivault.data.provider.JikanAdapter
import com.anivault.data.provider.KitsuAdapter
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.provider.OkHttpTransport
import com.anivault.data.provider.ProviderAdapter
import com.anivault.data.remote.model.ScrapedEntry
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Adult-rated catalog databases used by H-Vault search. They are public JSON APIs (no scraping, no
 * browser check) whose cover art is the publisher's original, uncensored artwork, so they are tried
 * before the scrapers. Nothing here is user-configurable and nothing is sent except the search text.
 */
abstract class AdultCatalogSource(
    final override val sourceName: String,
    private val prefix: String
) : HentaiSource {
    protected abstract fun adapter(): ProviderAdapter

    override suspend fun search(query: String, page: Int): List<ScrapedEntry> =
        adapter().search(query, page).items.map { it.toScraped(prefix) }

    /** Catalog entries are saved straight from the search result, so there is no separate detail page. */
    override suspend fun fetchDetail(detailUrl: String): ScrapedEntry? = null
}

internal fun NormalizedAnime.toScraped(prefix: String) = ScrapedEntry(
    id = "$prefix:$providerId",
    title = title,
    coverUrl = coverUrl,
    tags = genres,
    studio = null,
    isCensored = false,
    episodeCount = episodes,
    durationMinutes = durationMinutes,
    detailUrl = "catalog:$prefix:$providerId"
)

@Singleton
class AdultCatalogRepository @Inject constructor(
    @Named("api") client: OkHttpClient,
    prefs: AppPrefs
) {
    private val http: HttpTransport = OkHttpTransport(client) { prefs.networkTimeoutSec }

    private fun src(id: String, name: String, url: String, type: AnimeApiType) = ApiSource(id, name, url, type, builtIn = true)

    val sources: List<HentaiSource> = listOf(
        object : AdultCatalogSource("AniList (18+)", "anilist18") {
            override fun adapter() = AniListAdapter(src("anilist18", sourceName, "https://graphql.anilist.co", AnimeApiType.ANILIST_GRAPHQL), http, adult = true)
        },
        object : AdultCatalogSource("MyAnimeList via Jikan (18+)", "jikan18") {
            override fun adapter() = JikanAdapter(src("jikan18", sourceName, "https://api.jikan.moe/v4", AnimeApiType.JIKAN_COMPATIBLE), http, adult = true)
        },
        object : AdultCatalogSource("Kitsu (18+)", "kitsu18") {
            override fun adapter() = KitsuAdapter(src("kitsu18", sourceName, "https://kitsu.io/api/edge", AnimeApiType.KITSU_JSON_API), http, adult = true)
        }
    )
}
