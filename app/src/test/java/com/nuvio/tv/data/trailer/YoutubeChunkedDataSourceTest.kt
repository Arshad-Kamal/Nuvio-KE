package com.nuvio.tv.data.trailer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Regression for the chunk under-count: the first read after a chunk re-open was not added to
 * bytesReadInChunk, so the second chunk always looked short and the stream ended at ~2 chunks.
 */
@UnstableApi
class YoutubeChunkedDataSourceTest {

    private val chunkSize = 8L

    /** Serves [content] sequentially, at most [chunkSize] bytes per open (one YouTube range request). */
    private class FakeChunkUpstream(
        private val content: ByteArray,
        private val chunkSize: Long,
    ) : DataSource {
        var opens = 0
        private var cursor = 0
        private var remainingInChunk = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opens++
            remainingInChunk = minOf(chunkSize.toInt(), content.size - cursor)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remainingInChunk == 0) return C.RESULT_END_OF_INPUT
            val n = minOf(length, remainingInChunk)
            System.arraycopy(content, cursor, buffer, offset, n)
            cursor += n
            remainingInChunk -= n
            return n
        }

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }

    private fun googleVideoUri(): Uri {
        val uri = mockk<Uri>()
        val builder = mockk<Uri.Builder>()
        every { uri.host } returns "rr1---sn.googlevideo.com"
        every { uri.buildUpon() } returns builder
        every { builder.appendQueryParameter(any(), any()) } returns builder
        every { builder.build() } returns uri
        return uri
    }

    private fun readAll(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(3) // smaller than a chunk, so each chunk takes several reads
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `serves three full chunks plus a short tail`() {
        val content = ByteArray(3 * chunkSize.toInt() + 3) { it.toByte() }
        val upstream = FakeChunkUpstream(content, chunkSize)
        val source = YoutubeChunkedDataSourceFactory.YoutubeChunkedDataSource(upstream, chunkSize)

        source.open(DataSpec.Builder().setUri(googleVideoUri()).build())
        val received = readAll(source)

        assertArrayEquals(content, received)
        assertEquals(4, upstream.opens)
    }

    @Test
    fun `stops after an exactly-full final chunk`() {
        val content = ByteArray(3 * chunkSize.toInt()) { it.toByte() }
        val upstream = FakeChunkUpstream(content, chunkSize)
        val source = YoutubeChunkedDataSourceFactory.YoutubeChunkedDataSource(upstream, chunkSize)

        source.open(DataSpec.Builder().setUri(googleVideoUri()).build())

        assertArrayEquals(content, readAll(source))
    }
}
