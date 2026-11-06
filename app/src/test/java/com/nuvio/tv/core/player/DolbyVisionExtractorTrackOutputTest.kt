package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * L3a-01/02: the MP4/TS DV track output. Runs on the JVM without the native
 * bridge, so samples are never rewritten; the assertions cover routing and the
 * TrackOutput sample slicing (size/offset), which are bridge-independent.
 */
@UnstableApi
class DolbyVisionExtractorTrackOutputTest {

    private val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()

    // Annex-B sample: start code + 2-byte HEVC NAL header + payload.
    private fun annexB(type: Int, vararg payload: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 0x01) +
            ByteArray(payload.size) { payload[it].toByte() }

    /** Wraps [extractor] through the factory and returns the wrapper (already init'ed). */
    private fun wrap(
        extractor: FakeMp4Extractor,
        sink: RecordingTrackOutput,
        config: DolbyVisionConversionConfig = DolbyVisionConversionConfig(active = false),
        stripDvRpu: Boolean = false,
    ): Extractor {
        val factory = DolbyVisionExtractorsFactory(
            delegate = ExtractorsFactory { arrayOf<Extractor>(extractor) },
            config = config,
            stripDvRpu = stripDvRpu,
            stripHdr10PlusSei = true
        )
        val wrapped = factory.createExtractors().single()
        assertNotSame("MP4 extractor must be wrapped", extractor, wrapped)
        wrapped.init(RecordingExtractorOutput(sink))
        return wrapped
    }

    private fun videoTrack(extractor: FakeMp4Extractor): TrackOutput =
        extractor.output.track(0, C.TRACK_TYPE_VIDEO)

    @Test
    fun sliceUsesSizeAndOffset_dropsOrphanPrefix_carriesNextSampleBytes() {
        val extractor = FakeMp4Extractor()
        val sink = RecordingTrackOutput()
        wrap(extractor, sink)
        val track = videoTrack(extractor).also { it.format(hevc) }

        val orphan = byteArrayOf(9, 9, 9)          // left by a load cancelled mid-sample
        val first = annexB(1, 0xAA, 0xAB)
        val second = annexB(1, 0xBB, 0xBC)
        val secondHead = second.copyOfRange(0, 4)  // next sample already started

        val chunk = orphan + first + secondHead
        track.sampleData(ParsableByteArray(chunk), chunk.size)
        track.sampleMetadata(1L, C.BUFFER_FLAG_KEY_FRAME, first.size, secondHead.size, null)

        assertArrayEquals(first, sink.bytes.toByteArray())
        assertEquals(listOf(first.size to 0), sink.samples)

        val secondTail = second.copyOfRange(4, second.size)
        track.sampleData(ParsableByteArray(secondTail), secondTail.size)
        track.sampleMetadata(2L, 0, second.size, 0, null)

        assertArrayEquals(first + second, sink.bytes.toByteArray())
        assertEquals(listOf(first.size to 0, second.size to 0), sink.samples)
    }

    @Test
    fun seekDiscardsBufferedPartialSample() {
        val extractor = FakeMp4Extractor()
        val sink = RecordingTrackOutput()
        val wrapped = wrap(extractor, sink)
        val track = videoTrack(extractor).also { it.format(hevc) }

        val partial = annexB(1, 0x11)
        track.sampleData(ParsableByteArray(partial), partial.size)
        wrapped.seek(0L, 0L)

        val sample = annexB(19, 0x22, 0x33)
        track.sampleData(ParsableByteArray(sample), sample.size)
        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertArrayEquals(sample, sink.bytes.toByteArray())
        assertEquals(1, extractor.seekCount)
    }

    @Test
    fun sampleStartedBeforeBuffering_passesThroughUntouched() {
        val extractor = FakeMp4Extractor()
        val sink = RecordingTrackOutput()
        wrap(extractor, sink)
        val track = videoTrack(extractor)

        // TS H265Reader writes bytes before it can emit the Format.
        val sample = annexB(32, 1, 2, 3) + annexB(1, 4, 5)
        track.sampleData(ParsableByteArray(sample.copyOfRange(0, 5)), 5)
        track.format(hevc)
        track.sampleData(ParsableByteArray(sample.copyOfRange(5, sample.size)), sample.size - 5)
        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertArrayEquals(sample, sink.bytes.toByteArray())
        assertEquals(listOf(sample.size to 0), sink.samples)
    }

    @Test
    fun sampleStartedBeforeBuffering_carriesNextHead_soNextSampleIsSliced() {
        val extractor = FakeMp4Extractor()
        val sink = RecordingTrackOutput()
        wrap(extractor, sink)
        val track = videoTrack(extractor)

        // H265Reader shape: head written before format(); each sampleMetadata reports
        // offset > 0 because the next access unit's start code is already written.
        val first = annexB(32, 1, 2, 3) + annexB(1, 4, 5)
        val second = annexB(1, 6, 7)
        val thirdHead = annexB(1, 8).copyOfRange(0, 4)
        track.sampleData(ParsableByteArray(first.copyOfRange(0, 5)), 5)
        track.format(hevc)
        val rest = first.copyOfRange(5, first.size) + second.copyOfRange(0, 4)
        track.sampleData(ParsableByteArray(rest), rest.size)
        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, first.size, 4, null)

