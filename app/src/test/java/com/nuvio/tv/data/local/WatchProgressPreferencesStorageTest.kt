package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressPreferencesStorageTest {
    // Authors the LEGACY persisted shape: the old Gson writer emitted every
    // non-null field; encodeDefaults + omitted nulls reproduces it.
    private val legacyShapeJson = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `empty legacy storage initializes without writing bucket files`() = runTest {
        val harness = harness(emptyMap())

        val result = harness.preferences.getAllRawEntries()

        assertTrue(result.isEmpty())
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(1, harness.metadata.updateCount)
    }

    @Test
    fun `first save writes only the recent store`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(progress("item", lastWatched = 1L))

        assertEquals(setOf("item"), harness.recent.keys())
        assertTrue(harness.archive.keys().isEmpty())
        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `legacy snapshot migrates into recent and archive stores`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.getAllRawEntries()

        assertEquals(250, result.size)
        assertEquals(200, harness.recent.keys().size)
        assertEquals(50, harness.archive.keys().size)
        assertNull(harness.metadata.value[watchProgressEntriesKey])
        assertEquals(
            WATCH_PROGRESS_STORAGE_VERSION,
            harness.metadata.value[watchProgressStorageVersionKey]
        )
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
        assertEquals(1, harness.metadata.updateCount)
    }

    @Test
    fun `migration preserves a fifteen thousand item library`() = runTest {
        val entries = (0 until 15_000).associate { index ->
            "large$index" to progress("large$index", lastWatched = index.toLong())
        }
        val harness = harness(entries)

        val result = harness.preferences.getAllRawEntries()

        assertEquals(15_000, result.size)
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(14_800, harness.archive.keys().size)
        assertTrue("large14999" in harness.recent.keys())
        assertTrue("large0" in harness.archive.keys())
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("large14999").copy(position = 5_000L, lastWatched = 20_000L)
        )

        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `migration is idempotent after storage version is recorded`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val second = WatchProgressPreferences(harness.factory, harness.profileManager)

        val result = second.getAllRawEntries()

        assertEquals(250, result.size)
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
    }

    @Test
    fun `saving an existing recent item writes only the recent store`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("item249").copy(position = 5_000L, lastWatched = 1_000L)
        )

        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
        assertEquals(5_000L, harness.preferences.getAllRawEntries().getValue("item249").position)
    }

    @Test
    fun `saving an archived item promotes it and evicts the oldest recent item`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L)
        )

        val recentKeys = harness.recent.keys()
        val archiveKeys = harness.archive.keys()
        assertEquals(200, recentKeys.size)
        assertEquals(50, archiveKeys.size)
        assertTrue("item0" in recentKeys)
        assertTrue("item50" in archiveKeys)
        assertFalse("item0" in archiveKeys)
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `removing an archived item does not rewrite the recent store`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.removeProgress("item0")

        assertEquals(0, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
        assertFalse("item0" in harness.preferences.getAllRawEntries())
    }

    @Test
    fun `batch saves preserve the recent limit and merged reads`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val updates = (0 until 5).map { index ->
            entries.getValue("item$index").copy(lastWatched = 2_000L + index)
        }

        harness.preferences.saveProgressBatch(updates)

        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(250, harness.preferences.getAllRawEntries().size)
        updates.forEach { update ->
            assertTrue(update.contentId in harness.recent.keys())
        }
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `remote merge rebalances new entries across both buckets`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to remote),
            removeMissingRemoteEntries = false
        )

        val result = harness.preferences.getAllRawEntries()
        assertEquals(251, result.size)
        assertEquals(remote, result["remote"])
        assertTrue("remote" in harness.recent.keys())
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(51, harness.archive.keys().size)
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `remote merge removes only missing entries already pushed`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("pushed", lastWatched = 100L))
        harness.preferences.saveProgress(progress("unpushed", lastWatched = 300L))
        val remote = progress("remote", lastWatched = 150L)

        val preserved = harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to remote),
            lastSuccessfulPushMs = 200L,
            removeMissingRemoteEntries = true
        )

        val result = harness.preferences.getAllRawEntries()
        assertTrue(preserved)
        assertFalse("pushed" in result)
        assertEquals(300L, result.getValue("unpushed").lastWatched)
        assertEquals(remote, result["remote"])
        assertEquals(2, result.size)
    }

    @Test
    fun `hydrated artwork merge keeps a position saved after the snapshot`() = runTest {
        val harness = harness(emptyMap())
        val snapshot = progress("item", lastWatched = 1L)
        harness.preferences.saveProgress(snapshot)
        // Playback saves a newer position while the artwork fetch is in flight.
        harness.preferences.saveProgress(snapshot.copy(position = 5_000L, lastWatched = 2L))

        val merged = harness.preferences.mergeHydratedArtwork(
            snapshot = snapshot,
            poster = "poster",
            backdrop = "backdrop",
            logo = "logo",
            name = "Hydrated Name",
            durationMs = 0L
        )

        val stored = harness.preferences.getAllRawEntries().getValue("item")
        assertTrue(merged)
        assertEquals(5_000L, stored.position)
        assertEquals(2L, stored.lastWatched)
        assertEquals(10_000L, stored.duration)
        assertEquals("poster", stored.poster)
        assertEquals("backdrop", stored.backdrop)
        assertEquals("logo", stored.logo)
        assertEquals("Hydrated Name", stored.name)
    }

    @Test
    fun `hydrated artwork merge keeps artwork already on the stored entry`() = runTest {
        val harness = harness(emptyMap())
        val snapshot = progress("item", lastWatched = 1L)
        harness.preferences.saveProgress(snapshot.copy(poster = "own-poster", lastWatched = 2L))

        harness.preferences.mergeHydratedArtwork(
            snapshot = snapshot,
            poster = "hydrated-poster",
            backdrop = "backdrop",
            logo = null,
            name = null,
            durationMs = 0L
        )

        val stored = harness.preferences.getAllRawEntries().getValue("item")
        assertEquals("own-poster", stored.poster)
        assertEquals("backdrop", stored.backdrop)
    }

    @Test
    fun `hydrated artwork merge does not resurrect a removed entry`() = runTest {
        val harness = harness(emptyMap())
        val snapshot = progress("item", lastWatched = 1L)
        harness.preferences.saveProgress(snapshot)
        harness.preferences.removeProgress("item")

        val merged = harness.preferences.mergeHydratedArtwork(
            snapshot = snapshot,
            poster = "poster",
            backdrop = null,
            logo = null,
            name = null,
            durationMs = 0L
        )

        assertFalse(merged)
        assertFalse("item" in harness.preferences.getAllRawEntries())
    }

    @Test
    fun `hydrated artwork merge updates an archived entry in place`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        assertTrue("item0" in harness.archive.keys())
        harness.resetUpdateCounts()

        val merged = harness.preferences.mergeHydratedArtwork(
            snapshot = entries.getValue("item0"),
            poster = "poster",
            backdrop = "backdrop",
            logo = "logo",
            name = "Hydrated Name",
            durationMs = 0L
        )

        val result = harness.preferences.getAllRawEntries()
        val stored = result.getValue("item0")
        assertTrue(merged)
        assertEquals("poster", stored.poster)
        assertEquals("backdrop", stored.backdrop)
        assertEquals("logo", stored.logo)
        assertEquals("Hydrated Name", stored.name)
        assertEquals(1_000L, stored.position)
        assertEquals(0L, stored.lastWatched)
        assertTrue("item0" in harness.archive.keys())
        assertFalse("item0" in harness.recent.keys())
        assertEquals(1, harness.archive.updateCount)
        assertEquals(0, harness.recent.updateCount)
        assertEquals(250, result.size)
    }

    @Test
    fun `hydrated artwork merge fills only a missing duration`() = runTest {
        val harness = harness(emptyMap())
        val unknown = progress("unknown", lastWatched = 1L).copy(duration = 0L)
        val known = progress("known", lastWatched = 2L)
        harness.preferences.saveProgress(unknown)
        harness.preferences.saveProgress(known)

        val filled = harness.preferences.mergeHydratedArtwork(
            snapshot = unknown,
            poster = null,
            backdrop = null,
            logo = null,
            name = null,
            durationMs = 7_200_000L
        )
        // Stored duration is non-zero, so the hydrated one is ignored and nothing changes.
        val overwritten = harness.preferences.mergeHydratedArtwork(
            snapshot = known,
            poster = null,
            backdrop = null,
            logo = null,
            name = null,
            durationMs = 7_200_000L
        )

        val result = harness.preferences.getAllRawEntries()
        assertTrue(filled)
        assertEquals(7_200_000L, result.getValue("unknown").duration)
        assertFalse(overwritten)
        assertEquals(10_000L, result.getValue("known").duration)
    }

    @Test
    fun `remote delta deletes archived entries and promotes new progress`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.applyRemoteChanges(
            upserts = mapOf("remote" to remote),
            deletes = listOf("item0")
        )

        val result = harness.preferences.getAllRawEntries()
        assertEquals(250, result.size)
        assertFalse("item0" in result)
        assertEquals(remote, result["remote"])
        assertTrue("remote" in harness.recent.keys())
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
    }

    @Test
    fun `empty remote replacement preserves local progress`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.replaceWithRemoteEntries(emptyMap())

        assertEquals(250, harness.preferences.getAllRawEntries().size)
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `progress flow merges both stores after migration`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.allProgress.first()

        assertEquals(250, result.size)
        assertEquals("item249", result.first().contentId)
        assertEquals("item0", result.last().contentId)
    }

    @Test
    fun `progress flow does not expose an intermediate bucket write`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.allProgress.first()
        val initialEmission = CompletableDeferred<Unit>()
        val nextEmission = async {
            var initialSeen = false
            harness.preferences.allProgress.first {
                if (!initialSeen) {
                    initialSeen = true
                    initialEmission.complete(Unit)
                    false
                } else {
                    true
                }
            }
        }
        initialEmission.await()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L)
        )
        val result = nextEmission.await()

        assertEquals(250, result.size)
        assertEquals(5_000L, result.first { it.contentId == "item0" }.position)
    }

    @Test
    fun `read modify write preserves playback and tracking ids`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.getAllRawEntries()
        val tracked = progress("tracked", lastWatched = 1L).copy(
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
            traktPlaybackId = 11L,
            traktMovieId = 12,
            traktShowId = 13,
            traktEpisodeId = 14,
            simklPlaybackId = 21L,
            mdbListPlaybackId = 22L,
            trackingProviderId = "simkl",
            trackingProviderItemId = "simkl:33",
            trackingSourceUrl = "https://simkl.com/tv/33"
        )
        // A sibling entry whose ids are never re-supplied by an incoming save:
        // every saveProgress re-reads the whole bucket through the lenient
        // per-entry reader and rewrites it, which is how a dropping reader
        // erased attribution on entries nobody was updating.
        val sibling = tracked.copy(
            contentId = "sibling",
            videoId = "sibling",
            simklPlaybackId = 31L,
            trackingProviderId = "mdblist",
            trackingProviderItemId = "mdblist:44",
            trackingSourceUrl = "https://mdblist.com/44",
            lastWatched = 2L
        )

        harness.preferences.saveProgress(tracked)
        harness.preferences.saveProgress(sibling)
        harness.preferences.saveProgress(tracked.copy(position = 5_000L, lastWatched = 3L))

        val stored = harness.preferences.getAllRawEntries()
        val updated = stored.getValue("tracked")
        assertEquals(11L, updated.traktPlaybackId)
        assertEquals(12, updated.traktMovieId)
        assertEquals(13, updated.traktShowId)
        assertEquals(14, updated.traktEpisodeId)
        assertEquals(21L, updated.simklPlaybackId)
        assertEquals(22L, updated.mdbListPlaybackId)
        assertEquals("simkl", updated.trackingProviderId)
        assertEquals("simkl:33", updated.trackingProviderItemId)
        assertEquals("https://simkl.com/tv/33", updated.trackingSourceUrl)
        assertEquals(5_000L, updated.position)
        // The untouched sibling keeps its attribution through the rewrite.
        val bystander = stored.getValue("sibling")
        assertEquals(31L, bystander.simklPlaybackId)
        assertEquals("mdblist", bystander.trackingProviderId)
        assertEquals("mdblist:44", bystander.trackingProviderItemId)
        assertEquals("https://mdblist.com/44", bystander.trackingSourceUrl)
    }

    @Test
    fun `cw metadata merge keeps a position saved after the snapshot`() = runTest {
        val harness = harness(emptyMap())
        val snapshot = progress("item", lastWatched = 1L)
        harness.preferences.saveProgress(snapshot)
        // The player saves a newer position while CW enrichment is in flight.
        harness.preferences.saveProgress(snapshot.copy(position = 5_000L, lastWatched = 2L))

        val updated = harness.preferences.mergeContinueWatchingMetadata(
            listOf(
                snapshot.copy(
                    name = "Enriched Name",
                    poster = "poster",
                    backdrop = "backdrop",
                    logo = "logo",
                    episodeTitle = "Episode"
                )
            )
        )

        val stored = harness.preferences.getAllRawEntries().getValue("item")
        assertEquals(1, updated)
        assertEquals(5_000L, stored.position)
        assertEquals(2L, stored.lastWatched)
        assertEquals(10_000L, stored.duration)
        assertEquals("Enriched Name", stored.name)
        assertEquals("poster", stored.poster)
        assertEquals("backdrop", stored.backdrop)
        assertEquals("logo", stored.logo)
        assertEquals("Episode", stored.episodeTitle)
    }

    @Test
    fun `cw metadata merge keeps stored values the enrichment lacks`() = runTest {
        val harness = harness(emptyMap())
        val snapshot = progress("item", lastWatched = 1L)
        harness.preferences.saveProgress(snapshot.copy(name = "Stored Name", poster = "own-poster"))

        harness.preferences.mergeContinueWatchingMetadata(
            listOf(snapshot.copy(name = " ", poster = null, backdrop = "backdrop"))
        )

        val stored = harness.preferences.getAllRawEntries().getValue("item")
        assertEquals("Stored Name", stored.name)
        assertEquals("own-poster", stored.poster)
        assertEquals("backdrop", stored.backdrop)
    }

    @Test
    fun `cw metadata merge does not resurrect a removed entry`() = runTest {
        val harness = harness(emptyMap())
        val snapshot = progress("item", lastWatched = 1L)
        harness.preferences.saveProgress(snapshot)
        harness.preferences.removeProgress("item")

        val updated = harness.preferences.mergeContinueWatchingMetadata(
            listOf(snapshot.copy(poster = "poster"))
        )

        assertEquals(0, updated)
        assertFalse("item" in harness.preferences.getAllRawEntries())
    }

    @Test
    fun `cw metadata merge updates an archived entry in place`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        assertTrue("item0" in harness.archive.keys())
        harness.resetUpdateCounts()

        harness.preferences.mergeContinueWatchingMetadata(
            listOf(entries.getValue("item0").copy(poster = "poster"))
        )

        val stored = harness.preferences.getAllRawEntries().getValue("item0")
        assertEquals("poster", stored.poster)
        assertTrue("item0" in harness.archive.keys())
        assertFalse("item0" in harness.recent.keys())
        assertEquals(1, harness.archive.updateCount)
        assertEquals(0, harness.recent.updateCount)
    }

    private fun harness(entries: Map<String, WatchProgress>): Harness {
        val metadata = CountingPreferencesDataStore(preferences(entries = entries))
        val recent = CountingPreferencesDataStore()
        val archive = CountingPreferencesDataStore()
        val stores = mapOf(
            WATCH_PROGRESS_METADATA_FEATURE to metadata,
            WATCH_PROGRESS_RECENT_FEATURE to recent,
            WATCH_PROGRESS_ARCHIVE_FEATURE to archive
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getValue(secondArg<String>())
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(
            preferences = WatchProgressPreferences(factory, profileManager),
            factory = factory,
            profileManager = profileManager,
            metadata = metadata,
            recent = recent,
            archive = archive
        )
    }

    private fun legacyEntries(): Map<String, WatchProgress> {
        return (0 until 250).associate { index ->
            "item$index" to progress("item$index", lastWatched = index.toLong())
        }
    }

    private class CountingPreferencesDataStore(
        initial: Preferences = androidx.datastore.preferences.core.emptyPreferences()
    ) : TestPreferencesDataStore(initial) {
        var updateCount: Int = 0
            private set

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = super.updateData(transform)
            updateCount += 1
            return next
        }

        fun resetUpdateCount() {
            updateCount = 0
        }
    }

    private fun progress(contentId: String, lastWatched: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = lastWatched
    )

    private fun preferences(entries: Map<String, WatchProgress>): Preferences {
        return emptyPreferences().toMutablePreferences().apply {
            this[watchProgressEntriesKey] = legacyShapeJson.encodeToString(
                MapSerializer(String.serializer(), WatchProgress.serializer()),
                entries
            )
        }.toPreferences()
    }

    private fun TestPreferencesDataStore.keys(): Set<String> {
        val json = value[watchProgressEntriesKey] ?: "{}"
        return legacyShapeJson.parseToJsonElement(json).jsonObject.keys
    }

    private data class Harness(
        val preferences: WatchProgressPreferences,
        val factory: ProfileDataStoreFactory,
        val profileManager: ProfileManager,
        val metadata: CountingPreferencesDataStore,
        val recent: CountingPreferencesDataStore,
        val archive: CountingPreferencesDataStore
    ) {
        fun resetUpdateCounts() {
            metadata.resetUpdateCount()
            recent.resetUpdateCount()
            archive.resetUpdateCount()
        }
    }

}
