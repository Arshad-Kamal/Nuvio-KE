package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 47 D2: which bingeGroups name a release (AIOStreams v2.36.0 generateBingeGroup shapes). */
class BingeGroupsTest {

    @Test
    fun `the bare AIOStreams prefix names no release`() {
        assertFalse(BingeGroups.namesRelease("com.aiostreams.viren070|"))
        assertFalse(BingeGroups.namesRelease("com.aiostreams.viren070| "))
        // Empty-string attributes survive AIOStreams' nullish filter and join to blanks.
        assertFalse(BingeGroups.namesRelease("com.aiostreams.viren070|||"))
        assertFalse(BingeGroups.namesRelease("|"))
    }

    @Test
    fun `null and blank name no release`() {
        assertFalse(BingeGroups.namesRelease(null))
        assertFalse(BingeGroups.namesRelease(""))
        assertFalse(BingeGroups.namesRelease("   "))
    }

    @Test
    fun `a group with any attribute names a release`() {
        assertTrue(BingeGroups.namesRelease("com.aiostreams.viren070|1080p|WEB-DL|NTb"))
        assertTrue(BingeGroups.namesRelease("com.aiostreams.viren070|1080p"))
        assertTrue(BingeGroups.namesRelease("com.aiostreams.viren070||WEB-DL"))
        // matchingIndex method: the row index.
        assertTrue(BingeGroups.namesRelease("com.aiostreams.viren070|0"))
        assertTrue(BingeGroups.namesRelease("|1080p"))
        assertTrue(BingeGroups.namesRelease("  com.aiostreams.viren070|1080p  "))
    }

    @Test
    fun `another addon's group without a separator is unchanged`() {
        assertTrue(BingeGroups.namesRelease("torrentio-1080p"))
        assertTrue(BingeGroups.namesRelease("same-group"))
    }
}
