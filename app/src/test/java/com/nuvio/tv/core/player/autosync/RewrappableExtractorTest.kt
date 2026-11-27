@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.player.autosync

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import com.nuvio.tv.core.player.RewrappableExtractor
import com.nuvio.tv.core.player.replaceInnermostExtractor
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Upstream 747ce832b's test: the libass swap keeps AutoSync's observer (plain case: PlayerLibassCompatTest). */
class RewrappableExtractorTest {
    @Test
    fun replacingInnermostExtractorKeepsAutoSyncObserver() {
        val original = FakeExtractor()
        val wrapped = AutoSyncExtractorsFactory(
            delegate = ExtractorsFactory { arrayOf(original) },
            sourceKey = "https://example.test/video.mkv",
        ).createExtractors().single()
        val replacement = FakeExtractor()

        val result = wrapped.replaceInnermostExtractor(replacement)

        assertTrue(result is RewrappableExtractor)
        assertNotSame(replacement, result)
        assertSame(replacement, result.underlyingImplementation)
    }

    private class FakeExtractor : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = false
        override fun init(output: ExtractorOutput) = Unit
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }
}
