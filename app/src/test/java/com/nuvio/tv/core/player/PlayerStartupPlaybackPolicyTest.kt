package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupPlaybackPolicyTest {

    private fun baseState(
        shouldEnforceAutoplayOnFirstReady: Boolean = true,
        hasRenderedFirstFrame: Boolean = false,
        userPausedManually: Boolean = false,
        startPaused: Boolean = false,
        isTunneledPlayback: Boolean = false,
    ) = PlayerStartupPlaybackPolicy.ReadyState(
        shouldEnforceAutoplayOnFirstReady = shouldEnforceAutoplayOnFirstReady,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        userPausedManually = userPausedManually,
        startPaused = startPaused,
        isTunneledPlayback = isTunneledPlayback,
    )

    @Test
    fun firstReadyColdStartStartsPlayback() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(baseState())

        assertFalse(transition.nextState.shouldEnforceAutoplayOnFirstReady)
        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.ColdStartPrime, transition.action)
        assertTrue(transition.action.startsPlayback)
    }

    @Test
    fun firstReadyTunneledStartsPlaybackImmediately() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(isTunneledPlayback = true)
        )

        assertTrue(transition.nextState.hasRenderedFirstFrame)
        val action = transition.action as PlayerStartupPlaybackPolicy.ReadyAction.TunneledFirstReady
        assertTrue(action.startsPlayback)
    }

    @Test
    fun firstReadyTunneledStartPausedMarksFirstFrameWithoutStarting() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(isTunneledPlayback = true, startPaused = true)
        )

        assertTrue(transition.nextState.hasRenderedFirstFrame)
        val action = transition.action as PlayerStartupPlaybackPolicy.ReadyAction.TunneledFirstReady
        assertFalse(action.startsPlayback)
    }

    @Test
    fun firstReadyStartPausedDoesNothing() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(startPaused = true)
        )

        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.None, transition.action)
        assertFalse(transition.action.startsPlayback)
    }

    @Test
    fun secondReadyBeforeFirstFrameResumesPlayback() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(
                shouldEnforceAutoplayOnFirstReady = false,
                hasRenderedFirstFrame = false,
            )
        )

        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.PreFirstFrameResume, transition.action)
        assertTrue(transition.action.startsPlayback)
    }

    @Test
    fun secondReadyAfterFirstFrameResumesPlayback() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(
                shouldEnforceAutoplayOnFirstReady = false,
                hasRenderedFirstFrame = true,
            )
        )

        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.PostFirstFrameResume, transition.action)
        assertTrue(transition.action.startsPlayback)
    }

    @Test
    fun userPausedAfterFirstReadyGateDoesNothing() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(
                shouldEnforceAutoplayOnFirstReady = false,
                hasRenderedFirstFrame = true,
                userPausedManually = true,
            )
        )

        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.None, transition.action)
    }

    // Window NOTE N7: mpv honours a paused start, except at position 0.
    @Test
    fun mpvPausedStartAtAPositionStaysPaused() {
        assertTrue(PlayerStartupPlaybackPolicy.mpvStartsPaused(userPausedManually = true, startPositionMs = 318_199L))
    }

    @Test
    fun mpvPausedStartAtZeroPlays() {
        assertFalse(PlayerStartupPlaybackPolicy.mpvStartsPaused(userPausedManually = true, startPositionMs = 0L))
    }

    @Test
    fun mpvUnpausedStartPlays() {
        assertFalse(PlayerStartupPlaybackPolicy.mpvStartsPaused(userPausedManually = false, startPositionMs = 318_199L))
    }

    @Test
    fun mpvIdleCoreCountsAsBufferingOnlyWhilePlaying() {
        assertTrue(PlayerStartupPlaybackPolicy.mpvCacheBuffering(pausedForCache = false, playing = true, coreIdle = true))
        assertFalse(PlayerStartupPlaybackPolicy.mpvCacheBuffering(pausedForCache = false, playing = false, coreIdle = true))
        assertTrue(PlayerStartupPlaybackPolicy.mpvCacheBuffering(pausedForCache = true, playing = false, coreIdle = true))
    }
}
