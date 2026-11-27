package com.nuvio.tv.ui.screens.settings.trackers

import com.nuvio.tv.core.tracking.TrackingDeviceCode
import com.nuvio.tv.core.tracking.TrackingDevicePoll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 104 S3b: the device-code sign-in (mockup B1-B5). */
class TrackerSignInMachineTest {

    private fun code(n: Int, interval: Int = 5, expiresAt: Long = 600_000L) =
        TrackingDeviceCode("device-$n", "CODE$n", "https://example.test/pin", null, expiresAt, interval)

    private var state = initialTrackerSignIn()

    private fun send(event: TrackerSignInEvent): List<TrackerSignInEffect> =
        reduceTrackerSignIn(state, event).let { (next, effects) -> state = next; effects }

    private fun waiting(n: Int = 1, interval: Int = 5, expiresAt: Long = 600_000L) {
        send(TrackerSignInEvent.Restart)
        send(TrackerSignInEvent.Started(state.attempt, code(n, interval, expiresAt)))
    }

    @Test
    fun `opening asks for a code and polls at the service's interval until approved`() {
        assertEquals(listOf(TrackerSignInEffect.Start(1)), send(TrackerSignInEvent.Restart))
        val code = code(1)
        assertEquals(listOf(TrackerSignInEffect.Poll(1, code, 5_000L)), send(TrackerSignInEvent.Started(1, code)))
        assertEquals(listOf(TrackerSignInEffect.Poll(1, code, 5_000L)), send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Pending, 10_000L)))
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Approved, 15_000L)))
        assertEquals(TrackerSignIn.Connected(1), state)
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.Restart))
    }

    @Test
    fun `slow down adds five seconds for good, and no service is polled faster than two`() {
        waiting(interval = 1)
        assertEquals(2, (state as TrackerSignIn.Waiting).intervalSeconds)
        val effects = send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.SlowDown, 1_000L))
        assertEquals(7_000L, (effects.single() as TrackerSignInEffect.Poll).delayMs)
        assertEquals(7_000L, (send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Pending, 2_000L)).single() as TrackerSignInEffect.Poll).delayMs)
    }

    @Test
    fun `expired by the service or by the clock stops polling, and New code starts over`() {
        waiting()
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Expired, 1_000L)))
        assertTrue(state is TrackerSignIn.Expired)
        assertEquals(listOf(TrackerSignInEffect.Start(2)), send(TrackerSignInEvent.Restart))

        send(TrackerSignInEvent.Started(2, code(2, expiresAt = 60_000L)))
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.Polled(2, TrackingDevicePoll.Pending, 60_000L)))
        assertTrue(state is TrackerSignIn.Expired)
    }

    @Test
    fun `declined on the phone fails with nothing connected`() {
        waiting()
        send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Denied, 1_000L))
        assertEquals(TrackerSignIn.Failed(1, TrackerSignIn.Reason.DENIED, code(1)), state)
    }

    @Test
    fun `network failures back off, and the fourth in a row shows unreachable`() {
        waiting()
        val delays = (1..3).map { (send(TrackerSignInEvent.PollFailed(1, 1_000L)).single() as TrackerSignInEffect.Poll).delayMs }
        assertEquals(listOf(10_000L, 20_000L, 30_000L), delays)
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.PollFailed(1, 1_000L)))
        assertEquals(TrackerSignIn.Reason.UNREACHABLE, (state as TrackerSignIn.Failed).reason)
    }

    @Test
    fun `an answer resets the failure count`() {
        waiting()
        send(TrackerSignInEvent.PollFailed(1, 1_000L))
        send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Pending, 2_000L))
        assertEquals(0, (state as TrackerSignIn.Waiting).failures)
    }

    @Test
    fun `a code that cannot be fetched shows unreachable, and Try again asks again`() {
        send(TrackerSignInEvent.Restart)
        send(TrackerSignInEvent.StartFailed(1))
        assertEquals(TrackerSignIn.Failed(1, TrackerSignIn.Reason.UNREACHABLE, null), state)
        assertEquals(listOf(TrackerSignInEffect.Start(2)), send(TrackerSignInEvent.Restart))
    }

    @Test
    fun `results of a replaced code are ignored`() {
        waiting()
        send(TrackerSignInEvent.Restart)
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.Polled(1, TrackingDevicePoll.Approved, 1_000L)))
        assertFalse(state is TrackerSignIn.Connected)
        send(TrackerSignInEvent.Started(1, code(9)))
        assertEquals(TrackerSignIn.Starting(2), state)
    }

    @Test
    fun `the device code never reaches a log line`() {
        assertFalse(code(1).toString().contains("device-1"))
    }

    @Test
    fun `offline until the code runs out ends on expired, not on a further retry`() {
        waiting(expiresAt = 60_000L)
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.PollFailed(1, 60_000L)))
        assertTrue(state is TrackerSignIn.Expired)
    }

    @Test
    fun `a new sign-in continues the attempt count, so the previous one's late results never match`() {
        state = initialTrackerSignIn(lastAttempt = 4)
        assertEquals(listOf(TrackerSignInEffect.Start(5)), send(TrackerSignInEvent.Restart))
        assertEquals(emptyList<TrackerSignInEffect>(), send(TrackerSignInEvent.Started(1, code(1))))
        assertTrue(state is TrackerSignIn.Starting)
    }
}
