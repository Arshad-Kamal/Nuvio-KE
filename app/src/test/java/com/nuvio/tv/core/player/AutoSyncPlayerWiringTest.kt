package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.autosync.AutoSyncAnalysisOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 108 S3b/S5: the pure decisions of the player-side AutoSync wiring and the R1 retry timeout. */
class AutoSyncPlayerWiringTest {

    @Test
    fun onlyAConfirmedNoFitTellsTheViewer() {
        assertTrue(autoSyncShowsNoFitNotice(AutoSyncAnalysisOutcome.NO_FIT))
        assertFalse(autoSyncShowsNoFitNotice(AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS))
        assertFalse(autoSyncShowsNoFitNotice(AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE))
        assertFalse(autoSyncShowsNoFitNotice(AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE))
        assertFalse(autoSyncShowsNoFitNotice(AutoSyncAnalysisOutcome.INCONCLUSIVE))
        assertFalse(autoSyncShowsNoFitNotice(null))
    }

    @Test
    fun mpvRetimedCopyIsNamedByItsSubtitleAndContent() {
        val prefix = mpvAutoSyncedFilePrefix("4421087|https://subs.example/a.srt")
        assertTrue(prefix.startsWith("mpv_autosync_") && prefix.endsWith("_"))
        // Another subtitle never shares the prefix, so two subtitles' copies never share a name.
        assertNotEquals(prefix, mpvAutoSyncedFilePrefix("4421087|https://subs.example/b.srt"))

        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        assertTrue(mpvAutoSyncedFileName(prefix, srt).endsWith(".srt"))
        assertTrue(mpvAutoSyncedFileName(prefix, "\nWEBVTT\n\n00:01.000 --> 00:02.000\nHi\n").endsWith(".vtt"))
        assertTrue(mpvAutoSyncedFileName(prefix, "[Script Info]\n[Events]\nDialogue: x").endsWith(".ass"))
        // The retimer's rule: "[Events]" inside a cue line is SRT text, not an ASS section.
        assertTrue(mpvAutoSyncedFileName(prefix, "1\n00:00:01,000 --> 00:00:02,000\nSee [Events] below\n").endsWith(".srt"))
        // Same content, same name (mpv's cached sub-add reuses the track); new content, new name.
        assertEquals(mpvAutoSyncedFileName(prefix, srt), mpvAutoSyncedFileName(prefix, srt))
        assertNotEquals(mpvAutoSyncedFileName(prefix, srt), mpvAutoSyncedFileName(prefix, srt + "\n"))
    }

    @Test
    fun trustAllRetryGetsWhatIsLeftOfTheOuterCallTimeout() {
        assertNull(PlayerPlaybackNetworking.remainingCallTimeoutNanos(outerTimeoutNanos = 0L, elapsedNanos = 5L))
        assertEquals(
            6_000L,
            PlayerPlaybackNetworking.remainingCallTimeoutNanos(outerTimeoutNanos = 15_000L, elapsedNanos = 9_000L)
        )
        assertEquals(
            -1L,
            PlayerPlaybackNetworking.remainingCallTimeoutNanos(outerTimeoutNanos = 15_000L, elapsedNanos = 15_001L)
        )
    }
}
