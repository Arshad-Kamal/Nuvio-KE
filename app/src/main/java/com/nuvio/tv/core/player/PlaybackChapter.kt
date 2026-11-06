package com.nuvio.tv.core.player

import androidx.media3.common.Metadata
import androidx.media3.common.Tracks

/**
 * One container chapter (format-capability F2/G1): its start on the media timeline and its
 * display title when the file names it. Engine-neutral. On ExoPlayer the vendored MKV extractor
 * appends these to every track's `Format.metadata` (the seam stock Media3 1.11 uses for its own
 * `Chapter` entries; 1.8.0 has no chapter type) and [chaptersOf] reads them back from
 * `Player.getCurrentTracks()`; on mpv they are built from the `chapter-list` property.
 */
data class PlaybackChapter(val startMs: Long, val title: String?) : Metadata.Entry {

    companion object {
        /** Upper bounds on what one file's chapters keep in memory; container data is untrusted. */
        const val MAX_CHAPTERS = 1000
        const val MAX_TITLE_CHARS = 256

        /**
         * Drops chapters with a negative start, trims titles (blank to null, cut at
         * [MAX_TITLE_CHARS]), sorts by start and caps the list at [MAX_CHAPTERS]. Both engines'
         * sources pass through here.
         */
        @JvmStatic
        fun normalized(chapters: List<PlaybackChapter>): List<PlaybackChapter> =
            chapters.asSequence()
                .filter { it.startMs >= 0L }
                .map { it.copy(title = it.title?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotEmpty)) }
                .sortedBy { it.startMs }
                .take(MAX_CHAPTERS)
                .toList()
    }
}

/** Chapters carried by the first track whose format has any; empty when the source has none. */
fun chaptersOf(tracks: Tracks): List<PlaybackChapter> {
    for (group in tracks.groups) {
        for (i in 0 until group.length) {
            val metadata = group.getTrackFormat(i).metadata ?: continue
            val chapters = (0 until metadata.length()).mapNotNull { metadata.get(it) as? PlaybackChapter }
            if (chapters.isNotEmpty()) return chapters
        }
    }
    return emptyList()
}
