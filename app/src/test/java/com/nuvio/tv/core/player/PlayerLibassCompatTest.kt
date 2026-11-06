package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor as StockMatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T28 P33: progressive loading calls createExtractors(uri, responseHeaders) (Media3 1.8.0
 * BundledExtractorsAdapter.init); the libass wrapper must forward it, not fall back to the no-arg
 * overload and lose the delegate's URI/Content-Type ordering.
 */
class PlayerLibassCompatTest {

    private class RecordingFactory(private val result: Array<Extractor>) : ExtractorsFactory {
        val calls = mutableListOf<String>()
        var lastUri: Uri? = null

        override fun createExtractors(): Array<Extractor> {
            calls += "no-arg"
            return result
        }

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
            calls += "uri"
            lastUri = uri
            return result
        }
    }

    @Test
    fun `the uri overload reaches the delegate's uri overload and still swaps Matroska`() {
        val other = mockk<Extractor>()
        val delegate = RecordingFactory(
            arrayOf(other, StockMatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED))
        )
        val wrapped = delegate.withAssMkvSupportCompat(mockk(relaxed = true), mockk(relaxed = true))
        val uri = mockk<Uri>()

        val extractors = wrapped.createExtractors(uri, mapOf("Content-Type" to listOf("video/x-matroska")))

        assertEquals(listOf("uri"), delegate.calls)
        assertSame(uri, delegate.lastUri)
        assertSame(other, extractors[0])
        assertTrue(extractors[1] is NuvioAssMatroskaExtractor)
    }

    /**
     * Spec 21 N1: the HDR10-base-layer decision rides on the DV transformer, so the libass swap
     * must carry the factory's transformer over to the ASS-aware extractor.
     */
    @Test
    fun `the libass swap keeps the DV factory's transformer and its HDR10-base decision`() {
        fun swappedTransformer(hdr10BaseLayerMode: Boolean) = (DolbyVisionExtractorsFactory(
            delegate = ExtractorsFactory {
                arrayOf<Extractor>(StockMatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED))
            },
            config = DolbyVisionConversionConfig(active = false),
            hdr10BaseLayerMode = hdr10BaseLayerMode
        ).withAssMkvSupportCompat(mockk(relaxed = true), mockk(relaxed = true))
            .createExtractors().single() as NuvioAssMatroskaExtractor).dolbyVisionSampleTransformer!!

        assertTrue(swappedTransformer(true).isHdr10BaseLayerModeActive)
        assertFalse(swappedTransformer(false).isHdr10BaseLayerModeActive)
    }

    @Test
    fun `the no-arg overload still reaches the delegate's no-arg overload`() {
        val delegate = RecordingFactory(emptyArray())
        delegate.withAssMkvSupportCompat(mockk(relaxed = true), mockk(relaxed = true)).createExtractors()
        assertEquals(listOf("no-arg"), delegate.calls)
    }
}
