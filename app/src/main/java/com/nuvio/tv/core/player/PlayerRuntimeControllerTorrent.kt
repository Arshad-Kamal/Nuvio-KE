package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.core.torrent.formatTorrentMB
import com.nuvio.tv.core.torrent.formatTorrentSpeed
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PlayerTorrent"

/**
 * Starts a torrent stream via TorrServer. Returns the HTTP stream URL for ExoPlayer.
 * TorrServer handles all piece management, buffering, and seeking internally.
 */
internal suspend fun PlayerRuntimeController.startTorrentStream(
    infoHash: String,
    fileIdx: Int?,
    filename: String? = null,
    trackers: List<String> = emptyList()
): String {
    // Reserve the session before the first suspension so this player's stop/release reach
    // the torrent this start adds even if it fails or is cancelled mid-start.
    val session = torrentService.beginSession()
    torrentSession = session
    isTorrentStream = true
    currentInfoHash = infoHash
    currentFileIdx = fileIdx

    setLoadingStatus(
        phase = "torrent_starting_engine",
        message = context.getString(com.nuvio.tv.R.string.player_torrent_starting_engine),
        showOverlay = true
    )
    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = context.getString(com.nuvio.tv.R.string.player_torrent_starting_engine),
            loadingProgress = null,
            isTorrentStream = true
        )
    }

    val effectiveFilename = filename ?: currentFilename
    return torrentService.startStream(session, infoHash, fileIdx, effectiveFilename, trackers)
}

/**
 * Stops the current torrent stream and cleans up state. With no session (none started, or
 * handed off to an external player) it only resets the local state.
 */
internal fun PlayerRuntimeController.stopTorrentStream() {
    torrentStreamJob?.cancel()
    torrentStreamJob = null
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = null

    torrentSession?.let { torrentService.stopStream(it) }

    isTorrentStream = false
    currentInfoHash = null
    currentFileIdx = null
}

/**
 * Player-session end: stop the TorrServer process if this session played a torrent (and no
 * newer session has started since; see TorrentService.beginSession), so it
 * does not hold RAM for the app's lifetime. Non-blocking; TorrentService.startStream()
 * restarts the server lazily on the next torrent play.
 */
internal fun shutdownTorrServerAfterSession(session: Long?, torrentService: TorrentService) {
    if (session != null) torrentService.shutdown(session)
}

/**
 * Collects TorrentService state and maps it to PlayerUiState fields.
 */
