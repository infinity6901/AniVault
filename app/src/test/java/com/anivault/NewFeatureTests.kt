package com.anivault

import com.anivault.data.library.Franchise
import com.anivault.data.library.LibraryQuery
import com.anivault.data.library.MalParser
import com.anivault.data.library.ProgressRules
import com.anivault.data.library.SmartListPreset
import com.anivault.data.library.SmartLists
import com.anivault.data.library.StatsCalculator
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.AiringFormat
import com.anivault.data.provider.RedirectOriginGuard
import com.anivault.data.remote.model.PreviewScraperSource
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.scraper.AniyomiAdaptiveParser
import com.anivault.data.remote.scraper.CustomMirrorUrls
import com.anivault.data.remote.scraper.HanimeAniyomiCodec
import com.anivault.data.remote.scraper.UncensoredCoverMatcher
import com.anivault.data.sync.OAuthSecurity
import com.anivault.ui.common.CoverPalette
import com.anivault.ui.common.CoverUrls
import com.anivault.ui.common.Tags
import com.anivault.ui.export.ListExportCodec
import com.anivault.ui.theme.LiquidGlassConfig
import com.anivault.ui.theme.LiquidGlassTint
import com.anivault.ui.theme.Palette
import com.anivault.widget.AiringWidgetText
import com.anivault.widget.WidgetRules
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NewFeatureTests {
    @Test fun airingFormat() {
        assertEquals("in 3h 0m", AiringFormat.relative(3 * 3_600_000L, 0))
        assertEquals("airing now", AiringFormat.relative(10_000L, 0))
        assertEquals("aired 2h 0m ago", AiringFormat.relative(0, 2 * 3_600_000L))
    }

    @Test fun tagsAreCleanedAndUnique() {
        assertEquals(listOf("fav"), Tags.add(listOf("fav"), "  FAV "))
        assertEquals(listOf("fav", "re watch"), Tags.add(listOf("fav"), "re   watch"))
        assertEquals(listOf("fav"), Tags.add(listOf("fav"), "   "))
    }

    @Test fun tagFilterMatchesCaseInsensitively() {
        val e = AnimeEntry(id = "a", title = "A", tags = listOf("Favourite"))
        val q = LibraryQuery(tag = "favourite")
        assertTrue(q.tag.equals(e.tags.first(), true))
    }

    @Test fun malStatusAndDates() {
        assertEquals(WatchStatus.ON_HOLD, MalParser.status("On-Hold"))
        assertEquals(WatchStatus.COMPLETED, MalParser.status("2"))
        assertNull(MalParser.date("0000-00-00"))
        assertTrue(MalParser.date("2020-01-02")!! > 0)
    }

    @Test fun malParsesXml() {
        val xml = """<myanimelist><anime><series_animedb_id>1</series_animedb_id><series_title>Cowboy Bebop</series_title>
            <series_episodes>26</series_episodes><my_watched_episodes>26</my_watched_episodes><my_score>9</my_score>
            <my_status>Completed</my_status><my_tags>fav, space</my_tags><my_start_date>0000-00-00</my_start_date></anime></myanimelist>"""
        val r = MalParser.parse(xml.byteInputStream())
        assertEquals(1, r.size)
        assertEquals(listOf("fav", "space"), r[0].tags)
        assertEquals(WatchStatus.COMPLETED, r[0].status)
        assertEquals("mal:1", MalParser.toEntry(r[0], 5L).id)
    }

    @Test fun malRejectsDoctype() {
        val xml = """<!DOCTYPE x [<!ENTITY e "boom">]><myanimelist><anime><series_animedb_id>1</series_animedb_id></anime></myanimelist>"""
        val failed = runCatching { MalParser.parse(xml.byteInputStream()) }.isFailure
        assertTrue(failed)
    }

    @Test fun widgetPicksWatchingAndWraps() {
        val a = AnimeEntry(id = "a", status = WatchStatus.WATCHING, totalEpisodes = 12, watchedEpisodes = 3, updatedAt = 5)
        val b = AnimeEntry(id = "b", status = WatchStatus.WATCHING, totalEpisodes = 12, watchedEpisodes = 12, updatedAt = 9)
        val c = AnimeEntry(id = "c", status = WatchStatus.COMPLETED)
        val candidates = WidgetRules.candidates(listOf(a, b, c))
        assertEquals(listOf("a"), candidates.map { it.id })
        assertEquals(0, WidgetRules.wrap(2, 2)); assertEquals(1, WidgetRules.wrap(-1, 2)); assertEquals(0, WidgetRules.wrap(5, 0))
        // Target resolution matches renderedId and returns null if the rendered show already completed.
        assertEquals("a", WidgetRules.resolveTarget(candidates, 0, "a")?.id)
        assertNull(WidgetRules.resolveTarget(candidates, 0, "b"))
    }

    @Test fun franchiseKeysGroupSeasons() {
        val k = Franchise.key("Frieren")
        assertEquals(k, Franchise.key("Frieren Season 2"))
        assertEquals(k, Franchise.key("Frieren: 2nd Season"))
        assertEquals(k, Franchise.key("Frieren Part 2"))
        assertTrue(Franchise.key("Mob Psycho 100") != Franchise.key("Mob Psycho"))
        assertEquals("86", Franchise.key("86"))
        assertEquals("The Apothecary Diaries", Franchise.cleanTitle("The Apothecary Diaries Season 3"))
        assertEquals("Cyberpunk: Edgerunners", Franchise.cleanTitle("Cyberpunk: Edgerunners 2"))
    }

    @Test fun franchiseGroupsKeepOrder() {
        val a = AnimeEntry(id = "1", title = "Show Season 2", totalEpisodes = 12, watchedEpisodes = 4)
        val b = AnimeEntry(id = "2", title = "Other")
        val c = AnimeEntry(id = "3", title = "Show", totalEpisodes = 12, watchedEpisodes = 12, status = WatchStatus.COMPLETED)
        val g = Franchise.group(listOf(a, b, c))
        assertEquals(2, g.size)
        assertEquals(listOf("1", "3"), g[0].members.map { it.id })
        assertEquals("Show", g[0].displayTitle)
        assertEquals(24, g[0].totalEpisodes)
        assertEquals(16, g[0].watchedEpisodes)
    }

    @Test fun coverUpgrades() {
        assertEquals("https://s4.anilist.co/file/anilistcdn/media/anime/cover/extraLarge/x.jpg", CoverUrls.upgrade("https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/x.jpg"))
        assertEquals("https://cdn.myanimelist.net/images/anime/1/2l.jpg", CoverUrls.upgrade("https://cdn.myanimelist.net/images/anime/1/2t.jpg"))
        assertEquals("https://example.com/a.jpg", CoverUrls.upgrade("https://example.com/a.jpg"))
    }

    @Test fun rewatchRulesAndStats() {
        val completed = AnimeEntry(
            id = "bebop",
            title = "Cowboy Bebop",
            status = WatchStatus.COMPLETED,
            totalEpisodes = 26,
            watchedEpisodes = 26,
            rewatchCount = 1
        )
        val rewatching = ProgressRules.startRewatch(completed, now = 1234L)
        assertEquals(WatchStatus.WATCHING, rewatching.status)
        assertEquals(0, rewatching.watchedEpisodes)
        assertEquals(2, rewatching.rewatchCount)

        assertEquals(52, StatsCalculator.totalWatchedWithRewatches(completed))
        val stats = StatsCalculator.compute(listOf(completed))
        assertEquals(52, stats.episodesWatched)
        assertEquals(1, stats.totalRewatches)
        assertEquals(1, stats.rewatchedTitles)
    }

    @Test fun smartListsRoundTripAndRandomPick() {
        val preset = SmartListPreset(
            name = "Short Fantasy",
            status = WatchStatus.PLAN_TO_WATCH,
            genre = "Fantasy",
            minRating = 8f
        )
        val encoded = SmartLists.encode(listOf(preset))
        val decoded = SmartLists.parse(encoded)
        assertEquals(1, decoded.size)
        assertEquals("Short Fantasy", decoded[0].name)
        assertEquals(WatchStatus.PLAN_TO_WATCH, decoded[0].status)
        assertEquals("Fantasy", decoded[0].genre)
        assertEquals(8f, decoded[0].minRating!!, 0.01f)

        val entries = listOf(
            AnimeEntry(id = "1", title = "Frieren", status = WatchStatus.PLAN_TO_WATCH, totalEpisodes = 12, genres = listOf("Fantasy")),
            AnimeEntry(id = "2", title = "One Piece", status = WatchStatus.PLAN_TO_WATCH, totalEpisodes = 1000, genres = listOf("Fantasy")),
            AnimeEntry(id = "3", title = "Cyberpunk", status = WatchStatus.WATCHING, totalEpisodes = 10, genres = listOf("Sci-Fi"))
        )
        val picked = SmartLists.pickRandom(entries, status = WatchStatus.PLAN_TO_WATCH, genre = "Fantasy", maxEpisodes = 13, random = Random(42))
        assertNotNull(picked)
        assertEquals("1", picked?.id)
    }

    @Test fun csvAndMalXmlExports() {
        val entry = AnimeEntry(
            id = "mal:1",
            title = "Cowboy Bebop, The Movie",
            format = "Movie",
            status = WatchStatus.COMPLETED,
            totalEpisodes = 1,
            watchedEpisodes = 1,
            rewatchCount = 2,
            rating = 9.5f,
            genres = listOf("Action", "Sci-Fi"),
            tags = listOf("classic"),
            remoteIds = RemoteIds.encode(mapOf("mal" to "1"))
        )
        val csv = ListExportCodec.toCsv(listOf(entry))
        val parsed = ListExportCodec.fromCsv(csv, now = 999L)
        assertEquals(1, parsed.size)
        assertEquals("Cowboy Bebop, The Movie", parsed[0].title)
        assertEquals(WatchStatus.COMPLETED, parsed[0].status)
        assertEquals(2, parsed[0].rewatchCount)
        assertEquals(listOf("Action", "Sci-Fi"), parsed[0].genres)

        val xml = ListExportCodec.toMalXml(listOf(entry))
        val malParsed = MalParser.parse(xml.byteInputStream())
        assertEquals(1, malParsed.size)
        assertEquals(1, malParsed[0].malId)
        assertEquals(WatchStatus.COMPLETED, malParsed[0].status)
    }

    @Test fun coverPaletteExtractsVibrantColour() {
        val grey = 0xFF333333.toInt()
        val vibrantCyan = 0xFF00E5FF.toInt()
        val extracted = CoverPalette.extractFromArgb(intArrayOf(grey, grey, vibrantCyan))
        assertNotNull(extracted)
    }

    @Test fun airingWidgetShowsBehindCount() {
        val text = AiringWidgetText.row("Frieren", 5, "in 2h 0m", behind = 2)
        assertTrue(text.contains("2 behind"))
    }

    @Test fun liquidGlassPaletteAndTintConfig() {
        assertEquals(Palette.LIQUID_GLASS, Palette.parse("liquid_glass"))
        assertEquals(LiquidGlassTint.SAKURA, LiquidGlassTint.parse("sakura"))
        assertEquals(LiquidGlassTint.AURORA, LiquidGlassTint.parse("unknown"))
        val cfg = LiquidGlassConfig(enabled = true, tint = LiquidGlassTint.CRYSTAL, opacityPct = 30, specularPct = 80)
        assertEquals(0.30f, cfg.panelAlpha, 0.001f)
        assertEquals(0.80f, cfg.specularIntensity, 0.001f)
    }

    @Test fun oauthStateRequiresPresentMatchingAndFreshValue() {
        val pending = "1000:abcXYZ123"
        // Valid matching state within TTL
        assertTrue(OAuthSecurity.verifyCallbackState(pending, "abcXYZ123", nowMs = 5000L, ttlMs = 60_000L))
        // Missing returned state MUST fail (Finding 1)
        assertFalse(OAuthSecurity.verifyCallbackState(pending, null, nowMs = 5000L, ttlMs = 60_000L))
        assertFalse(OAuthSecurity.verifyCallbackState(pending, "", nowMs = 5000L, ttlMs = 60_000L))
        // Wrong state MUST fail
        assertFalse(OAuthSecurity.verifyCallbackState(pending, "wrongState", nowMs = 5000L, ttlMs = 60_000L))
        // Expired state MUST fail
        assertFalse(OAuthSecurity.verifyCallbackState(pending, "abcXYZ123", nowMs = 99_000L, ttlMs = 60_000L))
        // Missing pending record MUST fail
        assertFalse(OAuthSecurity.verifyCallbackState(null, "abcXYZ123", nowMs = 5000L, ttlMs = 60_000L))
    }

    @Test fun malPkceUsesS256AndNeverLeaksVerifierInUrl() {
        // RFC 7636 Appendix B test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, OAuthSecurity.pkceS256Challenge(verifier))

        val url = OAuthSecurity.buildMalAuthUrl("client123", verifier, "state999", "anivault://auth/mal")
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=$expectedChallenge"))
        assertFalse(url.contains(verifier))
    }

    @Test fun redirectOriginGuardStripsCredentialsOnCrossOriginHop() {
        val initial = "https://api.myanimelist.net/v2/anime".toHttpUrl()
        val sameOriginReq = Request.Builder()
            .url("https://api.myanimelist.net/v2/users/@me/animelist")
            .header("Authorization", "Bearer secret")
            .header("X-MAL-CLIENT-ID", "client-secret")
            .build()
        val kept = RedirectOriginGuard.validateAndSanitizeHop(initial, sameOriginReq)
        assertEquals("Bearer secret", kept.header("Authorization"))
        assertEquals("client-secret", kept.header("X-MAL-CLIENT-ID"))

        val crossOriginReq = Request.Builder()
            .url("https://cdn.example.org/redirected")
            .header("Authorization", "Bearer secret")
            .header("X-MAL-CLIENT-ID", "client-secret")
            .header("Cookie", "sid=123")
            .build()
        val stripped = RedirectOriginGuard.validateAndSanitizeHop(initial, crossOriginReq)
        assertNull(stripped.header("Authorization"))
        assertNull(stripped.header("X-MAL-CLIENT-ID"))
        assertNull(stripped.header("Cookie"))
    }

    @Test fun hanimeAniyomiCodecParsesHitsAndDetailJson() {
        val searchJson = """
            {
              "hits": "[{\"slug\":\"overflow-1\",\"name\":\"Overflow Episode 1\",\"cover_url\":\"https://static-assets.highwinds-cdn.com/posters/overflow-1.jpg\",\"brand\":\"Studio Hokiboshi\",\"is_censored\":false,\"tags\":[\"Romance\",\"Uncensored\"],\"duration_in_ms\":480000}]"
            }
        """.trimIndent()
        val parsed = HanimeAniyomiCodec.parseSearchJson(searchJson)
        assertEquals(1, parsed.size)
        assertEquals("hanime:overflow-1", parsed[0].id)
        assertEquals("Overflow Episode 1", parsed[0].title)
        assertEquals("https://static-assets.highwinds-cdn.com/posters/overflow-1.jpg", parsed[0].coverUrl)
        assertEquals("Studio Hokiboshi", parsed[0].studio)
        assertFalse(parsed[0].isCensored)
        assertEquals(8, parsed[0].durationMinutes)
    }

    @Test fun aniyomiAdaptiveParserHandlesWpJsonDooPlayAndThumbUpgrades() {
        assertEquals(
            "https://watchhentai.net/wp-content/uploads/2025/04/cover.jpg",
            AniyomiAdaptiveParser.upgradeWordPressCoverUrl("https://watchhentai.net/wp-content/uploads/2025/04/cover-185x278.jpg")
        )

        val wpJson = """
            [
              {
                "slug": "isekai-harem-monogatari",
                "link": "https://watchhentai.net/series/isekai-harem-monogatari/",
                "title": { "rendered": "Isekai Harem Monogatari &#8211; Watch Hentai" },
                "_embedded": {
                  "wp:featuredmedia": [
                    { "source_url": "https://watchhentai.net/wp-content/uploads/2024/01/isekai-300x420.jpg" }
                  ]
                }
              }
            ]
        """.trimIndent()
        val wpParsed = AniyomiAdaptiveParser.parseWpJsonPosts(wpJson)
        assertEquals(1, wpParsed.size)
        assertEquals("Isekai Harem Monogatari", wpParsed[0].title)
        assertEquals("https://watchhentai.net/wp-content/uploads/2024/01/isekai.jpg", wpParsed[0].coverUrl)

        val dooPlayJson = """
            {
              "901": {
                "title": "Succubus Stayed Life",
                "url": "https://watchhentai.net/videos/succubus-stayed-life/",
                "img": "https://watchhentai.net/wp-content/uploads/succubus-150x150.png"
              }
            }
        """.trimIndent()
        val dpParsed = AniyomiAdaptiveParser.parseDooPlaySearchJson(dooPlayJson)
        assertEquals(1, dpParsed.size)
        assertEquals("Succubus Stayed Life", dpParsed[0].title)
        assertEquals("https://watchhentai.net/wp-content/uploads/succubus.png", dpParsed[0].coverUrl)
    }

    @Test fun uncensoredCoverMatcherDetectsCatalogCensorshipAndFindsBestScraperCover() {
        assertTrue(UncensoredCoverMatcher.isCensoredCatalogCover("https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx123.jpg"))
        assertTrue(UncensoredCoverMatcher.isCensoredCatalogCover("https://cdn.myanimelist.net/images/anime/10/999l.jpg"))
        assertTrue(UncensoredCoverMatcher.isCensoredCatalogCover("https://media.kitsu.io/anime/poster_images/1/large.jpg"))
        assertFalse(UncensoredCoverMatcher.isCensoredCatalogCover("https://static-assets.highwinds-cdn.com/posters/overflow.jpg"))
        assertFalse(UncensoredCoverMatcher.isCensoredCatalogCover("https://watchhentai.net/wp-content/uploads/overflow.jpg"))

        val candidates = listOf(
            ScrapedEntry(
                id = "hanime:unrelated",
                title = "Completely Different Show",
                coverUrl = "https://static-assets.highwinds-cdn.com/posters/other.jpg",
                tags = emptyList(),
                studio = null,
                isCensored = false,
                episodeCount = 1,
                durationMinutes = 20,
                detailUrl = "https://hanime.tv/videos/hentai/other"
            ),
            ScrapedEntry(
                id = "hanime:overflow-1",
                title = "Overflow The Animation Episode 1 (Uncensored)",
                coverUrl = "https://static-assets.highwinds-cdn.com/posters/overflow.jpg",
                tags = listOf("Uncensored"),
                studio = "Studio Hokiboshi",
                isCensored = false,
                episodeCount = 8,
                durationMinutes = 8,
                detailUrl = "https://hanime.tv/videos/hentai/overflow-1"
            )
        )
        val match = UncensoredCoverMatcher.bestMatch("Overflow", emptyList(), candidates)
        assertNotNull(match)
        assertEquals("https://static-assets.highwinds-cdn.com/posters/overflow.jpg", match?.coverUrl)
    }

    @Test fun customMirrorUrlsValidateNormalizeAndDisplaySource() {
        assertEquals(
            "https://watchhentai.org",
            CustomMirrorUrls.normalizeAndValidate("  watchhentai.org/ ").getOrNull()
        )
        assertEquals(
            "https://hentaihaven.xxx/sub",
            CustomMirrorUrls.normalizeAndValidate("https://HentaiHaven.xxx/sub/").getOrNull()
        )
        assertTrue(CustomMirrorUrls.normalizeAndValidate("http://watchhentai.org").isFailure)
        assertTrue(CustomMirrorUrls.normalizeAndValidate("https://127.0.0.1").isFailure)
        assertTrue(CustomMirrorUrls.normalizeAndValidate("https://192.168.1.10").isFailure)

        val list = CustomMirrorUrls.parseList("https://watchhentai.org/, https://hentaihaven.xxx")
        assertEquals(listOf("https://watchhentai.org", "https://hentaihaven.xxx"), list)
        assertEquals("https://watchhentai.org,https://hentaihaven.xxx", CustomMirrorUrls.encodeList(list))

        val entry = ScrapedEntry(
            id = "watchhentai:test",
            title = "Test",
            coverUrl = "https://hentaihaven.xxx/a.jpg",
            tags = emptyList(),
            studio = null,
            isCensored = false,
            episodeCount = 1,
            durationMinutes = 20,
            detailUrl = "https://www.hentaihaven.xxx/videos/test/"
        )
        assertEquals("hentaihaven.xxx", entry.displaySource)
    }

    @Test fun scraperDetailExtractsEpisodePreviewsAndSynopsis() {
        val hanimeDetailJson = """
            {
              "hentai_video": {
                "slug": "overflow-1",
                "name": "Overflow Episode 1",
                "cover_url": "https://static-assets.highwinds-cdn.com/covers/overflow.jpg",
                "poster_url": "https://static-assets.highwinds-cdn.com/posters/overflow-ep1.jpg",
                "brand": "Studio Hokiboshi",
                "is_censored": false,
                "description": "<p>Two sisters and a bath.</p>",
                "likes": 900,
                "dislikes": 100,
                "released_at": "2020-01-05",
                "duration_in_ms": 480000,
                "hentai_tags": [{"text": "Romance"}, {"text": "Uncensored"}]
              },
              "hentai_franchise_hentai_videos": [
                {
                  "slug": "overflow-1",
                  "name": "Overflow Episode 1",
                  "poster_url": "https://static-assets.highwinds-cdn.com/posters/overflow-ep1.jpg",
                  "duration_in_ms": 480000,
                  "released_at": "2020-01-05"
                },
                {
                  "slug": "overflow-2",
                  "name": "Overflow Episode 2",
                  "poster_url": "https://static-assets.highwinds-cdn.com/posters/overflow-ep2.jpg",
                  "duration_in_ms": 480000,
                  "released_at": "2020-01-12"
                }
              ]
            }
        """.trimIndent()
        val detail = HanimeAniyomiCodec.parseDetailJson(hanimeDetailJson, "https://hanime.tv/videos/hentai/overflow-1")
        assertNotNull(detail)
        assertEquals("Two sisters and a bath.", detail?.synopsis)
        assertEquals(2020, detail?.releaseYear)
        assertEquals(9.0f, detail?.ratingScore ?: 0f, 0.01f)
        assertEquals(2, detail?.episodePreviews?.size)
        assertEquals(1, detail?.episodePreviews?.get(0)?.episodeNumber)
        assertEquals("https://static-assets.highwinds-cdn.com/posters/overflow-ep1.jpg", detail?.episodePreviews?.get(0)?.previewImageUrl)
        assertEquals(2, detail?.episodePreviews?.get(1)?.episodeNumber)
        assertEquals("https://static-assets.highwinds-cdn.com/posters/overflow-ep2.jpg", detail?.episodePreviews?.get(1)?.previewImageUrl)

        val watchHentaiSeriesHtml = """
            <html><body>
              <h1 class="entry-title">Akogare no Onna Joushi</h1>
              <div class="poster"><img src="https://watchhentai.net/wp-content/uploads/cover-185x278.jpg"/></div>
              <div class="wp-content">
                <p>As they work together, they grow closer, but Shota has never had the courage to ask Yui to be his girlfriend.</p>
                <div id="dt_galery" class="galeria">
                  <div class="g-item"><img data-src="https://watchhentai.net/wp-content/uploads/scene1-300x169.jpg"/></div>
                  <div class="g-item"><img data-src="https://watchhentai.net/wp-content/uploads/scene2-300x169.jpg"/></div>
                </div>
              </div>
              <ul class="episodios">
                <li>
                  <div class="imagen"><img data-src="https://watchhentai.net/wp-content/uploads/cover.jpg"/></div>
                  <div class="numerando">1 - 1</div>
                  <div class="episodiotitle"><a href="https://watchhentai.net/videos/akogare-ep-1/">Episode 1</a></div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val whDetail = AniyomiAdaptiveParser.parseAdaptiveDetailHtml(watchHentaiSeriesHtml, "https://watchhentai.net/series/akogare-no-onna-joushi/")
        assertTrue(whDetail.synopsis.contains("As they work together"))
        assertEquals(2, whDetail.episodePreviews.size)
        assertEquals("https://watchhentai.net/wp-content/uploads/scene1.jpg", whDetail.episodePreviews[0].previewImageUrl)
        assertEquals("https://watchhentai.net/wp-content/uploads/scene2.jpg", whDetail.episodePreviews[1].previewImageUrl)
        assertFalse(whDetail.episodePreviews.any { it.previewImageUrl.contains("cover") })
    }

    @Test fun previewScraperSourceFallbackOrderStartsWithSelectedPrimary() {
        assertEquals(PreviewScraperSource.WATCHHENTAI, PreviewScraperSource.parse(null))
        assertEquals(PreviewScraperSource.HANIME, PreviewScraperSource.parse("hanime"))
        assertEquals(PreviewScraperSource.HENTAIHAVEN, PreviewScraperSource.parse("hentaihaven"))
        assertEquals(PreviewScraperSource.HSTREAM, PreviewScraperSource.parse("hstream"))

        val orderFromHanime = PreviewScraperSource.fallbackOrder(PreviewScraperSource.HANIME)
        assertEquals(PreviewScraperSource.HANIME, orderFromHanime.first())
        assertEquals(PreviewScraperSource.values().size, orderFromHanime.distinct().size)

        val orderFromHaven = PreviewScraperSource.fallbackOrder(PreviewScraperSource.HENTAIHAVEN)
        assertEquals(PreviewScraperSource.HENTAIHAVEN, orderFromHaven.first())
        assertTrue(PreviewScraperSource.WATCHHENTAI in orderFromHaven)
        assertTrue(PreviewScraperSource.HANIME in orderFromHaven)
    }
}
