package com.nuvio.tv.core.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T24 (upstream-sync Register A2 P21): an adaptive stream's opened resources are a
 * playlist and segments, so their lengths are never the title's file size (the playlist's
 * few KB made the placeholder gate reject every long HLS title).
 */
class PlaybackByteCounterFileSizeTest {

    @Before
    fun setUp() = PlaybackByteCounter.reset()

    @After
    fun tearDown() = PlaybackByteCounter.reset()

    @Test
    fun `progressive stream reports the file's own length, not the largest`() {
        PlaybackByteCounter.recordContentLength("https://host/movie.mkv", 40_000_000_000L)
        PlaybackByteCounter.recordContentLength("https://host/stale.mkv", 50_000_000_000L)

        assertEquals(40_000_000_000L, PlaybackByteCounter.mediaFileSizeFor("https://host/movie.mkv", isAdaptiveStream = false))
    }

    @Test
    fun `file without a declared length does not borrow a subtitle's length`() {
        PlaybackByteCounter.recordContentLength("https://subs/movie.srt", 80_000L)

        assertNull(PlaybackByteCounter.mediaFileSizeFor("https://host/movie.mkv", isAdaptiveStream = false))
    }

    @Test
    fun `no url to match falls back to the largest resource`() {
        PlaybackByteCounter.recordContentLength("https://host/movie.mkv", 40_000_000_000L)
        PlaybackByteCounter.recordContentLength("https://subs/movie.srt", 80_000L)

        assertEquals(40_000_000_000L, PlaybackByteCounter.mediaFileSizeFor(null, isAdaptiveStream = false))
    }

    @Test
    fun `adaptive stream has no file size even when its playlist length was recorded`() {
        PlaybackByteCounter.recordContentLength("https://host/master.m3u8", 2_048L)
        PlaybackByteCounter.recordContentLength("https://host/seg1.ts", 6_000_000L)

        assertNull(PlaybackByteCounter.mediaFileSizeFor("https://host/master.m3u8", isAdaptiveStream = true))
    }

    @Test
    fun `non-positive lengths are not recorded`() {
        PlaybackByteCounter.recordContentLength("https://host/movie.mkv", 0L)

        assertNull(PlaybackByteCounter.mediaFileSizeFor("https://host/movie.mkv", isAdaptiveStream = false))
    }
}
