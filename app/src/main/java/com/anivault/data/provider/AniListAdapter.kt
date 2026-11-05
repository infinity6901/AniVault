package com.anivault.data.provider

import com.google.gson.JsonObject

/** AniList GraphQL adapter (public catalog queries need no key). Adult titles are excluded. */
class AniListAdapter(
    private val source: ApiSource,
    private val http: HttpTransport,
    private val adult: Boolean = false
) : ProviderAdapter {

    override val sections: Set<BrowseSection> = BrowseSection.values().toSet()

    override suspend fun search(query: String, page: Int): PagedResult {
        val q = buildQuery("§search: String", "search: §search, sort: [SEARCH_MATCH]", adult)
        return post(q, vars(page).also { it.addProperty("search", query) })
    }

    override suspend fun browse(section: BrowseSection, page: Int): PagedResult {
        val vars = vars(page)
        val q = when (section) {
            BrowseSection.TRENDING -> buildQuery("", "sort: [TRENDING_DESC]", adult)
            BrowseSection.POPULAR -> buildQuery("", "sort: [POPULARITY_DESC]", adult)
            BrowseSection.TOP_RATED -> buildQuery("", "sort: [SCORE_DESC]", adult)
            BrowseSection.RECENTLY_UPDATED -> buildQuery("", "status: RELEASING, sort: [UPDATED_AT_DESC]", adult)
            BrowseSection.SEASONAL -> {
                val s = Seasons.current()
                vars.addProperty("season", s.name)
                vars.addProperty("seasonYear", s.year)
                buildQuery("§season: MediaSeason, §seasonYear: Int", "season: §season, seasonYear: §seasonYear, sort: [POPULARITY_DESC]", adult)
            }
        }
        return post(q, vars)
    }

    override suspend fun check() {
        post(buildQuery("", "sort: [POPULARITY_DESC]", adult), vars(1, perPage = 1))
    }

    private fun vars(page: Int, perPage: Int = PAGE_SIZE) = JsonObject().apply {
        addProperty("page", page)
        addProperty("perPage", perPage)
    }

    private suspend fun post(query: String, variables: JsonObject): PagedResult {
        val payload = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }
        val res = http.execute(
            HttpRequestSpec(
                url = source.baseUrl, method = "POST",
                headers = mapOf("Accept" to "application/json"),
                body = payload.toString()
            )
        )
        HttpErrors.check(res, source.name)
        val root = HttpErrors.parse(res.body, source.name).asObjOrNull()
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name} returned an unexpected response")
        val page = root.obj("data")?.obj("Page")
        if (page == null) {
            val msg = root.arr("errors")?.objects()?.firstOrNull()?.str("message")
            throw ProviderException(FailureKind.MALFORMED, "${source.name}: ${msg ?: "response has no Page data"}")
        }
        val media = page.arr("media")
            ?: throw ProviderException(FailureKind.MALFORMED, "${source.name}: response has no media list")
        val items = media.objects().mapNotNull { parseMedia(source.id, source.name, it) }
        val hasNext = page.obj("pageInfo")?.get("hasNextPage")
            ?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
        return PagedResult(items, hasNext)
    }

    companion object {
        const val PAGE_SIZE = 20

        /** Fields requested for every media object (shared with account sync). */
        const val MEDIA_FIELDS =
            "id idMal title { romaji english native } synonyms coverImage { extraLarge large } bannerImage " +
                "description(asHtml: false) format status episodes duration season seasonYear " +
                "startDate { year month day } genres averageScore countryOfOrigin isAdult"

        /** "§" stands in for "$" so GraphQL variables don't collide with Kotlin templates. */
        fun gql(text: String): String = text.replace('§', '$')

        private fun buildQuery(extraVarDecls: String, mediaArgs: String, adult: Boolean = false): String {
            val decls = if (extraVarDecls.isBlank()) "" else ", $extraVarDecls"
            return gql(
                "query (§page: Int, §perPage: Int$decls) { Page(page: §page, perPage: §perPage) { " +
                    "pageInfo { hasNextPage } media(type: ANIME, isAdult: $adult, $mediaArgs) { $MEDIA_FIELDS } } }"
            )
        }

        fun parseMedia(providerId: String, providerName: String, o: JsonObject): NormalizedAnime? {
            val id = o.str("id") ?: return null
            val t = o.obj("title")
            val english = t?.str("english")
            val romaji = t?.str("romaji")
            val native = t?.str("native")
            val title = listOf(english, romaji, native).firstOrNull { !it.isNullOrBlank() } ?: return null
            val start = o.obj("startDate")
            val cover = o.obj("coverImage")
            val remote = buildMap {
                put("anilist", id)
                o.str("idMal")?.let { put("mal", it) }
            }
            return NormalizedAnime(
                provider = providerId,
                providerName = providerName,
                providerId = id,
                title = title,
                altTitles = distinctTitles(title, listOf(romaji, english, native), o.arr("synonyms")?.strings() ?: emptyList()),
                coverUrl = cover?.str("extraLarge") ?: cover?.str("large") ?: "",
                bannerUrl = o.str("bannerImage") ?: "",
                synopsis = HtmlText.clean(o.str("description")),
                year = o.int("seasonYear") ?: start?.int("year"),
                season = prettyEnum(o.str("season")),
                startDate = Dates.fromParts(start?.int("year"), start?.int("month"), start?.int("day")),
                format = prettyEnum(o.str("format")),
                episodes = o.int("episodes") ?: 0,
                durationMinutes = o.int("duration") ?: 0,
                status = prettyEnum(o.str("status")),
                genres = o.arr("genres")?.strings() ?: emptyList(),
                score = o.dbl("averageScore")?.let { (it / 10.0).toFloat() },
                country = o.str("countryOfOrigin").orEmpty(),
                adult = o.str("isAdult") == "true",
                remoteIds = remote
            )
        }
    }
}
