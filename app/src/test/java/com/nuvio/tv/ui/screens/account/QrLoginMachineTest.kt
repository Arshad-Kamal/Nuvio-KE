package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrLoginMachineTest {

    private fun showing(
        code: String = "C1",
        expiresAtMs: Long? = 10_000L,
        interval: Int = 3
    ) = QrLogin.Showing(code, "N1", expiresAtMs, baseIntervalS = interval)

    /** Feeds [events] in order; returns the final state and every effect emitted. */
    private fun run(start: QrLogin, vararg events: QrEvent): Pair<QrLogin, List<QrEffect>> {
        var state = start
        val effects = mutableListOf<QrEffect>()
        events.forEach { event ->
            val (next, emitted) = reduceQrLogin(state, event)
            state = next
            effects += emitted
        }
        return state to effects
    }

    @Test
    fun `refresh from idle starts a session`() {
        val (state, effects) = reduceQrLogin(QrLogin.Idle, QrEvent.Refresh("N1"))
        assertEquals(QrLogin.Starting("N1"), state)
        assertEquals(listOf(QrEffect.CancelPoll, QrEffect.StartSession("N1")), effects)
    }

    @Test
    fun `started shows the code and schedules one poll at the server interval`() {
        val (state, effects) = reduceQrLogin(QrLogin.Starting("N1"), QrEvent.Started("N1", "C1", 10_000L, 5))
        assertEquals(QrLogin.Showing("C1", "N1", 10_000L, baseIntervalS = 5), state)
        assertEquals(listOf(QrEffect.SchedulePoll(5_000L, "C1", "N1")), effects)
    }

    @Test
    fun `started clamps a too-short interval and ignores a stale nonce`() {
        val (state, effects) = reduceQrLogin(QrLogin.Starting("N1"), QrEvent.Started("N1", "C1", null, 0))
        assertEquals(listOf(QrEffect.SchedulePoll(2_000L, "C1", "N1")), effects)
        assertEquals(2, (state as QrLogin.Showing).baseIntervalS)

        val stale = reduceQrLogin(QrLogin.Starting("N2"), QrEvent.Started("N1", "C1", null, 3))
        assertEquals(QrLogin.Starting("N2") to emptyList<QrEffect>(), stale)
    }

    @Test
    fun `start failure fails with the message and no effects`() {
        val (state, effects) = reduceQrLogin(QrLogin.Starting("N1"), QrEvent.StartFailed("N1", "offline"))
        assertEquals(QrLogin.Failed(null, "offline"), state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `approved emits exactly one exchange`() {
        val (state, effects) = run(
            showing(),
            QrEvent.Polled("C1", "approved", null, null),
            // A late duplicate poll result must not start a second exchange.
            QrEvent.Polled("C1", "approved", null, null)
        )
        assertEquals(QrLogin.Exchanging("C1", "N1"), state)
        assertEquals(listOf(QrEffect.Exchange("C1", "N1")), effects)
    }

    @Test
    fun `exchange failure ends in Failed with no effects and nothing re-exchanges`() {
        val (state, effects) = run(
            QrLogin.Exchanging("C1", "N1"),
            QrEvent.ExchangeFailed("C1", "used"),
            QrEvent.Polled("C1", "approved", null, null),
            QrEvent.PollFailed("C1", "x"),
            QrEvent.ExchangeFailed("C1", "used")
        )
        assertEquals(QrLogin.Failed("C1", "used"), state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `exchange success signs in`() {
        val (state, effects) = reduceQrLogin(QrLogin.Exchanging("C1", "N1"), QrEvent.ExchangeOk("C1"))
        assertEquals(QrLogin.SignedIn, state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `pending keeps polling, takes the server interval and expiry, resets failures`() {
        val start = showing().copy(failures = 2)
        val (state, effects) = reduceQrLogin(start, QrEvent.Polled("C1", "PENDING", 20_000L, 4))
        assertEquals(
            QrLogin.Showing("C1", "N1", 20_000L, baseIntervalS = 4, failures = 0, polled = true),
            state
        )
        assertEquals(listOf(QrEffect.SchedulePoll(4_000L, "C1", "N1")), effects)
    }

    @Test
    fun `unknown status keeps waiting at the base interval`() {
        val (state, effects) = reduceQrLogin(showing(), QrEvent.Polled("C1", "mystery", null, null))
        assertTrue(state is QrLogin.Showing)
        assertEquals(listOf(QrEffect.SchedulePoll(3_000L, "C1", "N1")), effects)
    }

    @Test
    fun `server expired, used and cancelled are terminal without regenerating`() {
        assertEquals(
            QrLogin.Expired("C1") to emptyList<QrEffect>(),
            reduceQrLogin(showing(), QrEvent.Polled("C1", "expired", null, null))
        )
        assertEquals(
            QrLogin.Ended("C1", QrLogin.EndReason.USED) to emptyList<QrEffect>(),
            reduceQrLogin(showing(), QrEvent.Polled("C1", "used", null, null))
        )
        assertEquals(
            QrLogin.Ended("C1", QrLogin.EndReason.CANCELLED) to emptyList<QrEffect>(),
            reduceQrLogin(showing(), QrEvent.Polled("C1", "cancelled", null, null))
        )
    }

    @Test
    fun `results for another session or after clear are ignored`() {
        val cases = listOf(
            showing() to QrEvent.Polled("OLD", "approved", null, null),
            showing() to QrEvent.PollFailed("OLD", "x"),
            QrLogin.Starting("N2") to QrEvent.StartFailed("N1", "x"),
            QrLogin.Exchanging("C2", "N2") to QrEvent.ExchangeOk("C1"),
            QrLogin.Exchanging("C2", "N2") to QrEvent.ExchangeFailed("C1", "x"),
            QrLogin.Idle to QrEvent.Started("N1", "C1", null, 3),
            QrLogin.Idle to QrEvent.StartFailed("N1", "x"),
            QrLogin.Idle to QrEvent.Polled("C1", "approved", null, null),
            QrLogin.Idle to QrEvent.ExchangeOk("C1"),
            QrLogin.Idle to QrEvent.ExchangeFailed("C1", "x")
        )
        cases.forEach { (start, event) ->
            assertEquals("$event in $start", start to emptyList<QrEffect>(), reduceQrLogin(start, event))
        }
    }

    @Test
    fun `poll failures back off from the base interval then fail on the fourth`() {
        var state: QrLogin = showing(interval = 3)
        val delays = mutableListOf<Long>()
        repeat(3) {
            val (next, effects) = reduceQrLogin(state, QrEvent.PollFailed("C1", "timeout"))
            state = next
            delays += (effects.single() as QrEffect.SchedulePoll).delayMs
        }
        assertEquals(listOf(6_000L, 12_000L, 24_000L), delays)

        val (failed, effects) = reduceQrLogin(state, QrEvent.PollFailed("C1", "timeout"))
        assertEquals(QrLogin.Failed("C1", "timeout"), failed)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `back-off is capped at 30 seconds`() {
        val (_, effects) = reduceQrLogin(showing(interval = 10), QrEvent.PollFailed("C1", "x"))
        assertEquals(QrEffect.SchedulePoll(20_000L, "C1", "N1"), effects.single())
        val (_, capped) = reduceQrLogin(showing(interval = 10).copy(failures = 1), QrEvent.PollFailed("C1", "x"))
        assertEquals(QrEffect.SchedulePoll(30_000L, "C1", "N1"), capped.single())
    }

    @Test
    fun `refresh restarts from every terminal state and from showing`() {
        listOf(
            showing(),
            QrLogin.Expired("C1"),
            QrLogin.Ended("C1", QrLogin.EndReason.USED),
            QrLogin.Failed("C1", "x"),
            QrLogin.Failed(null, "x")
        ).forEach { start ->
            val (state, effects) = reduceQrLogin(start, QrEvent.Refresh("N2"))
            assertEquals(QrLogin.Starting("N2"), state)
            assertEquals(listOf(QrEffect.CancelPoll, QrEffect.StartSession("N2")), effects)
        }
    }

    @Test
    fun `refresh is ignored while a request is in flight or after sign in`() {
        listOf(QrLogin.Starting("N1"), QrLogin.Exchanging("C1", "N1"), QrLogin.SignedIn).forEach { start ->
            assertEquals(start to emptyList<QrEffect>(), reduceQrLogin(start, QrEvent.Refresh("N2")))
        }
    }

    @Test
    fun `clear returns to idle and cancels polling from any state`() {
        listOf(QrLogin.Idle, showing(), QrLogin.Exchanging("C1", "N1"), QrLogin.Failed(null, "x")).forEach { start ->
            assertEquals(QrLogin.Idle to listOf<QrEffect>(QrEffect.CancelPoll), reduceQrLogin(start, QrEvent.Clear))
        }
    }

    @Test
    fun `local clock past expiry changes only the display, server state stays Showing`() {
        val state = showing(expiresAtMs = 10_000L)
        assertFalse(qrCodeDimmed(state, 9_999L))
        assertTrue(qrCodeDimmed(state, 10_000L))
        assertFalse(qrCodeDimmed(showing(expiresAtMs = null), Long.MAX_VALUE))
        assertTrue(qrCodeDimmed(QrLogin.Expired("C1"), 0L))
        assertTrue(qrCodeDimmed(QrLogin.Ended("C1", QrLogin.EndReason.CANCELLED), 0L))
        // Nothing polls a failed session's code any more.
        assertTrue(qrCodeDimmed(QrLogin.Failed("C1", "x"), 0L))
        // A pending poll after local expiry keeps the session alive; only the server ends it.
        val (next, effects) = reduceQrLogin(state, QrEvent.Polled("C1", "pending", null, null))
        assertTrue(next is QrLogin.Showing)
        assertEquals(listOf(QrEffect.SchedulePoll(3_000L, "C1", "N1")), effects)
    }

    @Test
    fun `status copy per state`() {
        assertEquals(R.string.qr_login_preparing, qrStatusRes(QrLogin.Starting("N1"), 0L))
        assertEquals(R.string.qr_login_success, qrStatusRes(QrLogin.SignedIn, 0L))
        assertEquals(R.string.qr_login_scan_prompt, qrStatusRes(showing(), 0L))
        assertEquals(R.string.qr_login_pending, qrStatusRes(showing().copy(polled = true), 0L))
        assertEquals(R.string.qr_login_signing_in, qrStatusRes(QrLogin.Exchanging("C1", "N1"), 0L))
        assertEquals(R.string.qr_login_code_expired, qrStatusRes(QrLogin.Expired("C1"), 0L))
        assertEquals(R.string.qr_login_used, qrStatusRes(QrLogin.Ended("C1", QrLogin.EndReason.USED), 0L))
        assertEquals(R.string.qr_login_cancelled, qrStatusRes(QrLogin.Ended("C1", QrLogin.EndReason.CANCELLED), 0L))
        assertEquals(null, qrStatusRes(QrLogin.Failed("C1", "x"), 0L))
        assertEquals(null, qrStatusRes(QrLogin.Idle, 0L))
    }

    @Test
    fun `local expiry reads as expired, other dead codes keep their copy`() {
        val state = showing(expiresAtMs = 10_000L).copy(polled = true)
        assertFalse(qrCodeExpired(state, 9_999L))
        assertEquals(R.string.qr_login_pending, qrStatusRes(state, 9_999L))
        assertTrue(qrCodeExpired(state, 10_000L))
        assertEquals(R.string.qr_login_code_expired, qrStatusRes(state, 10_000L))
        assertTrue(qrCodeExpired(QrLogin.Expired("C1"), 0L))
        // Used / cancelled / failed codes are dead but not expired.
        assertFalse(qrCodeExpired(QrLogin.Ended("C1", QrLogin.EndReason.USED), Long.MAX_VALUE))
        assertFalse(qrCodeExpired(QrLogin.Failed("C1", "x"), Long.MAX_VALUE))
    }

    @Test
    fun `busy only while a request is in flight`() {
        assertTrue(QrLogin.Starting("N1").isBusy)
        assertTrue(QrLogin.Exchanging("C1", "N1").isBusy)
        listOf(QrLogin.Idle, showing(), QrLogin.Expired("C1"), QrLogin.Failed("C1", "x"), QrLogin.SignedIn)
            .forEach { assertFalse("$it", it.isBusy) }
    }
}
