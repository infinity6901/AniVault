package com.anivault.data.library

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus

/**
 * Groups seasons / parts / sequels of one series by title. This is a heuristic on the title text
 * (no network), so it groups "Show", "Show 2nd Season", "Show Season 3", "Show: Part 2" together but
 * leaves differently named spin-offs apart.
 */
object Franchise {
    private val SUFFIXES = listOf(
        Regex("""[\s:：\-–—]+(the\s+)?(final|last)\s+season$""", RegexOption.IGNORE_CASE),
        Regex("""[\s:：\-–—]+(season|part|cour|series)\s*\d+(\.\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""[\s:：\-–—]+\d+(st|nd|rd|th)\s+(season|part|cour)$""", RegexOption.IGNORE_CASE),
        Regex("""[\s:：\-–—]+(ii|iii|iv|v|vi|vii|viii|ix|x)$""", RegexOption.IGNORE_CASE),
        Regex("""\s+\d{1,2}$"""),
        Regex("""\s*\((tv|ova|ona|movie|special)\)$""", RegexOption.IGNORE_CASE)
    )

    /** Strips season/part suffixes while preserving original casing and punctuation for display. */
    fun cleanTitle(title: String): String {
        var t = title.trim()
        var changed = true
        while (changed) {
            changed = false
            for (r in SUFFIXES) {
                val n = t.replace(r, "").trim()
                if (n != t && n.length >= 2) {
                    t = n
                    changed = true
                }
            }
        }
        return t
    }

    fun key(title: String): String =
        cleanTitle(title).lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    data class Group(val key: String, val members: List<AnimeEntry>) {
        /** First member in the incoming sort order (kept for backwards compatibility with unit tests). */
        val lead: AnimeEntry get() = members.first()

        /** Chronologically ordered seasons/entries (by release year, then clean title/season). */
        val orderedMembers: List<AnimeEntry>
            get() = members.sortedWith(
                compareBy<AnimeEntry> { it.year ?: 9999 }
                    .thenBy { it.title.length }
                    .thenBy { it.title.lowercase() }
            )

        /**
         * Clean franchise display name: never shows "Show Season 3" or "Show 2" when representing a multi-season group.
         */
        val displayTitle: String
            get() {
                if (members.size <= 1) return lead.title
                val cleaned = members.map { cleanTitle(it.title) }.filter { it.isNotBlank() }
                return cleaned.minByOrNull { it.length } ?: cleanTitle(lead.title)
            }

        /**
         * Best representative entry for cover art and quick +1 actions:
         * prefers a currently WATCHING season, then the next uncompleted season in chronological order,
         * then the first season chronologically.
         */
        val representative: AnimeEntry
            get() {
                if (members.size <= 1) return lead
                val chronological = orderedMembers
                return chronological.firstOrNull { it.status == WatchStatus.WATCHING }
                    ?: chronological.firstOrNull { it.status != WatchStatus.COMPLETED }
                    ?: chronological.first()
            }

        val totalEpisodes: Int get() = members.sumOf { it.totalEpisodes.coerceAtLeast(0) }
        val watchedEpisodes: Int get() = members.sumOf { it.watchedEpisodes.coerceAtLeast(0) }
        val completedSeasons: Int get() = members.count { it.status == WatchStatus.COMPLETED }

        val overallStatus: WatchStatus
            get() = when {
                members.any { it.status == WatchStatus.WATCHING } -> WatchStatus.WATCHING
                members.all { it.status == WatchStatus.COMPLETED } -> WatchStatus.COMPLETED
                members.any { it.status == WatchStatus.COMPLETED || it.watchedEpisodes > 0 } -> WatchStatus.WATCHING
                members.any { it.status == WatchStatus.ON_HOLD } -> WatchStatus.ON_HOLD
                members.all { it.status == WatchStatus.DROPPED } -> WatchStatus.DROPPED
                else -> WatchStatus.PLAN_TO_WATCH
            }
    }

    /** Keeps the incoming order; each group is placed where its first member appeared. */
    fun group(entries: List<AnimeEntry>): List<Group> {
        val map = LinkedHashMap<String, MutableList<AnimeEntry>>()
        entries.forEach { map.getOrPut(key(it.title)) { mutableListOf() }.add(it) }
        return map.map { (k, v) -> Group(k, v) }
    }
}
