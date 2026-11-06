package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the one home for DV codec-string parsing shared by the MKV and MP4/TS paths. */
class DvCodecStringTest {

    @Test
    fun profile_readsEveryDvPrefixAndBothDigitForms() {
        assertEquals(7, DvCodecString.profile("dvhe.07.06"))
        assertEquals(5, DvCodecString.profile("DVH1.05.06"))
        assertEquals(9, DvCodecString.profile("dvav.09.05"))
        assertEquals(10, DvCodecString.profile("dva1.10.09"))
        assertEquals(7, DvCodecString.profile("dvhe.7.06"))
        assertEquals(8, DvCodecString.profile(" dvhe.08.06 "))
        assertEquals(7, DvCodecString.profile("dvhe.07"))
    }

    @Test
    fun profile_readsOnlyTheFirstListEntry() {
        // It gates conversion in the MP4/TS extractor: a DV entry behind an HEVC one is not DV.
        assertEquals(7, DvCodecString.profile("dvhe.07.06,hvc1.2.4.L153.B0"))
        assertNull(DvCodecString.profile("hvc1.2.4.L153.B0,dvhe.07.06"))
    }

    @Test
    fun profile_isNullForNonDvOrMalformed() {
        assertNull(DvCodecString.profile(null))
        assertNull(DvCodecString.profile(""))
        assertNull(DvCodecString.profile("hvc1.2.4.L153.B0"))
        assertNull(DvCodecString.profile("dvhe"))
        assertNull(DvCodecString.profile("dvhe.xx.06"))
    }

    @Test
    fun rewrite_profiles5And7To8KeepingCaseAndTwoDigits() {
        assertEquals("dvhe.08.06", DvCodecString.rewriteToProfile8("dvhe.07.06"))
        assertEquals("dvh1.08.09", DvCodecString.rewriteToProfile8("dvh1.05.09"))
        assertEquals("DVHE.08.06", DvCodecString.rewriteToProfile8("DVHE.07.06"))
        // Single-digit profile: once rewritten on MKV only, now on every container.
        assertEquals("dvhe.08.06", DvCodecString.rewriteToProfile8("dvhe.7.06"))
        assertEquals("dvhe.08.06", DvCodecString.rewriteToProfile8(" dvhe.07.06 "))
        assertEquals("dvhe.08", DvCodecString.rewriteToProfile8("dvhe.07"))
    }

    @Test
    fun rewrite_touchesOnlyDvEntriesOfAList() {
        assertEquals(
            "hvc1.2.4.L153.B0,dvhe.08.06",
            DvCodecString.rewriteToProfile8("hvc1.2.4.L153.B0,dvhe.07.06")
        )
    }

    @Test
    fun rewrite_isNullWhenNothingChanges() {
        assertNull(DvCodecString.rewriteToProfile8(null))
        assertNull(DvCodecString.rewriteToProfile8(" "))
        assertNull(DvCodecString.rewriteToProfile8("dvhe.08.06"))
        assertNull(DvCodecString.rewriteToProfile8("hvc1.2.4.L153.B0"))
        assertNull(DvCodecString.rewriteToProfile8("dvhe.xx.06"))
    }
}