        val secondRest = second.copyOfRange(4, second.size) + thirdHead
        track.sampleData(ParsableByteArray(secondRest), secondRest.size)
        track.sampleMetadata(1L, 0, second.size, thirdHead.size, null)

        // The second sample took the slicing path: emitted alone with offset 0, and the
        // third sample's head stays buffered (a passthrough would have forwarded it).
        assertArrayEquals(first + second, sink.bytes.toByteArray())
        assertEquals(listOf(first.size to 0, second.size to 0), sink.samples)
    }

    @Test
    fun nonHevcTrack_isNotBuffered() {
        val extractor = FakeMp4Extractor()
        val sink = RecordingTrackOutput()
        wrap(extractor, sink)
        val track = videoTrack(extractor)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())

        val bytes = byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0x02)
        track.sampleData(ParsableByteArray(bytes), bytes.size)

        assertArrayEquals(bytes, sink.bytes.toByteArray())
    }

    private fun dvFormat(codecs: String) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs(codecs)
        .build()

    /** Spec 21 N1: the MP4/TS path records the source DV profile on convert and DV strip, as MKV does. */
    @Test
    fun sourceProfile_recordedOnConvertAndDvStrip_notOnPlainHevc() {
        DolbyVisionConversionStats.reset()
        FakeMp4Extractor().let { extractor ->
            wrap(extractor, RecordingTrackOutput(), config = DolbyVisionConversionConfig(active = true))
            videoTrack(extractor).format(dvFormat("dvhe.07.06"))
        }
        assertEquals(7, DolbyVisionConversionStats.getLastSourceProfile())

        DolbyVisionConversionStats.reset()
        FakeMp4Extractor().let { extractor ->
            wrap(extractor, RecordingTrackOutput(), stripDvRpu = true)
            videoTrack(extractor).format(dvFormat("dvhe.08.06"))
        }
        assertEquals(8, DolbyVisionConversionStats.getLastSourceProfile())

        // A DV track that is neither converted nor stripped records nothing.
        DolbyVisionConversionStats.reset()
        FakeMp4Extractor().let { extractor ->
            wrap(extractor, RecordingTrackOutput())
            videoTrack(extractor).format(dvFormat("dvhe.08.06"))
        }
        assertNull(DolbyVisionConversionStats.getLastSourceProfile())

        // Conversion active but not for this profile (P8 is never converted): nothing either.
        DolbyVisionConversionStats.reset()
        FakeMp4Extractor().let { extractor ->
            wrap(extractor, RecordingTrackOutput(), config = DolbyVisionConversionConfig(active = true))
            videoTrack(extractor).format(dvFormat("dvhe.08.06"))
        }
        assertNull(DolbyVisionConversionStats.getLastSourceProfile())
    }

    /**
     * Spec 21 N1: the HDR10-base-layer decision rides on each Matroska extractor's transformer,
     * so two factories alive at once (a preload beside the current playback) never share it.
     */
    @Test
    fun hdr10BaseLayerMode_isPerFactory_notGlobal() {
        fun mkvTransformer(hdr10BaseLayerMode: Boolean) = (DolbyVisionExtractorsFactory(
            delegate = ExtractorsFactory {
                arrayOf<Extractor>(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED))
            },
            config = DolbyVisionConversionConfig(active = false),
            hdr10BaseLayerMode = hdr10BaseLayerMode
        ).createExtractors().single() as DvMatroskaExtractor).dolbyVisionSampleTransformer!!

        val on = mkvTransformer(true)
        val off = mkvTransformer(false)
        assertTrue(on.isHdr10BaseLayerModeActive)
        assertFalse(off.isHdr10BaseLayerModeActive)
    }

    /** Named so the factory's class-name routing treats it as an MP4 extractor. */
    private class FakeMp4Extractor : Extractor {
        lateinit var output: ExtractorOutput
        var seekCount = 0

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT

        override fun seek(position: Long, timeUs: Long) {
            seekCount++
        }

        override fun release() = Unit
    }

    private class RecordingExtractorOutput(private val video: TrackOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = video
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class RecordingTrackOutput : TrackOutput {
        val bytes = ByteArrayOutputStream()
        val samples = mutableListOf<Pair<Int, Int>>()

        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val buf = ByteArray(length)
            val read = input.read(buf, 0, length)
            if (read > 0) bytes.write(buf, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buf = ByteArray(length)
            data.readBytes(buf, 0, length)
            bytes.write(buf)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            samples += size to offset
        }
    }
}
