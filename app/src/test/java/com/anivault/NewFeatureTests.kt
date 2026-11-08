package com.anivault

import com.anivault.data.library.LibraryQuery
import com.anivault.data.library.MalParser
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.AiringFormat
import com.anivault.ui.common.Tags
import com.anivault.widget.WidgetRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.anivault.data.library.Franchise
import com.anivault.ui.common.CoverUrls
import org.junit.Test

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
        assertEquals(listOf("a"), WidgetRules.candidates(listOf(a, b, c)).map { it.id })
        assertEquals(0, WidgetRules.wrap(2, 2)); assertEquals(1, WidgetRules.wrap(-1, 2)); assertEquals(0, WidgetRules.wrap(5, 0))
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
}
