package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L1-26: a switched-to stream's filename comes from its own hint or URL, never
 * from the launch stream's navigationArgs.filename (which keys the AFR cache,
 * mime inference and subtitle hashing for the wrong file).
 */
class SwitchedStreamFilenameTest {

    @Test
    fun `behavior hint wins over the url`() {
        assertEquals(
            "Movie.2160p.Remux.mkv",
            switchedStreamFilename("Movie.2160p.Remux.mkv", "https://host/dl/other.mp4")
        )
    }

    @Test
    fun `no hint falls back to the url's last path segment without query`() {
        assertEquals(
            "Movie.1080p.mkv",
            switchedStreamFilename(null, "https://host/dl/Movie.1080p.mkv?token=abc")
        )
    }

    @Test
    fun `no hint and no file-like url segment yields null`() {
        assertNull(switchedStreamFilename(null, "https://host/resolve/12345"))
        assertNull(switchedStreamFilename(null, "https://host/"))
    }

    @Test
    fun `no hint and no url yields null`() {
        assertNull(switchedStreamFilename(null, null))
    }
}
