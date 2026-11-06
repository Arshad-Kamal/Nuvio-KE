package com.nuvio.tv.core.torrent

import androidx.compose.runtime.Immutable

@Immutable
sealed class TorrentState {
    data object Idle : TorrentState()
    data object Connecting : TorrentState()

    data class Streaming(
        val localUrl: String,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        // Fraction (0..1) of the whole torrent downloaded (TorrServerStats.totalProgress).
        val totalProgress: Float,
        val preloadedBytes: Long = 0L,
        // Whole torrent size and the streamed file's 1-based position in its file list (P23);
        // 0 until the first stats poll answers.
        val torrentSize: Long = 0L,
        val fileNumber: Int = 0,
        val fileCount: Int = 0
    ) : TorrentState()

    data class Error(val message: String) : TorrentState()
}
