package com.anivault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anivault.data.local.model.WatchStatus

/**
 * A user-owned library entry. Every parameter has a default so Gson (backup/import) can
 * construct it even when an older export lacks newer fields.
 *
 * [rating] is the user's PERSONAL score (0–10, 0 = unrated). The provider's community score is
 * kept separately in [providerScore] so statistics never mix the two.
 */
@Entity(tableName = "anime_entries")
data class AnimeEntry(
    @PrimaryKey val id: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val totalEpisodes: Int = 0,
    val watchedEpisodes: Int = 0,
    val status: WatchStatus = WatchStatus.PLAN_TO_WATCH,
    val rating: Float = 0f,
    val year: Int? = null,
    val season: String = "",
    val genres: List<String> = emptyList(),
    val synopsis: String = "",
    val notes: String = "",
    val durationMinutes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),

    // ── v2 ────────────────────────────────────────────────────────────────
    @ColumnInfo(defaultValue = "''") val bannerUrl: String = "",
    @ColumnInfo(defaultValue = "''") val altTitles: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "''") val format: String = "",
    @ColumnInfo(defaultValue = "''") val airingStatus: String = "",
    /** Provider attribution for the catalog metadata (e.g. "AniList"). */
    @ColumnInfo(defaultValue = "''") val sourceName: String = "",
    /** JSON map of remote ids, see [RemoteIds]. */
    @ColumnInfo(defaultValue = "''") val remoteIds: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0L,
    /** True when a local change has not yet been pushed to a connected account. */
    @ColumnInfo(defaultValue = "0") val pendingSync: Boolean = false,
    @ColumnInfo(defaultValue = "0") val remoteUpdatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val providerScore: Float = 0f
)

/** Remote ids per service: keys "anilist", "mal", "kitsu", "kitsuEntry". Uses org.json (no reflection, R8-safe). */
object RemoteIds {
    fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = org.json.JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            o.keys().forEach { k -> out[k] = o.optString(k, "") }
            out as Map<String, String>
        }.getOrDefault(emptyMap())
    }

    fun encode(map: Map<String, String>): String {
        if (map.isEmpty()) return ""
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun merge(raw: String, extra: Map<String, String>): String =
        encode(parse(raw) + extra.filterValues { it.isNotBlank() })
}
