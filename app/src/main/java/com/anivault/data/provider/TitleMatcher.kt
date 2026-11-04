package com.anivault.data.provider

import java.text.Normalizer

/** Title normalization used for de-duplication and for matching remote list entries to local ones. */
object TitleMatcher {

    private val combining = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val trailingFormat = Regex("\\s*\\((tv|movie|ova|ona|special)\\)$")

    fun normalize(title: String): String {
        val folded = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
        val noMarks = combining.replace(folded, "")
        val noFormat = trailingFormat.replace(noMarks.trim(), "")
        return nonAlnum.replace(noFormat, " ").trim()
    }

    /** "title|year" key; a missing year only matches another missing year. */
    fun key(title: String, year: Int?): String = normalize(title) + "|" + (year?.toString() ?: "")

    /** All normalized names an entry can be known by. */
    fun names(title: String, alt: List<String>): Set<String> =
        (listOf(title) + alt).map { normalize(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * Removes duplicates: first by (provider, id), then by normalized title + year. When two items
     * describe the same work the first one is kept (it came from the higher-priority provider).
     */
    fun dedupe(items: List<NormalizedAnime>): List<NormalizedAnime> {
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        val out = ArrayList<NormalizedAnime>(items.size)
        for (a in items) {
            if (!seenIds.add(a.canonicalId)) continue
            val k = key(a.title, a.year)
            if (normalize(a.title).isNotEmpty() && !seenKeys.add(k)) continue
            out += a
        }
        return out
    }
}
