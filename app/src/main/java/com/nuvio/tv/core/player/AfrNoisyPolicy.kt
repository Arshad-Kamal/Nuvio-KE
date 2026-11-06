package com.nuvio.tv.core.player

/**
 * Spec 50 I6 (Spec 12 F-1): an AFR display-mode switch re-plugs HDMI, and Android then broadcasts
 * ACTION_AUDIO_BECOMING_NOISY, which Media3 (setHandleAudioBecomingNoisy) turns into a pause: the film stopped
 * at 0:01 after "Track AFR: settle complete". A noisy pause within [GRACE_MS] of a mode switch this app made is
 * that re-plug, not a headphone/BT disconnect, so playback resumes. Outside the window the pause stands.
 */
internal object AfrNoisyPolicy {
    const val GRACE_MS = 10_000L

    fun shouldResume(
        noisyPause: Boolean,
        playWhenReady: Boolean,
        userPausedManually: Boolean,
        lastModeSwitchElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean = noisyPause &&
        !playWhenReady &&
        !userPausedManually &&
        lastModeSwitchElapsedMs > 0L &&
        nowElapsedMs - lastModeSwitchElapsedMs in 0L..GRACE_MS
}
