package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

/** U5i P10 "Last played": which row the saved link marks. */
class LastPlayedIndexTest {

    @Test
    fun `no saved link marks nothing`() {
        assertEquals(-1, lastPlayedIndex(listOf(stream(url = URL_A)), null))
    }

    @Test
    fun `exact link wins over an earlier same-file row`() {
        val streams = listOf(
            stream(url = URL_B, filename = FILE, size = SIZE),
            stream(url = URL_A, filename = FILE, size = SIZE)
        )
        assertEquals(1, lastPlayedIndex(streams, saved(url = URL_A, filename = FILE, videoSize = SIZE)))
    }

    @Test
    fun `torrent matches by info hash, any case, and the saved file index`() {
        val streams = listOf(
            stream(infoHash = HASH, fileIdx = 1),
            stream(infoHash = HASH.uppercase(), fileIdx = 2)
        )
        assertEquals(1, lastPlayedIndex(streams, saved(infoHash = HASH, fileIdx = 2)))
        assertEquals(0, lastPlayedIndex(streams, saved(infoHash = HASH)))
        assertEquals(-1, lastPlayedIndex(streams, saved(infoHash = HASH, fileIdx = 3)))
    }

    @Test
    fun `player torrent record (blank url) matches a magnet-only row before a same-file row`() {
        val streams = listOf(
            stream(url = URL_B, filename = FILE, size = SIZE),
            stream(url = "magnet:?xt=urn:btih:$HASH&dn=movie")
        )
        assertEquals(1, lastPlayedIndex(streams, saved(url = "", infoHash = HASH, filename = FILE, videoSize = SIZE)))
    }

    @Test
    fun `exact link wins over an earlier info hash row`() {
        val streams = listOf(
            stream(infoHash = HASH),
            stream(url = URL_A, infoHash = HASH)
        )
        assertEquals(1, lastPlayedIndex(streams, saved(url = URL_A, infoHash = HASH)))
    }

    @Test
    fun `a changed link still matches by file name and size, both required`() {
        val streams = listOf(stream(url = URL_B, filename = FILE, size = SIZE))
        assertEquals(0, lastPlayedIndex(streams, saved(url = URL_A, filename = FILE, videoSize = SIZE)))
        assertEquals(-1, lastPlayedIndex(streams, saved(url = URL_A, filename = FILE)))
        assertEquals(-1, lastPlayedIndex(streams, saved(url = URL_A, filename = FILE, videoSize = SIZE + 1)))
    }

    @Test
    fun `the same file from two addons is marked once`() {
        val streams = listOf(
            stream(url = URL_A, filename = FILE, size = SIZE),
            stream(url = URL_B, filename = FILE, size = SIZE)
        )
        assertEquals(0, lastPlayedIndex(streams, saved(url = URL_C, filename = FILE, videoSize = SIZE)))
    }

    private fun saved(
        url: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        filename: String? = null,
        videoSize: Long? = null
    ) = LastPlayed(url = url, infoHash = infoHash, fileIdx = fileIdx, filename = filename, videoSize = videoSize)

    private fun stream(
        url: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        filename: String? = null,
        size: Long? = null
    ): Stream = Stream(
        name = "Example",
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = size,
            filename = filename
        ),
        addonName = "Example Addon",
        addonLogo = null
    )

    private companion object {
        const val URL_A = "https://a.example.net/play/1"
        const val URL_B = "https://b.example.net/play/1"
        const val URL_C = "https://c.example.net/play/1"
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        const val FILE = "Movie.2012.2160p.REMUX-GRP.mkv"
        const val SIZE = 62_500_000_000L
    }
}
