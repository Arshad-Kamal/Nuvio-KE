package com.nuvio.tv.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 31 S7c (Q11/Q13): Settings ranges equal the store clamps; one subtitle-size default. */
class PlaybackRangesTest {
    @Test
    fun `chunk sizes stay inside the setter clamp`() {
        val sizes = PlayerSettings.PARALLEL_CHUNK_SIZES_KB
        assertEquals(PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_KB, sizes.first())
        assertEquals(PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_KB, sizes.last())
        assertEquals(sizes.sorted().distinct(), sizes)
    }

    @Test
    fun `buffer slider bounds are the approved store clamps`() {
        assertEquals(30_000, BufferSettings.MAX_BUFFER_FOR_PLAYBACK_MS)
        assertEquals(60_000, BufferSettings.MAX_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        assertTrue(BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS in
            BufferSettings.MIN_BUFFER_FOR_PLAYBACK_MS..BufferSettings.MAX_BUFFER_FOR_PLAYBACK_MS)
        assertTrue(BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS in
            BufferSettings.MIN_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS..BufferSettings.MAX_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }

    @Test
    fun `setters clamp to the shared bounds`() = runTest {
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store)

        settings.setBufferForPlaybackMs(60_000)
        settings.setBufferForPlaybackAfterRebufferMs(120_000)
        settings.setParallelChunkSizeKb(128 * 1024)
        val read = settings.playerSettings.first()
        assertEquals(BufferSettings.MAX_BUFFER_FOR_PLAYBACK_MS, read.bufferSettings.bufferForPlaybackMs)
        assertEquals(BufferSettings.MAX_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, read.bufferSettings.bufferForPlaybackAfterRebufferMs)
        assertEquals(PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_KB, read.parallelChunkSizeKb)
    }

    @Test
    fun `subtitle size has one default of 100`() = runTest {
        assertEquals(100, SubtitleStyleSettings().size)
        val store = TestPreferencesDataStore()
        val settings = playerSettingsOver(store)
        assertEquals(100, settings.playerSettings.first().subtitleStyle.size)
    }
}
