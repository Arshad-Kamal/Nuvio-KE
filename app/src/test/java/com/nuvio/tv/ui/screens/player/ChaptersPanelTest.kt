package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.PlaybackChapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 25 U6f/U6g: which chapter is playing, untitled chapter names, seek-bar mark positions. */
class ChaptersPanelTest {

    private val chapters = listOf(
        PlaybackChapter(0L, "Opening"),
        PlaybackChapter(60_000L, null),
        PlaybackChapter(120_000L, "Finale")
    )

    @Test
    fun currentChapterIsTheLastStartedOne() {
        assertEquals(0, currentChapterIndex(chapters, 0L))
        assertEquals(0, currentChapterIndex(chapters, 59_999L))
        assertEquals(1, currentChapterIndex(chapters, 60_000L))
        assertEquals(2, currentChapterIndex(chapters, 10_000_000L))
    }

    @Test
    fun beforeTheFirstChapterOrWithoutChaptersThereIsNone() {
        assertEquals(-1, currentChapterIndex(listOf(PlaybackChapter(5_000L, "Late")), 1_000L))
        assertEquals(-1, currentChapterIndex(emptyList(), 1_000L))
    }

    @Test
    fun marksSitAtChapterStartsInsideTheTimeline() {
        assertEquals(listOf(0.25f, 0.5f), chapterMarkFractions(chapters, 240_000L))
    }

    @Test
    fun startsAtZeroAtOrPastTheEndAndRepeatsMarkNothingExtra() {
        val edge = listOf(
            PlaybackChapter(0L, null),
            PlaybackChapter(50_000L, null),
            PlaybackChapter(50_000L, "Same start"),
            PlaybackChapter(100_000L, null),
            PlaybackChapter(150_000L, null)
        )
        assertEquals(listOf(0.5f), chapterMarkFractions(edge, 100_000L))
    }

    @Test
    fun noDurationYetMeansNoMarks() {
        assertEquals(emptyList<Float>(), chapterMarkFractions(chapters, 0L))
        assertEquals(emptyList<Float>(), chapterMarkFractions(emptyList(), 100_000L))
    }

    @Test
    fun untitledChaptersAreNamedByTheirNumber() {
        assertEquals("Opening", chapterLabel(chapters[0], 0) { "Chapter $it" })
        assertEquals("Chapter 2", chapterLabel(chapters[1], 1) { "Chapter $it" })
    }
}
