package com.anivault

import com.anivault.data.backup.*
import com.anivault.data.library.*
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.sync.*
import com.anivault.notify.QuietHours
import org.junit.Assert.*
import org.junit.Test

private fun entry(id: String, title: String = id, status: WatchStatus = WatchStatus.WATCHING, watched: Int = 0, total: Int = 12,
                  updated: Long = 0, rating: Float = 0f, remote: String = "") =
    AnimeEntry(id = id, title = title, status = status, watchedEpisodes = watched, totalEpisodes = total, updatedAt = updated, rating = rating, remoteIds = remote, year = 2020)

private fun remote(id: String, title: String, status: WatchStatus, progress: Int, updated: Long, score: Float = 0f) =
    RemoteListEntry(id, null, status, progress, score, null, null, updated, NormalizedAnime("anilist", "AniList", id, title, year = 2020, episodes = 12))

class SyncPlannerTest {
    private val p = AccountProvider.ANILIST
    private val ids = RemoteIds.encode(mapOf("anilist" to "1"))

    @Test fun imports_unknown_remote() {
        val plan = SyncPlanner.plan(p, emptyList(), listOf(remote("9", "New Show", WatchStatus.COMPLETED, 12, 5)), 0, ConflictPolicy.NEWEST)
        assertEquals(1, plan.imports.size); assertEquals(WatchStatus.COMPLETED, plan.imports[0].status)
    }
    @Test fun remote_only_change_pulls() {
        val l = entry("a", "Show", watched = 3, updated = 100, remote = ids)
        val plan = SyncPlanner.plan(p, listOf(l), listOf(remote("1", "Show", WatchStatus.WATCHING, 5, 500)), 200, ConflictPolicy.NEWEST)
        assertEquals(5, plan.pulls.single().updated.watchedEpisodes); assertTrue(plan.pushes.isEmpty())
    }
    @Test fun local_only_change_pushes() {
        val l = entry("a", "Show", watched = 6, updated = 500, remote = ids)
        val plan = SyncPlanner.plan(p, listOf(l), listOf(remote("1", "Show", WatchStatus.WATCHING, 5, 100)), 200, ConflictPolicy.NEWEST)
        assertEquals(1, plan.pushes.size); assertTrue(plan.pulls.isEmpty())
    }
    @Test fun conflict_policies() {
        val l = entry("a", "Show", watched = 6, updated = 400, remote = ids)
        val r = listOf(remote("1", "Show", WatchStatus.WATCHING, 5, 300))
        assertEquals(1, SyncPlanner.plan(p, listOf(l), r, 200, ConflictPolicy.NEWEST).pushes.size)
        assertEquals(1, SyncPlanner.plan(p, listOf(l), r, 200, ConflictPolicy.REMOTE).pulls.size)
        assertEquals(1, SyncPlanner.plan(p, listOf(l), r, 200, ConflictPolicy.LOCAL).pushes.size)
        assertEquals(1, SyncPlanner.plan(p, listOf(l), r, 200, ConflictPolicy.LOCAL).conflicts)
    }
    @Test fun identical_is_unchanged() {
        val l = entry("a", "Show", watched = 5, updated = 100, remote = ids)
        val plan = SyncPlanner.plan(p, listOf(l), listOf(remote("1", "Show", WatchStatus.WATCHING, 5, 100)), 200, ConflictPolicy.NEWEST)
        assertTrue(plan.isEmpty); assertEquals(1, plan.unchanged)
    }
    @Test fun unmatched_local_change_is_counted_not_sent() {
        val plan = SyncPlanner.plan(p, listOf(entry("z", "Other", updated = 900)), emptyList(), 200, ConflictPolicy.NEWEST)
        assertEquals(1, plan.skippedUnmatched); assertTrue(plan.pushes.isEmpty())
    }
    @Test fun progress_never_exceeds_total() {
        val l = entry("a", "Show", total = 12, remote = ids, updated = 1)
        val v = SyncPlanner.applyRemote(l, remote("1", "Show", WatchStatus.WATCHING, 99, 5), "anilist")
        assertEquals(12, v.watchedEpisodes)
    }
}

class ProgressRulesTest {
    @Test fun completes_on_last_episode() {
        val e = ProgressRules.apply(entry("a", total = 12, watched = 11), 12, true, 1000)
        assertEquals(WatchStatus.COMPLETED, e.status); assertNotNull(e.endDate); assertTrue(e.pendingSync)
    }
    @Test fun idempotent() {
        val once = ProgressRules.apply(entry("a", status = WatchStatus.PLAN_TO_WATCH), 3, true, 1000)
        val twice = ProgressRules.apply(once, 3, true, 1000)
        assertEquals(once, twice); assertEquals(WatchStatus.WATCHING, once.status)
    }
    @Test fun clamps() {
        assertEquals(0, ProgressRules.apply(entry("a"), -4, true, 1).watchedEpisodes)
        assertEquals(12, ProgressRules.apply(entry("a", total = 12), 40, false, 1).watchedEpisodes)
    }
    @Test fun unknown_total_never_completes() {
        assertEquals(WatchStatus.WATCHING, ProgressRules.apply(entry("a", total = 0, watched = 5), 50, true, 1).status)
    }
    @Test fun stepping_back_from_completed_reopens() {
        val done = ProgressRules.apply(entry("a", total = 12, watched = 11), 12, true, 1)
        val back = ProgressRules.apply(done, 11, true, 2)
        assertEquals(WatchStatus.WATCHING, back.status); assertNull(back.endDate)
    }
}

