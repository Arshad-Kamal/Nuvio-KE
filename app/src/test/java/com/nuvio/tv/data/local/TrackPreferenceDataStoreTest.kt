package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1a pins for the BoundedPrefs store adapters in this file: '|'-key entry
 * grouping, type-sensitive group removal on eviction (string track fields and
 * the int delay key share a group), and the all-null save leaving no ts
 * sidecar behind (the 98fed26eb review-gate behavior). The eviction POLICY is
 * pinned separately in BoundedPrefsTest; these tests pin the plumbing.
 */
class TrackPreferenceDataStoreTest {

    @Test
    fun `save and load round-trips tracks and per-video delay`() = runTest {
        val harness = harness()
        val pref = PersistedTrackPreference(
            subtitleType = "INTERNAL",
            subtitleLanguage = "eng",
            subtitleName = "English",
            subtitleTrackId = "3",
            subtitleIsForced = true,
            addonSubtitleId = null,
            addonSubtitleUrl = null,
            addonSubtitleAddonName = null,
            audioLanguage = "eng",
            audioName = "Surround",
            audioTrackId = "1"
        )

        harness.store.save("tt1", pref)
        harness.store.saveSubtitleDelayMs("tt1:vid", -250)

        assertEquals(pref, harness.store.load("tt1"))
        assertEquals(-250, harness.store.loadSubtitleDelayMs("tt1:vid"))
    }

    @Test
    fun `load returns null when no gating field is stored`() = runTest {
        val harness = harness()

        assertNull(harness.store.load("tt1"))

        // subtitleName alone is not a gate field: nothing meaningful to restore.
        harness.store.save("tt2", allNull().copy(subtitleName = "English"))
        assertNull(harness.store.load("tt2"))

        harness.store.save("tt3", allNull().copy(subtitleType = "INTERNAL"))
        assertEquals("INTERNAL", harness.store.load("tt3")?.subtitleType)
    }

    @Test
    fun `all-null save leaves no entry keys and no ts sidecar`() = runTest {
        val harness = harness()
        harness.store.save("tt1", allNull().copy(subtitleType = "INTERNAL"))
        assertTrue(harness.entryKeyNames("tt1").isNotEmpty())

        harness.store.save("tt1", allNull())

        assertTrue(
            "expected no keys left for tt1, had ${harness.allKeyNames()}",
            harness.entryKeyNames("tt1").isEmpty()
        )
    }

    @Test
    fun `cleared delay keeps the sidecar while fields remain and drops it when alone`() = runTest {
        val harness = harness()
        harness.store.save("tt1", allNull().copy(subtitleType = "INTERNAL"))
        harness.store.saveSubtitleDelayMs("tt1", 250)

        harness.store.saveSubtitleDelayMs("tt1", 0)

        // The sidecar belongs to the id's group: it survives a cleared delay
        // while track fields remain (still needed for eviction ordering)…
        assertTrue(harness.entryKeyNames("tt1").none { it.startsWith("sub_delay_ms|") })
        assertTrue(harness.entryKeyNames("tt1").any { it.startsWith("ts|") })
        assertTrue(harness.entryKeyNames("tt1").any { it.startsWith("sub_type|") })

        // …and a cleared delay on an otherwise-empty id leaves no orphan timestamp.
        harness.store.saveSubtitleDelayMs("tt2", 250)
        harness.store.saveSubtitleDelayMs("tt2", 0)
        assertTrue(harness.entryKeyNames("tt2").isEmpty())
    }

    @Test
    fun `over-cap write evicts the oldest group whole across key types`() = runTest {
        val harness = harness()
        // One group mixing string keys (track fields) and an int key (delay),
        // saved strictly before everything else so eviction must pick it.
        harness.store.save("zz-old", allNull().copy(subtitleType = "INTERNAL"))
        harness.store.saveSubtitleDelayMs("zz-old", 250)
        advanceWallClockDistinctly()
        repeat(500) { index ->
            harness.store.save("filler$index", allNull().copy(subtitleType = "INTERNAL"))
        }

        val evicted = harness.entryKeyNames("zz-old")
        assertTrue("expected the whole group evicted, still present: $evicted", evicted.isEmpty())
        assertEquals(500, harness.entryGroupIds().size)
        // No half-evicted group: every surviving id keeps at least one non-sidecar key.
        harness.entryGroupIds().forEach { id ->
            assertTrue(
                "group $id survived with only a ts sidecar",
                harness.entryKeyNames(id).any { !it.startsWith("ts|") }
            )
        }
    }

    private fun allNull() = PersistedTrackPreference(
        subtitleType = null,
        subtitleLanguage = null,
        subtitleName = null,
        subtitleTrackId = null,
        subtitleIsForced = null,
        addonSubtitleId = null,
        addonSubtitleUrl = null,
        addonSubtitleAddonName = null,
        audioLanguage = null,
        audioName = null,
        audioTrackId = null
    )

    /** Saves stamp System.currentTimeMillis(); make the next save's timestamp distinct. */
    private fun advanceWallClockDistinctly() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() == start) Thread.sleep(1)
    }

    private fun harness(): Harness {
        val store = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(TrackPreferenceDataStore(factory, profileManager), store)
    }

    private class Harness(val store: TrackPreferenceDataStore, val backing: TestPreferencesDataStore) {
        fun allKeyNames(): List<String> = backing.value.asMap().keys.map { it.name }

        fun entryKeyNames(id: String): List<String> =
            allKeyNames().filter { '|' in it && it.substringAfter('|') == id }

        fun entryGroupIds(): Set<String> =
            allKeyNames().filter { '|' in it }.map { it.substringAfter('|') }.toSet()
    }
}
