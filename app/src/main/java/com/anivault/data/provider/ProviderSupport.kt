package com.anivault.data.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.ZoneOffset

// ── Safe JSON accessors (never throw on wrong types) ────────────────────────

internal fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

internal fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

internal fun JsonObject.dbl(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

internal fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonElement.asObjOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.asObjOrNull() }

internal fun JsonArray.strings(): List<String> =
    mapNotNull { e -> if (e.isJsonPrimitive) runCatching { e.asString }.getOrNull() else null }

object HttpErrors {
    /** Maps an HTTP status to a [ProviderException]; returns normally for 2xx with a body. */
    fun check(result: HttpResult, what: String) {
        val code = result.code
        if (code in 200..299) {
            if (result.body.isBlank()) throw ProviderException(FailureKind.MALFORMED, "$what returned an empty response", code)
            return
        }
        val retryAfter = result.headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1000L)
        throw when {
            code == 401 || code == 403 -> ProviderException(FailureKind.AUTH, "$what rejected the request (HTTP $code)", code)
            code == 429 -> ProviderException(FailureKind.RATE_LIMITED, "$what is rate limiting requests", code, retryAfter)
            code == 408 -> ProviderException(FailureKind.TIMEOUT, "$what timed out (HTTP 408)", code)
            code in 500..599 -> ProviderException(FailureKind.SERVER, "$what had a server error (HTTP $code)", code)
            else -> ProviderException(FailureKind.CLIENT, "$what rejected the request (HTTP $code)", code)
        }
    }

    fun parse(body: String, what: String): JsonElement =
        try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw ProviderException(FailureKind.MALFORMED, "$what returned malformed JSON")
        }
}

object HtmlText {
    private val br = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val tags = Regex("<[^>]*>")

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return tags.replace(br.replace(raw, "\n"), "")
            .replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
    }
}

object Dates {
    /** Parses "2024", "2024-04", "2024-04-07" or an ISO timestamp to UTC-midnight epoch millis. */
    fun parseToMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().take(10)
        val padded = when (t.length) {
            4 -> "$t-01-01"
            7 -> "$t-01"
            else -> t
        }
        return runCatching { LocalDate.parse(padded).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    fun fromParts(year: Int?, month: Int?, day: Int?): Long? {
        if (year == null || year <= 0) return null
        return runCatching {
            val ym = java.time.YearMonth.of(year, (month ?: 1).coerceIn(1, 12))
            ym.atDay((day ?: 1).coerceIn(1, ym.lengthOfMonth()))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** "yyyy-MM-dd" (UTC) for an epoch-millis date. */
    fun toIsoDate(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Year/month/day (UTC) for an epoch-millis date. */
    fun toParts(millis: Long): Triple<Int, Int, Int> {
        val d = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        return Triple(d.year, d.monthValue, d.dayOfMonth)
    }

    /** Parses ISO-8601 instants ("2024-05-01T10:00:00Z", with or without offset/fraction) to epoch millis. */
    fun parseInstant(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
            ?: parseToMillis(raw)
    }
}

object Seasons {
    data class Current(val name: String, val year: Int)

    /** AniList/MAL season names (WINTER, SPRING, SUMMER, FALL) for [date]. */
    fun current(date: LocalDate = LocalDate.now()): Current {
        val name = when (date.monthValue) {
            in 1..3 -> "WINTER"
            in 4..6 -> "SPRING"
            in 7..9 -> "SUMMER"
            else -> "FALL"
        }
        return Current(name, date.year)
    }
}

/** "TV_SHORT" → "TV Short", "MOVIE" → "Movie", "OVA" stays "OVA". */
fun prettyEnum(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    if (raw.length <= 3) return raw.uppercase()
    return raw.lowercase().split('_', ' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> if (w == "tv") "TV" else w.replaceFirstChar { it.uppercase() } }
}

internal fun firstInt(text: String?): Int? =
    text?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

internal fun distinctTitles(primary: String, vararg others: Collection<String?>): List<String> =
    others.flatMap { it }.mapNotNull { it?.trim() }.filter { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }
        .distinctBy { it.lowercase() }

/** Placeholder transport for adapters constructed only to reuse their parsing code. */
internal object NoTransport : HttpTransport {
    override suspend fun execute(request: HttpRequestSpec): HttpResult =
        throw IllegalStateException("This adapter instance is parse-only")
}