class LibraryFilterTest {
    private val all = listOf(
        entry("a", "Bleach", WatchStatus.COMPLETED, 12, 12, updated = 3, rating = 8f).copy(format = "TV", genres = listOf("Action")),
        entry("b", "Clannad", WatchStatus.WATCHING, 4, 12, updated = 2, rating = 9f).copy(format = "TV", genres = listOf("Drama")),
        entry("c", "Akira", WatchStatus.DROPPED, 1, 1, updated = 1).copy(format = "Movie")
    )
    @Test fun status_and_text() {
        assertEquals(listOf("b"), LibraryFilter.apply(all, LibraryQuery(status = WatchStatus.WATCHING)).map { it.id })
        assertEquals(listOf("c"), LibraryFilter.apply(all, LibraryQuery(text = "aki")).map { it.id })
    }
    @Test fun sorts() {
        assertEquals(listOf("c", "a", "b"), LibraryFilter.apply(all, LibraryQuery(sort = LibrarySort.TITLE)).map { it.id })
        assertEquals("b", LibraryFilter.apply(all, LibraryQuery(sort = LibrarySort.RATING)).first().id)
    }
    @Test fun facets_and_filters() {
        assertEquals(listOf("Movie", "TV"), LibraryFilter.facets(all).formats)
        assertEquals(listOf("a", "b"), LibraryFilter.apply(all, LibraryQuery(format = "tv", sort = LibrarySort.TITLE)).map { it.id })
        assertEquals(listOf("b"), LibraryFilter.apply(all, LibraryQuery(minRating = 9f)).map { it.id })
    }
}

class StatsTest {
    @Test fun computes() {
        val s = StatsCalculator.compute(listOf(
            entry("a", watched = 10, rating = 8f).copy(durationMinutes = 24, genres = listOf("Action")),
            entry("b", watched = 2, rating = 0f).copy(durationMinutes = 0)
        ))
        assertEquals(240L, s.watchMinutes); assertEquals(1, s.entriesMissingDuration)
        assertEquals(8f, s.averageRating!!, 0.01f); assertEquals(1, s.ratingHistogram[8])
    }
    @Test fun no_ratings_is_null() { assertNull(StatsCalculator.compute(listOf(entry("a"))).averageRating) }
}

class BackupCodecTest {
    init { BackupCodec.iterations = 1_000 }
    private val pw = "correct horse".toCharArray()
    private val payload = BackupPayload(anime = listOf(entry("a", "Show", watched = 3, updated = 5)), settings = mapOf("theme" to "light"))

    @Test fun round_trip() {
        val out = BackupCodec.decrypt(BackupCodec.encrypt(payload, pw), pw)
        assertEquals("Show", out.anime.single().title); assertEquals("light", out.settings!!["theme"]); assertNull(out.hvault)
    }
    @Test fun wrong_password() {
        try { BackupCodec.decrypt(BackupCodec.encrypt(payload, pw), "wrong password".toCharArray()); fail() }
        catch (e: BackupException) { assertTrue(e.message!!.contains("password")) }
    }
    @Test fun tamper_detected() {
        val b = BackupCodec.encrypt(payload, pw); b[b.size - 3] = (b[b.size - 3] + 1).toByte()
        try { BackupCodec.decrypt(b, pw); fail() } catch (_: BackupException) {}
    }
    @Test fun short_password_rejected() {
        try { BackupCodec.encrypt(payload, "short".toCharArray()); fail() } catch (_: BackupException) {}
    }
    @Test fun not_a_backup() {
        try { BackupCodec.decrypt("hello world, definitely not".toByteArray(), pw); fail() } catch (_: BackupException) {}
    }
    @Test fun preview_and_merge() {
        val local = listOf(entry("a", "Show", watched = 1, updated = 1), entry("only", "Local", updated = 1))
        val p = BackupPayload(anime = listOf(entry("a", "Show", watched = 3, updated = 5), entry("n", "New", updated = 2)))
        val pv = BackupPlanner.preview(p, local, null)
        assertEquals(1, pv.animeNew); assertEquals(1, pv.animeConflicts); assertEquals(1, pv.animeOnlyHere)
        assertEquals(setOf("a", "n"), BackupPlanner.mergeAnime(p, local).map { it.id }.toSet())
        val older = p.copy(anime = listOf(entry("a", "Show", watched = 0, updated = 0)))
        assertTrue(BackupPlanner.mergeAnime(older, local).isEmpty())
    }
}

class QuietHoursTest {
    @Test fun wraps_midnight() {
        assertTrue(QuietHours.isQuiet(23, 22, 8)); assertTrue(QuietHours.isQuiet(3, 22, 8)); assertFalse(QuietHours.isQuiet(12, 22, 8))
    }
    @Test fun same_day_and_disabled() {
        assertTrue(QuietHours.isQuiet(13, 12, 14)); assertFalse(QuietHours.isQuiet(14, 12, 14)); assertFalse(QuietHours.isQuiet(5, 7, 7))
    }
}
