package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Pins the on-disk names that ProfileManager's profile deletion sweeps (L9-51). */
class ContinueWatchingCacheFilesTest {

    private val filesDir = File("/data/files")

    @Test
    fun `per-profile cache files keep their persisted names`() {
        assertEquals(
            File("/data/files/cw_enrichment/nextup_3.json"),
            ContinueWatchingEnrichmentCache.nextUpCacheFile(filesDir, 3)
        )
        assertEquals(
            File("/data/files/cw_enrichment/inprogress_3.json"),
            ContinueWatchingEnrichmentCache.inProgressCacheFile(filesDir, 3)
        )
    }
}
