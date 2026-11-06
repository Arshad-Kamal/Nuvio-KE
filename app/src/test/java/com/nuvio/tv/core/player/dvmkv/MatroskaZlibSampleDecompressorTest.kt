package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.ParserException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class MatroskaZlibSampleDecompressorTest {

    @Test
    fun `decompress restores zlib-compressed subtitle payload`() {
        val original = "Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello".toByteArray()
        val compressed = deflate(original)

        val decompressor = MatroskaZlibSampleDecompressor()
        val decompressed = decompressor.decompress(compressed, 0, compressed.size)

        assertEquals(original.size, decompressed.limit())
        assertArrayEquals(original, decompressed.data.copyOfRange(0, decompressed.limit()))
    }

    @Test
    fun `decompress reuses inflater and grows output buffer across calls`() {
        val first = "First".toByteArray()
        val second = "Second subtitle line with more bytes".toByteArray()

        val decompressor = MatroskaZlibSampleDecompressor()
        val firstOut =
            decompressor.decompress(deflate(first), 0, deflate(first).size).let { sample ->
                sample.data.copyOfRange(0, sample.limit())
            }
        val secondOut =
            decompressor.decompress(deflate(second), 0, deflate(second).size).let { sample ->
                sample.data.copyOfRange(0, sample.limit())
            }

        assertArrayEquals(first, firstOut)
        assertArrayEquals(second, secondOut)
    }

    @Test
    fun `decompress rejects a sample inflating past the 64 MiB cap`() {
        val bomb = deflateZeros(65 * 1024 * 1024)

        val decompressor = MatroskaZlibSampleDecompressor()
        assertThrows(ParserException::class.java) {
            decompressor.decompress(bomb, 0, bomb.size)
        }
    }

    /** Streams [size] zero bytes through a Deflater without holding them in one array. */
    private fun deflateZeros(size: Int): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val zeros = ByteArray(1 shl 20)
        val scratch = ByteArray(64 * 1024)
        val output = ByteArrayOutputStream()
        var remaining = size
        while (remaining > 0) {
            val count = minOf(zeros.size, remaining)
            deflater.setInput(zeros, 0, count)
            while (!deflater.needsInput()) {
                output.write(scratch, 0, deflater.deflate(scratch))
            }
            remaining -= count
        }
        deflater.finish()
        while (!deflater.finished()) {
            output.write(scratch, 0, deflater.deflate(scratch))
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream(input.size)
        val scratch = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(scratch)
            if (count > 0) {
                output.write(scratch, 0, count)
            }
        }
        deflater.end()
        return output.toByteArray()
    }
}
