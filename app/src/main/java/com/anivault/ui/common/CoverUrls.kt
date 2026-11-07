package com.anivault.ui.common

/**
 * Asks each image CDN for the larger variant of a cover when the stored URL points at a thumbnail.
 * Only pattern swaps on known hosts; anything unknown comes back unchanged. If the larger image fails to load,
 * [Poster] falls back to the original URL.
 */
object CoverUrls {
    fun upgrade(url: String): String {
        if (url.isBlank()) return url
        return when {
            // AniList: .../cover/medium/... or /large/ → /extraLarge/
            url.contains("anilistcdn/media") || url.contains("anili.st") ->
                url.replace("/cover/medium/", "/cover/extraLarge/").replace("/cover/large/", "/cover/extraLarge/")
            // MyAnimeList / Jikan: ".../1234t.jpg" (thumbnail) or "l.jpg" is already large → use "l"
            url.contains("cdn.myanimelist.net/images/anime/") ->
                url.replace(Regex("(\\d+)(t|m)\\.(jpg|webp)(\\?.*)?$"), "$1l.$3$4")
            // Kitsu: tiny/small/medium → large (original can be huge)
            url.contains("media.kitsu") || url.contains("kitsu.io") ->
                url.replace("/tiny/", "/large/").replace("/small/", "/large/").replace("/medium/", "/large/")
            else -> url
        }
    }
}
