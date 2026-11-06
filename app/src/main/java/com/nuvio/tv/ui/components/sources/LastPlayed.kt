package com.nuvio.tv.ui.components.sources

import androidx.compose.runtime.Immutable
import com.nuvio.tv.data.local.CachedStreamLink
import com.nuvio.tv.domain.model.Stream

/** What the stream link cache remembers of the last played source, enough to find its row. */
@Immutable
data class LastPlayed(
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val filename: String?,
    val videoSize: Long?
)

/**
 * Index of the row to mark "Last played" in [streams], or -1: the first exact link match, else
 * the first torrent match (info hash, and file index when one was saved), else the first row with
 * the same file name and size (a resolved link can change between visits; the file does not).
 * One row at most, so the same file listed by two addons is marked once.
 */
internal fun lastPlayedIndex(streams: List<Stream>, lastPlayed: LastPlayed?): Int {
    if (lastPlayed == null) return -1
    val url = lastPlayed.url?.takeIf { it.isNotBlank() }
    val infoHash = lastPlayed.infoHash?.takeIf { it.isNotBlank() }
    val filename = lastPlayed.filename?.takeIf { it.isNotBlank() }
    val videoSize = lastPlayed.videoSize
    val matchers = listOfNotNull<(Stream) -> Boolean>(
        url?.let { { stream -> stream.getStreamUrl() == it } },
        infoHash?.let {
            { stream ->
                stream.getEffectiveInfoHash().equals(it, ignoreCase = true) &&
                    (lastPlayed.fileIdx == null || stream.getEffectiveFileIdx() == lastPlayed.fileIdx)
            }
        },
        if (filename != null && videoSize != null) {
            { stream -> stream.behaviorHints?.filename == filename && stream.behaviorHints.videoSize == videoSize }
        } else {
            null
        }
    )
    for (matches in matchers) {
        val index = streams.indexOfFirst(matches)
        if (index >= 0) return index
    }
    return -1
}

/** The parts of a cached last link that find its source again. */
internal fun CachedStreamLink.toLastPlayed(): LastPlayed = LastPlayed(
    url = url,
    infoHash = infoHash,
    fileIdx = fileIdx,
    filename = filename,
    videoSize = videoSize
)
