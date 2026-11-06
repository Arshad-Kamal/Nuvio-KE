package com.nuvio.tv.core.player

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * L2-22: a FileData element seen before (or without) its FileName/FileMimeType used to
 * throw from requireNotNull, turning one malformed font attachment into a fatal source
 * error. It must now be skipped.
 */
class NuvioAssMatroskaExtractorAttachmentTest {

    @Test
    fun fileDataWithoutNameOrMimeIsSkipped() {
        val assHandler = mockk<AssHandler>(relaxed = true)
        val extractor = NuvioAssMatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, assHandler)
        val input = mockk<ExtractorInput>(relaxed = true)

        extractor.handleAttachmentData(contentSize = 5, input = input)

        verify(exactly = 1) { input.skipFully(5) }
        verify(exactly = 0) { assHandler.addFont(any(), any()) }
    }
}
