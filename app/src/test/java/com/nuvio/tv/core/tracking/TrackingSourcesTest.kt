package com.nuvio.tv.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingSourcesTest {

    @Test
    fun `stored names are pinned (they sync with the profile settings)`() {
        assertEquals(listOf("nuvio", "mdblist", "simkl"), TrackingSource.entries.map { it.storageId })
        assertEquals(listOf("mdblist", "simkl"), TrackingProviderId.entries.map { it.storageId })
    }

    @Test
    fun `unknown, missing and pre-cut values read as Nuvio`() {
        listOf(null, "", "trakt", "TRAKT", "NUVIO_SYNC", "wetrakr").forEach { raw ->
            assertEquals(raw.toString(), TrackingSource.NUVIO, TrackingSource.fromStorage(raw))
        }
        assertEquals(TrackingSource.SIMKL, TrackingSource.fromStorage(" Simkl "))
    }

    @Test
    fun `a chosen tracker is used only while it is connected`() {
        assertEquals(TrackingSource.MDBLIST, effectiveTrackingSource(TrackingSource.MDBLIST, setOf(TrackingProviderId.MDBLIST)))
        assertEquals(TrackingSource.NUVIO, effectiveTrackingSource(TrackingSource.MDBLIST, setOf(TrackingProviderId.SIMKL)))
        assertEquals(TrackingSource.NUVIO, effectiveTrackingSource(TrackingSource.SIMKL, emptySet()))
        assertEquals(TrackingSource.NUVIO, effectiveTrackingSource(TrackingSource.NUVIO, TrackingProviderId.entries.toSet()))
    }

    @Test
    fun `the picker lists Nuvio and then each connected tracker in display order`() {
        assertEquals(listOf(TrackingSource.NUVIO), availableTrackingSources(emptySet()))
        assertEquals(
            listOf(TrackingSource.NUVIO, TrackingSource.MDBLIST, TrackingSource.SIMKL),
            availableTrackingSources(setOf(TrackingProviderId.SIMKL, TrackingProviderId.MDBLIST))
        )
        assertEquals(listOf(TrackingSource.NUVIO, TrackingSource.SIMKL), availableTrackingSources(setOf(TrackingProviderId.SIMKL)))
    }
}
