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
}
