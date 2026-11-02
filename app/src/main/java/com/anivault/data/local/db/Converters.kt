package com.anivault.data.local.db

import androidx.room.TypeConverter
import com.anivault.data.local.model.WatchStatus

class Converters {

    // ── List<String> ↔ pipe-delimited String ──────────────────────────────
    // Triple-pipe separator avoids conflicts with commas inside genre/tag names.
    @TypeConverter
    fun fromStringList(list: List<String>): String =
        list.joinToString(separator = "|||")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList()
        else value.split("|||").map { it.trim() }.filter { it.isNotEmpty() }

    // ── WatchStatus ↔ String ───────────────────────────────────────────────
    @TypeConverter
    fun fromWatchStatus(status: WatchStatus): String = status.name

    @TypeConverter
    fun toWatchStatus(value: String): WatchStatus = WatchStatus.valueOf(value)
}
