package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * L2-01: the LIVE clock governor (governPosition, reached through getCurrentPositionUs)
 * caps the master clock at a 1.0x wall-clock ceiling, so it must stay out of the way at
 * any other playback speed and must not carry state across a speed change.
 */
class PlaybackSpeedAwareAudioSinkGovernorTest {

    private lateinit var delegate: AudioSink
    private lateinit var sink: PlaybackSpeedAwareAudioSink
    private var nowMs = 10_000L
    private var rawUs = 0L

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { nowMs }

        delegate = mockk(relaxed = true)
        every { delegate.getCurrentPositionUs(any()) } answers { rawUs }
        sink = PlaybackSpeedAwareAudioSink(delegate)
        sink.setPlaybackActive(true)
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
        unmockkStatic(Log::class)
    }

    /**
     * Advances wall time in 25 ms reads while the raw delegate clock advances at
     * [rawRate] x wall; returns how many reads reported something other than raw.
     */
    private fun readsDivergingFromRaw(steps: Int, rawRate: Double): Int {
        var diverging = 0
        repeat(steps) {
            nowMs += STEP_MS
            rawUs += (STEP_MS * 1_000L * rawRate).toLong()
            if (sink.getCurrentPositionUs(false) != rawUs) diverging++
        }
        return diverging
    }

    @Test
    fun `at 1x a raced clock still engages and freezes the governor`() {
        assertTrue(readsDivergingFromRaw(steps = 40, rawRate = 2.0) > 0)
    }

    @Test
    fun `at 2x the governor never holds the clock`() {
        sink.setPlaybackParameters(PlaybackParameters(2f))
        assertEquals(0, readsDivergingFromRaw(steps = 200, rawRate = 2.0))
    }

    @Test
    fun `at 1_75x the governor never holds the clock`() {
        sink.setPlaybackParameters(PlaybackParameters(1.75f))
        assertEquals(0, readsDivergingFromRaw(steps = 200, rawRate = 1.75))
    }

    @Test
    fun `speed change drops a freeze and the old-speed tail at 1x does not re-engage`() {
        // An engaged freeze at 1x...
        assertTrue(readsDivergingFromRaw(steps = 40, rawRate = 2.0) > 0)
        // ...is dropped by the change to 2x.
        sink.setPlaybackParameters(PlaybackParameters(2f))
        assertEquals(0, readsDivergingFromRaw(steps = 20, rawRate = 2.0))
        // Back at 1x, Media3 keeps reporting the 2x rate until the audio queued at 2x
        // has played out (750 ms here), then settles to 1x: no hold throughout.
        sink.setPlaybackParameters(PlaybackParameters(1f))
        assertEquals(0, readsDivergingFromRaw(steps = 30, rawRate = 2.0))
        assertEquals(0, readsDivergingFromRaw(steps = 40, rawRate = 1.0))
        // Once settled, the storm guard is armed again.
        assertTrue(readsDivergingFromRaw(steps = 40, rawRate = 2.0) > 0)
    }

    @Test
    fun `flush after a speed change clears the settle gate so a raced window engages`() {
        sink.setPlaybackParameters(PlaybackParameters(2f))
        sink.setPlaybackParameters(PlaybackParameters(1f))

        // A flush discards the old-speed audio, so there is no tail left to wait out.
        sink.flush()

        assertTrue(readsDivergingFromRaw(steps = 40, rawRate = 2.0) > 0)
    }

    @Test
    fun `a flat window after 2x to 1x counts as settled, so a following 2x-rate tail engages`() {
        sink.setPlaybackParameters(PlaybackParameters(2f))
        sink.setPlaybackParameters(PlaybackParameters(1f))
        // A stalled (zero-rate) window while playing is calm (<=1.5x): it clears the gate.
        assertEquals(0, readsDivergingFromRaw(steps = 4, rawRate = 0.0))

        // Pinned current behaviour: a 2x-rate tail arriving after that window is
        // indistinguishable from a storm and engages.
        assertTrue(readsDivergingFromRaw(steps = 40, rawRate = 2.0) > 0)
    }

    @Test
    fun `at 1x a storm freezes at the 1x ceiling, holds, then follows raw after the freeze cap`() {
        // Reads every 25 ms from t=10_000 with raw racing at 2x. The first read seeds
        // the rate window; the third (t=10_075, raw=150_000) closes it at rate 200 and
        // engages with the 1.0x ceiling based at (150_000 us, 10_075 ms). Raw leads that
        // ceiling by 25 ms per read, so the lead first exceeds 300 ms at t=10_400.
        var firstHeldAtMs = -1L
        var firstHeldUs = -1L
        while (firstHeldAtMs < 0L) {
            nowMs += STEP_MS
            rawUs += STEP_MS * 2_000L
            val reported = sink.getCurrentPositionUs(false)
            if (reported != rawUs) {
                firstHeldAtMs = nowMs
                firstHeldUs = reported
            }
            assertTrue("no freeze by $nowMs", nowMs < 11_000L)
        }
        assertEquals(10_400L, firstHeldAtMs)
        // base + elapsed wall since engage x 1000: 150_000 + (10_400 - 10_075) * 1000.
        assertEquals(475_000L, firstHeldUs)

        // Held constant while frozen; the 3_500 ms cap is exceeded first at t=13_925.
        while (nowMs + STEP_MS <= 13_900L) {
            nowMs += STEP_MS
            rawUs += STEP_MS * 2_000L
            assertEquals(475_000L, sink.getCurrentPositionUs(false))
        }
        assertEquals(0, readsDivergingFromRaw(steps = 40, rawRate = 2.0))
    }

    @Test
    fun `at 1x a teleport-grade jump never engages the governor`() {
        assertEquals(0, readsDivergingFromRaw(steps = 4, rawRate = 1.0))
        // A 10 s jump inside one 50 ms window is rate ~200x, above the 40x teleport cut.
        rawUs += 10_000_000L

        assertEquals(0, readsDivergingFromRaw(steps = 200, rawRate = 1.0))
    }

    private companion object {
        const val STEP_MS = 25L
    }
}
