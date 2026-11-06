package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format-capability F2/G1: the engine-neutral chapter model and its ExoPlayer read-back. */
class PlaybackChapterTest {

    @Test
    fun `normalized drops negative starts, blanks empty titles and caps the list`() {
        val many = (0 until PlaybackChapter.MAX_CHAPTERS + 5).map { PlaybackChapter(it.toLong(), " ") }
        val normalized = PlaybackChapter.normalized(listOf(PlaybackChapter(-1L, "neg")) + many.reversed())
        assertEquals(PlaybackChapter.MAX_CHAPTERS, normalized.size)
        assertEquals(PlaybackChapter(0L, null), normalized.first())
        assertTrue(normalized.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `normalized trims and cuts long titles`() {
        val long = "x".repeat(PlaybackChapter.MAX_TITLE_CHARS + 50)
        val normalized = PlaybackChapter.normalized(listOf(PlaybackChapter(0L, "  $long")))
        assertEquals(PlaybackChapter.MAX_TITLE_CHARS, normalized.single().title?.length)
    }

    @Test
    fun `chaptersOf reads chapters back from a track format's metadata`() {
        val chapters = listOf(PlaybackChapter(0L, "One"), PlaybackChapter(5_000L, "Two"))
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
        val video = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setMetadata(Metadata(chapters))
            .build()
        assertEquals(emptyList<PlaybackChapter>(), chaptersOf(tracksOf(audio)))
        assertEquals(chapters, chaptersOf(tracksOf(audio, video)))
    }

    private fun tracksOf(vararg formats: Format) = Tracks(
        formats.map { Tracks.Group(TrackGroup(it), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)) }
    )
}
