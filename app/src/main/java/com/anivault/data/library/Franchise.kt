package com.anivault.data.library

import com.anivault.data.local.entity.AnimeEntry

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

    fun key(title: String): String {
        var t = title.trim()
        // Subtitles after a colon often name the season ("Show: Season 2"); strip repeatedly.
        var changed = true
        while (changed) {
            changed = false
            for (r in SUFFIXES) {
                val n = t.replace(r, "").trim()
                if (n != t && n.length >= 2) { t = n; changed = true }
            }
        }
        return t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }

    data class Group(val key: String, val members: List<AnimeEntry>) {
        val lead: AnimeEntry get() = members.first()
    }

    /** Keeps the incoming order; each group is placed where its first member appeared. */
    fun group(entries: List<AnimeEntry>): List<Group> {
        val map = LinkedHashMap<String, MutableList<AnimeEntry>>()
        entries.forEach { map.getOrPut(key(it.title)) { mutableListOf() }.add(it) }
        return map.map { (k, v) -> Group(k, v) }
    }
}
