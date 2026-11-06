package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

// Spec 50 I1: ported from ysosrs cc1c72fcb.
class ContinueWatchingMetaTypesTest {

    @Test
    fun `a film is looked up only as a film`() {
        assertEquals(listOf("movie"), cwMetaTypeCandidates("movie"))
        assertEquals(listOf("Movie"), cwMetaTypeCandidates("Movie"))
    }

    @Test
    fun `a series keeps the series fallbacks`() {
        assertEquals(listOf("series", "tv"), cwMetaTypeCandidates("series"))
        assertEquals(listOf("tv", "series"), cwMetaTypeCandidates("tv"))
    }

    @Test
    fun `an unknown type keeps the series fallbacks`() {
        assertEquals(listOf("anime", "series", "tv"), cwMetaTypeCandidates("anime"))
    }
}