internal fun PlayerRuntimeController.observeTorrentState() {
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = scope.launch {
        torrentService.state.collectLatest { torrentState ->
            when (torrentState) {
                is TorrentState.Idle -> { /* No-op */ }

                is TorrentState.Connecting -> {
                    // A new torrent: the Stats P2P section must not show the previous one's numbers.
                    _uiState.update {
                        it.copy(
                            torrentDownloadSpeed = 0L,
                            torrentUploadSpeed = 0L,
                            torrentPeers = 0,
                            torrentSeeds = 0,
                            torrentTotalProgress = 0f,
                            torrentSizeBytes = 0L,
                            torrentFileNumber = 0,
                            torrentFileCount = 0
                        )
                    }
                    if (!hasRenderedFirstFrame) {
                        recordLoadingDiagnosticEvent(
                            phase = "torrent_connecting_peers",
                            message = context.getString(com.nuvio.tv.R.string.player_torrent_connecting_peers)
                        )
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = context.getString(com.nuvio.tv.R.string.player_torrent_connecting_peers),
                                loadingProgress = null,
                                torrentBufferingMessage = null
                            )
                        }
                    }
                }

                is TorrentState.Streaming -> {
                    val speed = formatTorrentSpeed(context, torrentState.downloadSpeed)
                    val peerInfo = context.getString(com.nuvio.tv.R.string.player_torrent_peer_info, torrentState.seeds, torrentState.peers)
                    val mbLoaded = formatTorrentMB(context, torrentState.preloadedBytes)
                    val statsHidden = _uiState.value.hideTorrentStats

                    if (!hasRenderedFirstFrame) {
                        // Initial load: show preloaded MB with progress bar
                        // TorrServer preloads ~5MB before streaming starts
                        val preloadTarget = 5_242_880L // 5MB
                        val progress = (torrentState.preloadedBytes.toFloat() / preloadTarget).coerceIn(0f, 1f)
                        val message = if (statsHidden) null else context.getString(com.nuvio.tv.R.string.player_torrent_buffered_status, mbLoaded, peerInfo, speed)
                        recordLoadingDiagnosticEvent(
                            phase = "torrent_preloading",
                            message = message,
                            progress = progress,
                            detail = "${torrentState.seeds}/${torrentState.peers}"
                        )
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = message,
                                loadingProgress = progress,
                                torrentDownloadSpeed = torrentState.downloadSpeed,
                                torrentUploadSpeed = torrentState.uploadSpeed,
                                torrentPeers = torrentState.peers,
                                torrentSeeds = torrentState.seeds,
                                torrentTotalProgress = torrentState.totalProgress,
                                torrentSizeBytes = torrentState.torrentSize,
                                torrentFileNumber = torrentState.fileNumber,
                                torrentFileCount = torrentState.fileCount,
                                torrentBufferingMessage = null
                            )
                        }
                    } else {
                        // During playback: update stats, rebuffer message is
                        // handled by the progress loop in PlaybackEvents
                        val message = if (statsHidden) null else context.getString(com.nuvio.tv.R.string.player_torrent_status, peerInfo, speed)
                        _uiState.update {
                            it.copy(
                                loadingProgress = null,
                                torrentDownloadSpeed = torrentState.downloadSpeed,
                                torrentUploadSpeed = torrentState.uploadSpeed,
                                torrentPeers = torrentState.peers,
                                torrentSeeds = torrentState.seeds,
                                torrentTotalProgress = torrentState.totalProgress,
                                torrentSizeBytes = torrentState.torrentSize,
                                torrentFileNumber = torrentState.fileNumber,
                                torrentFileCount = torrentState.fileCount,
                                torrentBufferingMessage = message
                            )
                        }
                    }
                }

                is TorrentState.Error -> {
                    Log.e(TAG, "Torrent error: ${torrentState.message}")
                    _uiState.update {
                        it.copy(
                            error = context.getString(com.nuvio.tv.R.string.player_error_torrent, torrentState.message),
                            showLoadingOverlay = false,
                            torrentBufferingMessage = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Launches a torrent stream for source/episode stream switching.
 */
internal fun PlayerRuntimeController.launchTorrentSourceStream(
    stream: Stream,
    infoHash: String,
    loadSavedProgress: Boolean
) {
    currentTorrentSources = stream.sources
    launchTorrentStream(
        infoHash = infoHash,
        fileIdx = stream.getEffectiveFileIdx(),
        filename = stream.behaviorHints?.filename,
        trackers = stream.sources
            ?.filter { it.startsWith("tracker:") }
            ?.map { it.removePrefix("tracker:") }
            ?: emptyList(),
        loadSavedProgress = loadSavedProgress
    )
}

/**
 * The one torrent launch sequence, for the initial start and every source/episode switch:
 * cancels a previous launch, starts TorrServer, then prepares playback of its local URL.
 * Resume works as for HTTP (TorrServer serves Range requests), so [loadSavedProgress] is
 * the caller's start-from-beginning choice.
 */
internal fun PlayerRuntimeController.launchTorrentStream(
    infoHash: String,
    fileIdx: Int?,
    filename: String?,
    trackers: List<String>,
    loadSavedProgress: Boolean
) {
    torrentStreamJob?.cancel()
    torrentStreamJob = scope.launch {
        try {
            observeTorrentState()
            val localUrl = startTorrentStream(
                infoHash = infoHash,
                fileIdx = fileIdx,
                filename = filename,
                trackers = trackers
            )

            currentStreamUrl = localUrl
            currentHeaders = emptyMap()
            currentStreamMimeType = null

            preparePlaybackBeforeStart(
                url = localUrl,
                headers = emptyMap(),
                loadSavedProgress = loadSavedProgress
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start torrent stream", e)
            _uiState.update {
                it.copy(
                    error = context.getString(
                        R.string.player_error_failed_start_torrent,
                        e.message ?: context.getString(R.string.error_unknown)
                    ),
                    showLoadingOverlay = false,
                    loadingProgress = null
                )
            }
        }
    }
}
