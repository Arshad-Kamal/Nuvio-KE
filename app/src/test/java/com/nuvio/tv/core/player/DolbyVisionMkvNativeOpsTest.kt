package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 21 N4: [mkvNativeOps] maps the per-stream policy to native ops exactly as the Kotlin path
 * branches (dv-golden README "Per-sample order"; configs as in DolbyVisionGoldenCorpusTest).
 */
class DolbyVisionMkvNativeOpsTest {

    private val strip = DoviBridge.MKV_OP_STRIP_RPU
    private val convert = DoviBridge.MKV_OP_CONVERT
    private val h10p = DoviBridge.MKV_OP_STRIP_HDR10PLUS

    private val auto = DolbyVisionConversionConfig(active = true)
    private val manual = DolbyVisionConversionConfig(active = true, manualDv81 = true)
    private val dv5 = DolbyVisionConversionConfig(active = true, dv5Enabled = true, manualDv81 = true)
    private val inactive = DolbyVisionConversionConfig(active = false)

    private fun ops(
        config: DolbyVisionConversionConfig,
        profile: Int?,
        stripRpuOnly: Boolean = false,
        hdr10Plus: Boolean = false,
        dv5Strip: Boolean = false,
    ) = mkvNativeOps(config, stripRpuOnly, hdr10Plus, profile) { dv5Strip }

    @Test
    fun `convert configs convert P7 with the config's mode`() {
        assertEquals(MkvNativeOps(convert, 1), ops(auto, 7))
        assertEquals(MkvNativeOps(convert, 2), ops(manual, 7))
        assertEquals(MkvNativeOps(convert or h10p, 2), ops(manual, 7, hdr10Plus = true))
    }

    @Test
    fun `profiles that do not convert run only the HDR10+ step`() {
        assertEquals(0, ops(auto, 8).ops)
        assertEquals(0, ops(manual, 5).ops)
        assertEquals(0, ops(auto, null).ops)
        assertEquals(h10p, ops(inactive, null, hdr10Plus = true).ops)
        assertEquals(h10p, ops(manual, 8, hdr10Plus = true).ops)
    }

    @Test
    fun `DV5 converts only with dv5 enabled and manual DV8_1, mode 2`() {
        assertEquals(MkvNativeOps(convert, 2), ops(dv5, 5))
    }

    @Test
    fun `strip configs strip, P5 only once the DV5 decision is STRIP`() {
        assertEquals(strip, ops(inactive, 7, stripRpuOnly = true).ops)
        assertEquals(strip or h10p, ops(inactive, 8, stripRpuOnly = true, hdr10Plus = true).ops)
        assertEquals(0, ops(inactive, 5, stripRpuOnly = true, dv5Strip = false).ops)
        assertEquals(h10p, ops(inactive, 5, stripRpuOnly = true, hdr10Plus = true, dv5Strip = false).ops)
        assertEquals(strip, ops(inactive, 5, stripRpuOnly = true, dv5Strip = true).ops)
    }

    @Test
    fun `the DV5 decision is consulted only for a profile-5 sample under strip`() {
        var asked = 0
        val decide = { asked++; true }
        mkvNativeOps(inactive, stripRpuOnly = true, stripHdr10PlusSei = false, profile = 7, dv5Strip = decide)
        mkvNativeOps(dv5, stripRpuOnly = false, stripHdr10PlusSei = false, profile = 5, dv5Strip = decide)
        assertEquals(0, asked)
        assertTrue((mkvNativeOps(inactive, true, false, 5, decide).ops and strip) != 0)
        assertEquals(1, asked)
        assertFalse((mkvNativeOps(inactive, true, false, 5) { false }.ops and strip) != 0)
    }
}
