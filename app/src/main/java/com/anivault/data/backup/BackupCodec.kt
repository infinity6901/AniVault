package com.anivault.data.backup

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.google.gson.Gson
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/** Everything a backup can hold. [hvault] is null unless the user chose to include it. */
data class BackupPayload(
    val version: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val anime: List<AnimeEntry> = emptyList(),
    val hvault: List<HVaultEntry>? = null,
    /** Non-secret preferences only (see AppPrefs.BACKUP_KEYS). Values are strings/booleans/numbers. */
    val settings: Map<String, String>? = null,
    /** Sign-in tokens and client IDs. Only present when the user chose to include them; the file is password-encrypted. */
    val secrets: Map<String, String>? = null,
    /** True when the library was left out on purpose (default false so older files read as "included"). */
    val libraryExcluded: Boolean = false
)

/**
 * File format: MAGIC | salt(16) | nonce(12) | AES-256-GCM(JSON). Key = PBKDF2-HMAC-SHA256(password, salt).
 * The magic is authenticated as AAD. Version 1 files (previous AniVault builds) can still be read.
 * No Android classes here, so it is unit-testable on the JVM.
 */
object BackupCodec {
    private val gson = Gson()
    private val magicV2 = "ANIVAULT-ENC-2".toByteArray(Charsets.UTF_8)
    private val magicV1 = "ANIVAULT-ENC-1".toByteArray(Charsets.UTF_8)
    private const val SALT = 16
    private const val NONCE = 12
    const val MIN_PASSWORD = 8
    private const val MAX_ID_LEN = 256
    private const val MAX_TITLE_LEN = 512
    private const val MAX_URL_LEN = 2048
    private const val MAX_NOTES_LEN = 16_384
    private const val MAX_LIST_ITEMS = 64
    private const val MAX_ENTRIES = 50_000
    /** PBKDF2 work factor. Fixed for the format; only tests lower it. */
    internal var iterations = 210_000

    fun encrypt(payload: BackupPayload, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        if (password.size < MIN_PASSWORD) throw BackupException("Use a backup password with at least $MIN_PASSWORD characters.")
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magicV2)
        val body = cipher.doFinal(gson.toJson(payload).toByteArray(Charsets.UTF_8))
        return magicV2 + salt + nonce + body
    }

    fun decrypt(bytes: ByteArray, password: CharArray): BackupPayload {
        val magic = when {
            startsWith(bytes, magicV2) -> magicV2
            startsWith(bytes, magicV1) -> magicV1
            else -> throw BackupException("This is not a supported AniVault encrypted backup.")
        }
        val header = magic.size + SALT + NONCE
        if (bytes.size <= header) throw BackupException("This backup file is incomplete.")
        val salt = bytes.copyOfRange(magic.size, magic.size + SALT)
        val nonce = bytes.copyOfRange(magic.size + SALT, header)
        val clear = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magic)
            cipher.doFinal(bytes.copyOfRange(header, bytes.size))
        } catch (_: AEADBadTagException) {
            throw BackupException("Incorrect password or damaged backup file.")
        }
        val payload = try {
            gson.fromJson(String(clear, Charsets.UTF_8), BackupPayload::class.java)
        } catch (e: Exception) {
            throw BackupException("The backup could not be read.")
        } ?: throw BackupException("The backup is empty.")
        if (payload.version > 2) throw BackupException("This backup was made by a newer AniVault. Update the app first.")
        // Gson bypasses Kotlin null-safety, so sanitize and bound every deserialized entity and collection.
        val rows: List<AnimeEntry>? = payload.anime
        val vaultRows: List<HVaultEntry>? = payload.hvault
        return payload.copy(
            anime = (rows ?: emptyList()).take(MAX_ENTRIES).mapNotNull(::sanitizeAnime).distinctBy { it.id },
            hvault = vaultRows?.take(MAX_ENTRIES)?.mapNotNull(::sanitizeVault)?.distinctBy { it.id }
        )
    }

    @Suppress("UselessCallOnNotNull")
    private fun sanitizeAnime(e: AnimeEntry?): AnimeEntry? {
        if (e == null || e.id.isNullOrBlank() || e.title.isNullOrBlank()) return null
        val total = e.totalEpisodes.coerceIn(0, 50_000)
        val watched = if (total > 0) e.watchedEpisodes.coerceIn(0, total) else e.watchedEpisodes.coerceIn(0, 50_000)
        return e.copy(
            id = e.id.trim().take(MAX_ID_LEN),
            title = e.title.trim().take(MAX_TITLE_LEN),
            imageUrl = e.imageUrl.orEmpty().trim().take(MAX_URL_LEN),
            totalEpisodes = total,
            watchedEpisodes = watched,
            status = (e.status as WatchStatus?) ?: WatchStatus.PLAN_TO_WATCH,
            rating = if (e.rating.isNaN()) 0f else e.rating.coerceIn(0f, 10f),
            season = e.season.orEmpty().take(32),
            genres = (e.genres as List<String?>?)?.mapNotNull { it?.trim()?.take(64)?.takeIf(String::isNotEmpty) }?.take(MAX_LIST_ITEMS) ?: emptyList(),
            synopsis = e.synopsis.orEmpty().take(MAX_NOTES_LEN),
            notes = e.notes.orEmpty().take(MAX_NOTES_LEN),
            durationMinutes = e.durationMinutes.coerceIn(0, 1_440),
            bannerUrl = e.bannerUrl.orEmpty().trim().take(MAX_URL_LEN),
            altTitles = (e.altTitles as List<String?>?)?.mapNotNull { it?.trim()?.take(MAX_TITLE_LEN)?.takeIf(String::isNotEmpty) }?.take(MAX_LIST_ITEMS) ?: emptyList(),
            format = e.format.orEmpty().take(32),
            airingStatus = e.airingStatus.orEmpty().take(64),
            sourceName = e.sourceName.orEmpty().take(64),
            remoteIds = e.remoteIds.orEmpty().take(1024),
            providerScore = if (e.providerScore.isNaN()) 0f else e.providerScore.coerceIn(0f, 10f),
            tags = (e.tags as List<String?>?)?.mapNotNull { it?.trim()?.take(32)?.takeIf(String::isNotEmpty) }?.take(MAX_LIST_ITEMS) ?: emptyList(),
            rewatchCount = e.rewatchCount.coerceIn(0, 1_000),
            lastWatchedAt = e.lastWatchedAt.coerceAtLeast(0L)
        )
    }

    @Suppress("UselessCallOnNotNull")
    private fun sanitizeVault(v: HVaultEntry?): HVaultEntry? {
        if (v == null || v.id.isNullOrBlank() || v.title.isNullOrBlank()) return null
        val total = v.totalEpisodes.coerceIn(0, 50_000)
        val watched = if (total > 0) v.watchedEpisodes.coerceIn(0, total) else v.watchedEpisodes.coerceIn(0, 50_000)
        return v.copy(
            id = v.id.trim().take(MAX_ID_LEN),
            title = v.title.trim().take(MAX_TITLE_LEN),
            imageUrl = v.imageUrl.orEmpty().trim().take(MAX_URL_LEN),
            totalEpisodes = total,
            watchedEpisodes = watched,
            status = (v.status as WatchStatus?) ?: WatchStatus.PLAN_TO_WATCH,
            rating = if (v.rating.isNaN()) 0f else v.rating.coerceIn(0f, 10f),
            studio = v.studio?.trim()?.take(128),
            tags = (v.tags as List<String?>?)?.mapNotNull { it?.trim()?.take(32)?.takeIf(String::isNotEmpty) }?.take(MAX_LIST_ITEMS) ?: emptyList(),
            notes = v.notes.orEmpty().take(MAX_NOTES_LEN),
            durationMinutes = v.durationMinutes.coerceIn(0, 1_440)
        )
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}

