package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 21 P3: the next source's pending seek on a dead-source / placeholder failover. */
class FailoverPendingSeekTest {

    @Test
    fun `a dead source carries its live playhead`() {
        assertEquals(754_000L, failoverPendingSeek(placeholderRejected = false, livePositionMs = 754_000L, pendingSeekMs = 12_000L))
    }

    @Test
    fun `no live playhead keeps the pending seek`() {
        assertEquals(12_000L, failoverPendingSeek(placeholderRejected = false, livePositionMs = 0L, pendingSeekMs = 12_000L))
        assertNull(failoverPendingSeek(placeholderRejected = false, livePositionMs = 0L, pendingSeekMs = null))
    }

    @Test
    fun `a rejected placeholder never carries its playhead`() {
        assertEquals(12_000L, failoverPendingSeek(placeholderRejected = true, livePositionMs = 95_000L, pendingSeekMs = 12_000L))
        assertNull(failoverPendingSeek(placeholderRejected = true, livePositionMs = 95_000L, pendingSeekMs = null))
    }
}
