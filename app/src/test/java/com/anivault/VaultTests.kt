package com.anivault

import com.anivault.data.library.VaultMapping
import com.anivault.data.library.VaultRules
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTests {
    private fun v(total: Int, watched: Int, status: WatchStatus = WatchStatus.WATCHING) = HVaultEntry(
        id = "x", title = "t", imageUrl = "", totalEpisodes = total, watchedEpisodes = watched, status = status,
        rating = 0f, studio = null, isCensored = false, tags = emptyList(), notes = "", durationMinutes = 0
    )

    @Test fun reachingLastEpisodeCompletes() {
        val e = VaultRules.withProgress(v(6, 2), 6)
        assertEquals(WatchStatus.COMPLETED, e.status); assertEquals(6, e.watchedEpisodes)
    }

    @Test fun completedFillsProgress() {
        assertEquals(6, VaultRules.withStatus(v(6, 2), WatchStatus.COMPLETED).watchedEpisodes)
    }

    @Test fun normalizeFixesFullButNotCompleted() {
        assertEquals(WatchStatus.COMPLETED, VaultRules.normalize(v(6, 6)).status)
        assertEquals(6, VaultRules.normalize(v(6, 3, WatchStatus.COMPLETED)).watchedEpisodes)
    }

    @Test fun steppingBackFromCompletedResumesWatching() {
        assertEquals(WatchStatus.WATCHING, VaultRules.withProgress(v(6, 6, WatchStatus.COMPLETED), 5).status)
    }

    @Test fun moveKeepsAccountLinkSoSyncDoesNotReimport() {
        val e = AnimeEntry(id = "anilist:99", title = "A", remoteIds = RemoteIds.encode(mapOf("anilist" to "99")), watchedEpisodes = 3, totalEpisodes = 12)
        val vault = VaultMapping.toVault(e, 1L)
        assertEquals("sync:anilist:99", vault.id)
        val back = VaultMapping.toLibrary(vault, 2L)
        assertEquals("anilist:99", back.id)
        assertEquals("99", RemoteIds.parse(back.remoteIds)["anilist"])
        assertEquals(3, back.watchedEpisodes)
    }

    @Test fun unlinkedEntryRoundTrips() {
        val e = AnimeEntry(id = "custom:1", title = "B")
        val vault = VaultMapping.toVault(e, 1L)
        assertTrue(vault.id.startsWith("moved:"))
        assertEquals("custom:1", VaultMapping.toLibrary(vault, 2L).id)
    }
}

class VaultOwnershipTests {
    @Test fun keysCoverEveryLinkedService() {
        val k = com.anivault.data.sync.VaultOwnership.keysOf(RemoteIds.encode(mapOf("anilist" to "1", "mal" to "2", "kitsu" to "3", "kitsuEntry" to "9")))
        assertEquals(setOf("anilist:1", "mal:2", "kitsu:3"), k)
    }

    @Test fun markIsAddedAndRemovedWithoutTouchingUserNotes() {
        val o = com.anivault.data.sync.VaultOwnership
        val marked = o.withMark("great show", true)
        assertTrue(o.hasMark(marked)); assertTrue(marked.startsWith("great show"))
        assertEquals("great show", o.withMark(marked, false))
        assertEquals(o.withMark("", true), com.anivault.data.sync.VAULT_MARK)
    }

    @Test fun vaultIdKeys() {
        assertEquals(setOf("mal:55"), com.anivault.data.sync.VaultOwnership.keysOfVaultId("sync:mal:55"))
        assertTrue(com.anivault.data.sync.VaultOwnership.keysOfVaultId("moved:x").isEmpty())
    }
}
