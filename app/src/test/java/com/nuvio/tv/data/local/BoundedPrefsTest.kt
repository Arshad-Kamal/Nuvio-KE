package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared eviction policy for the preference-per-entry caches
 * (BingeGroupCacheDataStore, StreamLinkCacheDataStore, TrackPreferenceDataStore):
 * cap 500 entries per store, oldest-timestamp-first, unknown age evicts first.
 */
class BoundedPrefsTest {

    @Test
    fun `at or under the cap evicts nothing`() {
        val timestamps = linkedMapOf("a" to 300L, "b" to 100L, "c" to 200L)
        assertTrue(BoundedPrefs.planEviction(timestamps, cap = 3).isEmpty())
        assertTrue(BoundedPrefs.planEviction(timestamps, cap = 10).isEmpty())
    }

    @Test
    fun `over the cap evicts exactly the oldest entries`() {
        val timestamps = linkedMapOf(
            "keep1" to 900L, "old1" to 100L, "keep2" to 800L,
            "old2" to 200L, "keep3" to 700L
        )
        assertEquals(setOf("old1", "old2"), BoundedPrefs.planEviction(timestamps, cap = 3))
    }

    @Test
    fun `unknown or non-positive timestamps evict first`() {
        val timestamps = linkedMapOf(
            "legacy" to 0L, "negative" to -5L, "old" to 100L,
            "k1" to 900L, "k2" to 800L, "k3" to 700L
        )
        assertEquals(setOf("negative", "legacy"), BoundedPrefs.planEviction(timestamps, cap = 4))
    }

    @Test
    fun `equal timestamps break ties by id for determinism`() {
        val timestamps = linkedMapOf("b" to 100L, "a" to 100L, "c" to 100L, "k1" to 500L)
        assertEquals(setOf("a", "b"), BoundedPrefs.planEviction(timestamps, cap = 2))
    }

    @Test
    fun `default parameter uses the ratified cap`() {
        val timestamps = (1..501).associate { "id$it" to it.toLong() }
        assertEquals(1, BoundedPrefs.planEviction(timestamps).size)
        assertEquals("id1", BoundedPrefs.planEviction(timestamps).single())
    }

    @Test
    fun `ratified default cap is 500 entries per store`() {
        assertEquals(500, BoundedPrefs.DEFAULT_MAX_ENTRIES)
    }
}
