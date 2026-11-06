package com.nuvio.tv.ui.screens.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X17 pin: the pure helpers behind the stream request id vs playback videoId split on the
 * Stream screen. They take test-chosen ids, so which ids the ViewModel passes is not pinned.
 */
class StreamRequestIdTest {

    @Test
    fun `route request id wins over the video id`() {
        assertEquals("yt_id:v1", resolveStreamRequestId("yt_id:v1", "yt_channel:abc"))
    }

    @Test
    fun `absent or blank route request id falls back to the video id`() {
        assertEquals("tt1:1:2", resolveStreamRequestId(null, "tt1:1:2"))
        assertEquals("tt1", resolveStreamRequestId("  ", "tt1"))
    }

    @Test
    fun `request id follows the route arg while the link cache key uses the given video id`() {
        val videoId = "yt_channel:abc"

        val requestId = resolveStreamRequestId("yt_id:v1", videoId)
        val cacheKey = streamLinkCacheKey("movie", videoId)

        assertEquals("yt_id:v1", requestId)
        assertEquals("movie|yt_channel:abc", cacheKey)
    }

    @Test
    fun `link cache key format is lowercase type pipe video id`() {
        // Persisted key: must stay byte-identical across releases.
        assertEquals("series|tt1:1:2", streamLinkCacheKey("Series", "tt1:1:2"))
    }

    @Test
    fun `embedded lookup runs for a movie whose request id is not the meta id`() {
        // Regression (L5-25): gated on videoId (= meta id) this was false, so the
        // request-id match in the embedded lookup was unreachable.
        assertTrue(shouldAttemptEmbeddedMetaStreamLookup("mymeta", "movie", "mymeta-video1"))
    }

    @Test
    fun `embedded lookup is skipped when the request id belongs to the meta`() {
        assertFalse(shouldAttemptEmbeddedMetaStreamLookup("tt1", "series", "tt1:1:2"))
        assertFalse(shouldAttemptEmbeddedMetaStreamLookup("tt1", "movie", "tt1"))
    }

    @Test
    fun `embedded lookup always runs for type other and never without a meta id`() {
        assertTrue(shouldAttemptEmbeddedMetaStreamLookup("tt1", "other", "tt1"))
        assertFalse(shouldAttemptEmbeddedMetaStreamLookup(null, "movie", "x:1"))
        assertFalse(shouldAttemptEmbeddedMetaStreamLookup("meta", "", "x:1"))
    }
}
