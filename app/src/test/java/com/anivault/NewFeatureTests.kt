package com.anivault

import com.anivault.data.library.Franchise
import com.anivault.data.library.LibraryQuery
import com.anivault.data.library.MalParser
import com.anivault.data.library.ProgressRules
import com.anivault.data.library.SmartListPreset
import com.anivault.data.library.SmartLists
import com.anivault.data.library.SortField
import com.anivault.data.library.StatsCalculator
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.MediaType
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.AiringFormat
import com.anivault.ui.common.CoverPalette
import com.anivault.ui.common.CoverUrls
import com.anivault.ui.common.Tags
import com.anivault.ui.export.ListExportCodec
import com.anivault.ui.theme.LiquidGlassConfig
import com.anivault.ui.theme.LiquidGlassTint
import com.anivault.ui.theme.Palette
import com.anivault.widget.AiringWidgetText
import com.anivault.widget.WidgetRules
import org.junit.Assert.assertEquals
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
    }

    @Test fun franchiseGroupsKeepOrder() {
        val a = AnimeEntry(id = "1", title = "Show Season 2"); val b = AnimeEntry(id = "2", title = "Other"); val c = AnimeEntry(id = "3", title = "Show")
        val g = Franchise.group(listOf(a, b, c))
        assertEquals(2, g.size); assertEquals(listOf("1", "3"), g[0].members.map { it.id })
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

        val stats = StatsCalculator.compute(listOf(completed))
        assertEquals(26, stats.totalWatchedEpisodes)
        assertEquals(52, stats.totalWatchedWithRewatches)
        assertEquals(1, stats.totalRewatches)
    }

    @Test fun smartListsRoundTripAndRandomPick() {
        val preset = SmartListPreset(
            name = "Short Fantasy",
            status = WatchStatus.PLAN_TO_WATCH,
            genre = "Fantasy",
            sort = SortField.RATING,
            ascending = false
        )
        val encoded = SmartLists.encode(listOf(preset))
        val decoded = SmartLists.parse(encoded)
        assertEquals(1, decoded.size)
        assertEquals("Short Fantasy", decoded[0].name)
        assertEquals(WatchStatus.PLAN_TO_WATCH, decoded[0].status)
        assertEquals("Fantasy", decoded[0].genre)

        val entries = listOf(
            AnimeEntry(id = "1", title = "Frieren", status = WatchStatus.PLAN_TO_WATCH, totalEpisodes = 12, genres = listOf("Fantasy")),
            AnimeEntry(id = "2", title = "One Piece", status = WatchStatus.PLAN_TO_WATCH, totalEpisodes = 1000, genres = listOf("Fantasy")),
            AnimeEntry(id = "3", title = "Cyberpunk", status = WatchStatus.WATCHING, totalEpisodes = 10, genres = listOf("Sci-Fi"))
        )
        val picked = SmartLists.pickRandom(entries, status = WatchStatus.PLAN_TO_WATCH, genre = "Fantasy", maxEpisodes = 13, rng = Random(42))
        assertNotNull(picked)
        assertEquals("1", picked?.id)
    }

    @Test fun csvAndMalXmlExports() {
        val entry = AnimeEntry(
            id = "mal:1",
            malId = 1,
            title = "Cowboy Bebop, The Movie",
            mediaType = MediaType.MOVIE,
            status = WatchStatus.COMPLETED,
            totalEpisodes = 1,
            watchedEpisodes = 1,
            rewatchCount = 2,
            rating = 9.5f,
            genres = listOf("Action", "Sci-Fi"),
            tags = listOf("classic")
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
}
