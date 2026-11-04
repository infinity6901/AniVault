package com.anivault.data.local.model

/**
 * Tracking status. Persisted by [name], so values must never be renamed.
 * ON_HOLD was added in database v2 (spec §4 "Library & Tracking").
 */
enum class WatchStatus(val label: String) {
    WATCHING("Watching"),
    COMPLETED("Completed"),
    PLAN_TO_WATCH("Plan to Watch"),
    ON_HOLD("On Hold"),
    DROPPED("Dropped");

    companion object {
        /** Unknown stored/remote values degrade safely instead of crashing. */
        fun parse(value: String?): WatchStatus =
            values().firstOrNull { it.name == value } ?: PLAN_TO_WATCH
    }
}
