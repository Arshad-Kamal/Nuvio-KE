package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 33 D7: the network test's notice and pause chip stay with the stream the test ran on. */
class NetworkTestResultStateTest {

    private val held = "https://download.real-debrid.com/d/abc/film.mkv"
    private val tested = PlayerUiState(
        networkTestResult = NetworkTestResult(service = "real-debrid.com", connections = 4),
        networkTestNoticePending = true
    )

    @Test
    fun `a re-init of the tested stream keeps the result and the pending notice`() {
        assertSame(tested, tested.withNetworkTestResultFor(held, heldUrl = held))
    }

    @Test
    fun `another stream clears both`() {
        val next = tested.withNetworkTestResultFor("https://download.real-debrid.com/d/xyz/next.mkv", heldUrl = held)
        assertNull(next.networkTestResult)
        assertFalse(next.networkTestNoticePending)
        assertEquals(tested.copy(networkTestResult = null, networkTestNoticePending = false), next)
    }

    @Test
    fun `no test held yet clears too`() {
        assertNull(tested.withNetworkTestResultFor(held, heldUrl = null).networkTestResult)
    }
}
