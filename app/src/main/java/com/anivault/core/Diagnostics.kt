package com.anivault.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticEntry(
    val time: Long,
    val source: String,
    val operation: String,
    val ok: Boolean,
    val durationMs: Long,
    /** Already sanitized – never contains tokens, query text or private titles. */
    val message: String?
)

/** Removes anything that looks like a credential or a full URL from text bound for UI/logs. */
object Sanitizer {
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val secretParam = Regex("(?i)(access_token|refresh_token|client_id|client_secret|token|code|key|api_key|password|secret)=([^&\\s]+)")
    private val url = Regex("https?://([^/\\s:?#\"')]+)[^\\s\"')]*")

    fun clean(message: String?, maxLen: Int = 160): String {
        if (message.isNullOrBlank()) return ""
        var s = message
        s = bearer.replace(s) { it.groupValues[1] + "***" }
        s = secretParam.replace(s) { it.groupValues[1] + "=***" }
        s = url.replace(s) { "https://" + it.groupValues[1] + "/…" }
        s = s.replace('\n', ' ').trim()
        return if (s.length > maxLen) s.take(maxLen - 1) + "…" else s
    }
}

/**
 * In-memory ring buffer of recent provider/sync results. Entries hold only sanitized text and
 * are lost when the process dies – deliberately nothing is written to disk or Logcat.
 */
@Singleton
class DiagnosticsLog @Inject constructor() {

    private val lock = Any()
    private val items = ArrayDeque<DiagnosticEntry>()
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    fun record(entry: DiagnosticEntry) {
        synchronized(lock) {
            items.addFirst(entry.copy(message = entry.message?.let { Sanitizer.clean(it) }))
            while (items.size > MAX) items.removeLast()
            _entries.value = items.toList()
        }
    }

    fun record(source: String, operation: String, ok: Boolean, durationMs: Long, message: String? = null) =
        record(DiagnosticEntry(System.currentTimeMillis(), source, operation, ok, durationMs, message))

    fun clear() = synchronized(lock) { items.clear(); _entries.value = emptyList() }

    /** Plain-text report for sharing. Contains no secrets, queries or private titles. */
    fun exportText(appVersion: String, buildType: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("AniVault diagnostics\nVersion: ").append(appVersion).append(" (").append(buildType).append(")\n\n")
        _entries.value.forEach { e ->
            sb.append(fmt.format(Date(e.time))).append("  ")
                .append(if (e.ok) "OK    " else "FAIL  ")
                .append(e.source).append(" · ").append(e.operation)
                .append(" · ").append(e.durationMs).append(" ms")
            if (!e.message.isNullOrBlank()) sb.append(" · ").append(e.message)
            sb.append('\n')
        }
        return sb.toString()
    }

    private companion object { const val MAX = 100 }
}