/** What restoring a payload would do to the current library (shown before anything changes). */
data class BackupPreview(
    val createdAt: Long,
    val animeInBackup: Int,
    val animeNew: Int,
    /** Same id on both sides but different content. Merge keeps the newer edit. */
    val animeConflicts: Int,
    val animeIdentical: Int,
    val hvaultInBackup: Int?,
    val hvaultNew: Int,
    val hvaultConflicts: Int,
    val hasSettings: Boolean,
    /** Entries that only exist on this device; deleted by Replace, kept by Merge. */
    val animeOnlyHere: Int,
    val hasSecrets: Boolean = false,
    val hasLibrary: Boolean = true
)

enum class RestoreMode { MERGE, REPLACE }

object BackupPlanner {
    fun preview(p: BackupPayload, localAnime: List<AnimeEntry>, localVault: List<HVaultEntry>?): BackupPreview {
        val la = localAnime.associateBy { it.id }
        var n = 0; var c = 0; var same = 0
        p.anime.forEach { b ->
            val l = la[b.id]
            when {
                l == null -> n++
                l == b -> same++
                else -> c++
            }
        }
        val bIds = p.anime.map { it.id }.toSet()
        var hn = 0; var hc = 0
        p.hvault?.let { hv ->
            val lv = (localVault ?: emptyList()).associateBy { it.id }
            hv.forEach { b -> val l = lv[b.id]; if (l == null) hn++ else if (l != b) hc++ }
        }
        return BackupPreview(
            createdAt = p.createdAt, animeInBackup = p.anime.size, animeNew = n, animeConflicts = c, animeIdentical = same,
            hvaultInBackup = p.hvault?.size, hvaultNew = hn, hvaultConflicts = hc, hasSettings = !p.settings.isNullOrEmpty(),
            animeOnlyHere = localAnime.count { it.id !in bIds },
            hasSecrets = !p.secrets.isNullOrEmpty(),
            hasLibrary = !p.libraryExcluded
        )
    }

    /** Rows to write for a MERGE: new ones plus conflicts where the backup copy is newer. */
    fun mergeAnime(p: BackupPayload, local: List<AnimeEntry>): List<AnimeEntry> {
        val la = local.associateBy { it.id }
        return p.anime.filter { b -> val l = la[b.id]; l == null || b.updatedAt > l.updatedAt }
    }

    fun mergeVault(p: BackupPayload, local: List<HVaultEntry>): List<HVaultEntry> {
        val lv = local.associateBy { it.id }
        return (p.hvault ?: emptyList()).filter { b -> val l = lv[b.id]; l == null || b.updatedAt > l.updatedAt }
    }
}
