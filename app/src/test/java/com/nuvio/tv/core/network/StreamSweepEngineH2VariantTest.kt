package com.nuvio.tv.core.network

import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.Http2Grounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the F4 h2 A/B verdict maths: the H2Variant win bar, the void rule it
 * shares with the ladder's measure() (one home), and the assessment's
 * http2-row decision (recommendation, tier, ordered grounds figures). The
 * networked sweep wiring around them (cell scheduling, labels) has no JVM
 * seam — engine-level, like the rest of the sweep.
 */
class StreamSweepEngineH2VariantTest {

    @Test
    fun `h1 materially faster wins`() {
        // 110 vs 100: exactly at the bar is NOT a win (strict >).
        val atBar = StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 100.0, h1Mbps = 110.0)!!
        assertFalse(atBar.h1Wins)
        val aboveBar = StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 100.0, h1Mbps = 110.1)!!
        assertTrue(aboveBar.h1Wins)
    }

    @Test
    fun `small h1 edge and h2 lead do not win`() {
        // Within single-pass noise (5%): keep the multiplexed default.
        assertFalse(
            StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 100.0, h1Mbps = 105.0)!!.h1Wins
        )
        // h2 faster: multiplexing is free or better.
        assertTrue(
            !StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 120.0, h1Mbps = 90.0)!!.h1Wins
        )
    }

    @Test
    fun `void arms yield no variant`() {
        assertNull(StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 0.0, h1Mbps = 90.0))
        assertNull(StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 88.0, h1Mbps = 0.0))
    }

    @Test
    fun `outcome defaults to no variant`() {
        // Source compatibility: existing constructions without the field carry null.
        val outcome = StreamSweepEngine.SweepOutcome(
            verdictKind = StreamSweepEngine.VerdictKind.NONE,
            verdictText = null,
            recommendation = null,
            baselineMbps = 10.0,
            targetMbps = null,
            measured = emptyList(),
            errorText = null,
            stabilityCoV = null,
            stabilityPassCount = 0
        )
        assertNull(outcome.h2Variant)
    }

    @Test
    fun `variant carries the tested config and both arms`() {
        val v = StreamSweepEngine.H2Variant.of(3, 32, h2Mbps = 50.0, h1Mbps = 60.0)!!
        assertEquals(3, v.connections)
        assertEquals(32, v.chunkMb)
        assertEquals(50.0, v.h2Mbps, 0.0)
        assertEquals(60.0, v.h1Mbps, 0.0)
        assertTrue(v.h1Wins)
    }

    @Test
    fun `void rule is one home for ladder and variant`() {
        // Clamped: the cell ran on ONE connection, not the labelled count.
        val clamped = StreamSpeedTester.ParallelPassResult(
            mbps = 50.0, subWindowMbps = emptyList(), clampTrips = 1
        )
        assertEquals("rate-limited", StreamSweepEngine.voidReasonFor(clamped, floorMbps = 2.0))
        // Starved: 429 backoffs ate the window; below the zero floor.
        val starved = StreamSpeedTester.ParallelPassResult(
            mbps = 1.0, subWindowMbps = emptyList()
        )
        assertEquals("no usable transfer", StreamSweepEngine.voidReasonFor(starved, floorMbps = 2.0))
        // Clean measurement.
        val clean = StreamSpeedTester.ParallelPassResult(
            mbps = 50.0, subWindowMbps = emptyList()
        )
        assertNull(StreamSweepEngine.voidReasonFor(clean, floorMbps = 2.0))
    }

    @Test
    fun `void rule floor is inclusive like the former F4 log check`() {
        // The F4 h2 A/B log line used `mbps >= floor && clampTrips == 0`; it
        // now asks voidReasonFor(...) == null. Exactly at the floor is valid.
        val atFloor = StreamSpeedTester.ParallelPassResult(
            mbps = 2.0, subWindowMbps = emptyList()
        )
        assertNull(StreamSweepEngine.voidReasonFor(atFloor, floorMbps = 2.0))
        val justBelow = StreamSpeedTester.ParallelPassResult(
            mbps = 1.99, subWindowMbps = emptyList()
        )
        assertEquals("no usable transfer", StreamSweepEngine.voidReasonFor(justBelow, floorMbps = 2.0))
    }

    @Test
    fun `http2 row decision without a variant keeps the HTTP 1_1 default with the caveat`() {
        // Spec 36/S6: an unmeasured assessment must never write enableHttp2=true.
        val d = DeviceAssessmentEngine.http2RowDecision(null)
        assertFalse(d.recommendOn)
        assertFalse(d.measured)
        assertEquals(Http2Grounds.Caveat, d.grounds)
    }

    @Test
    fun `http2 row decision when h1 wins recommends off with the h1 figure first`() {
        val d = DeviceAssessmentEngine.http2RowDecision(
            StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 100.0, h1Mbps = 130.0)!!
        )
        assertFalse(d.recommendOn)
        assertTrue(d.measured)
        val g = d.grounds as Http2Grounds.Measured
        assertEquals(4, g.connections)
        assertEquals(16, g.chunkMb)
        // The RECOMMENDED arm's figure goes first (h1 wins -> h1 first).
        assertEquals(130.0, g.recommendedMbps, 0.0)
        assertEquals(100.0, g.otherMbps, 0.0)
    }

    @Test
    fun `http2 row decision when h2 holds recommends on with the h2 figure first`() {
        val d = DeviceAssessmentEngine.http2RowDecision(
            StreamSweepEngine.H2Variant.of(4, 16, h2Mbps = 120.0, h1Mbps = 90.0)!!
        )
        assertTrue(d.recommendOn)
        assertTrue(d.measured)
        val g = d.grounds as Http2Grounds.Measured
        assertEquals(120.0, g.recommendedMbps, 0.0)
        assertEquals(90.0, g.otherMbps, 0.0)
    }
}
