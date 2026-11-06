package com.nuvio.tv.core.player

/**
 * Pure startup playback decisions for [Player.STATE_READY] before and after the first
 * rendered frame. Extracted so instrumented fixture tests can mirror production 1:1.
 */
internal object PlayerStartupPlaybackPolicy {

    data class ReadyState(
        val shouldEnforceAutoplayOnFirstReady: Boolean,
        val hasRenderedFirstFrame: Boolean,
        val userPausedManually: Boolean,
        val startPaused: Boolean,
        val isTunneledPlayback: Boolean,
    )

    /**
     * [startsPlayback] is the start bit the controller passes through its AFR gates (on
     * ExoPlayer play() is setPlayWhenReady(true), so a start is a single bit): false for
     * [None] and a paused [TunneledFirstReady], true for every other action.
     */
    sealed class ReadyAction {
        abstract val startsPlayback: Boolean

        data object None : ReadyAction() {
            override val startsPlayback: Boolean = false
        }
        /**
         * Tunneled first READY, which also stands in for the first frame. [startsPlayback]
         * is false when the stream starts paused or the user paused before READY.
         */
        data class TunneledFirstReady(override val startsPlayback: Boolean) : ReadyAction()
        /** Initial cold start reached READY: start playback now (nothing waits for first frame). */
        data object ColdStartPrime : ReadyAction() {
            override val startsPlayback: Boolean = true
        }
        data object PostFirstFrameResume : ReadyAction() {
            override val startsPlayback: Boolean = true
        }
        /** Resume seek / track rebuild reached READY again before first frame. */
        data object PreFirstFrameResume : ReadyAction() {
            override val startsPlayback: Boolean = true
        }
    }

    data class ReadyTransition(
        val nextState: ReadyState,
        val action: ReadyAction,
    )

    fun onStateReady(state: ReadyState): ReadyTransition {
        if (state.shouldEnforceAutoplayOnFirstReady) {
            val next = state.copy(shouldEnforceAutoplayOnFirstReady = false)
            return if (state.isTunneledPlayback) {
                ReadyTransition(
                    nextState = next.copy(hasRenderedFirstFrame = true),
                    action = ReadyAction.TunneledFirstReady(
                        startsPlayback = !state.startPaused && !state.userPausedManually,
                    )
                )
            } else if (!state.startPaused && !state.userPausedManually) {
                ReadyTransition(
                    nextState = next,
                    action = ReadyAction.ColdStartPrime,
                )
            } else {
                ReadyTransition(nextState = next, action = ReadyAction.None)
            }
        }

        if (state.userPausedManually || state.startPaused) {
            return ReadyTransition(state, ReadyAction.None)
        }

        return if (state.hasRenderedFirstFrame) {
            ReadyTransition(state, ReadyAction.PostFirstFrameResume)
        } else {
            ReadyTransition(state, ReadyAction.PreFirstFrameResume)
        }
    }

    /**
     * mpv's start: a paused start (initializePlayer startPaused, window NOTE N7) stays paused
     * only at a known position. At 0 mpv's first-frame check (position > 0) never passes while
     * paused, so the loading overlay would stick; that start plays as before.
     */
    fun mpvStartsPaused(userPausedManually: Boolean, startPositionMs: Long): Boolean =
        userPausedManually && startPositionMs > 0L

    /** mpv buffering: `core-idle` is also true while paused, so it counts only while playing. */
    fun mpvCacheBuffering(pausedForCache: Boolean, playing: Boolean, coreIdle: Boolean): Boolean =
        pausedForCache || (playing && coreIdle)
}
