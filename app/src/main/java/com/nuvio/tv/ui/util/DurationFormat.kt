package com.nuvio.tv.ui.util

import java.util.concurrent.TimeUnit

/**
 * Playback clock formatting, m:ss or H:mm:ss (G6 consolidation home,
 * 2026-08-30): previously four private copies — PlayerScreen.formatTime
 * (unpadded), SharedTrailerOverlay.formatPlaybackTime and
 * PlayerRuntimeControllerSubtitleTiming.formatAutoSyncTimestamp (both
 * zero-padded), AuthQrSignInScreen.formatDuration (minutes-scale countdown).
 * Per-site padding is preserved exactly via [padMinutesUnderHour]; both
 * conventions ship today and neither is a defect.
 */
fun formatPlaybackClock(millis: Long, padMinutesUnderHour: Boolean = false): String {
    if (millis <= 0) return if (padMinutesUnderHour) "00:00" else "0:00"
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else if (padMinutesUnderHour) {
        String.format("%02d:%02d", minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * mm:ss countdown whose minutes are NOT broken into hours (a 90-minute
 * expiry renders 90:00) — the QR sign-in expiry convention.
 */
fun formatMinutesSecondsCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
