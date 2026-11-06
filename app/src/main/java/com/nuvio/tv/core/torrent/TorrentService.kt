package com.nuvio.tv.core.torrent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv")

// BitTorrent v1 btih: 40 hex chars or its 32-char base32 form.
private val INFO_HASH = Regex("[0-9a-fA-F]{40}|[a-zA-Z2-7]{32}")

@Singleton
class TorrentService @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val binary: TorrServerBinary,
    private val api: TorrServerApi,
    private val torrentSettings: TorrentSettings
) {
    companion object {
        private const val TAG = "TorrentService"
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    // Guards the session and teardown bookkeeping below. Never held across a suspension.
    private val lock = Any()

    // Serializes the start critical section (join teardown, start server, add, claim). A
    // superseded start drops the hash it just added; holding this until the claim orders that
    // drop before the next start's add, which may re-add the same hash.
    private val startMutex = Mutex()

    // Last issued session (see beginSession). stopStream/shutdown act only for this value.
    private var sessionGeneration = 0L
    // The session a start may still publish into; cleared when its owner stops it.
    private var openSession: Long? = null
    // The owner of sessionGeneration called shutdown (not only stopStream) on it.
    private var shutdownRequested = false
    private var currentHash: String? = null
    private var statsJob: Job? = null
    // Latest pending async teardown (torrent drop and/or server stop), chained after the
    // previous one. startStream joins it so a late drop or server stop never lands on the
    // stream it is about to create.
    private var teardownJob: Job? = null

    /**
     * Reserves a new torrent session and tears down the current torrent (async drop; the server
     * keeps running). Synchronous so the caller holds its session before its first suspension:
     * stopStream/shutdown with it then reach whatever its start adds, even when that start fails
     * or is cancelled. Every older session becomes stale, so a late teardown from a superseded
     * owner (e.g. an old screen cleared after a new torrent started) is a no-op.
     */
    fun beginSession(): Long = synchronized(lock) {
        sessionGeneration++
        openSession = sessionGeneration
        shutdownRequested = false
        teardownLocked(stopServer = false)
        sessionGeneration
    }

    /**
     * Starts streaming a torrent for [session] (from [beginSession]). Returns the local HTTP
     * URL for the player. Throws CancellationException when [session] is superseded or stopped
     * before the stream is published; any torrent it added is dropped on every failure path.
     *
     * The P2P consent gate for every caller (stream screen, in-player switches, failover,
     * auto-play): with P2P off it throws TorrentException before starting TorrServer or adding
     * anything. An [infoHash] that is not a btih also throws TorrentException.
     */
    suspend fun startStream(
        session: Long,
        infoHash: String,
        fileIdx: Int?,
        filename: String? = null,
        trackers: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        var hash: String? = null
        // binary.start() was entered and this start has not chained a server stop since.
        var serverMayBeUp = false
        try {
            if (!torrentSettings.settings.first().p2pEnabled) {
                throw TorrentException(appContext.getString(com.nuvio.tv.R.string.torrent_error_p2p_disabled))
            }
            val magnetLink = buildMagnetUri(infoHash, trackers)
            startMutex.withLock {
                synchronized(lock) { teardownJob }?.join()
                synchronized(lock) {
                    ensureOpenLocked(session)
                    _state.value = TorrentState.Connecting
                }

                // Lazily (re)starts TorrServer: idempotent when it is already up, and the
                // restart path after a shutdown() at the end of a player session.
                serverMayBeUp = true
                binary.start()

                // No magnet, local URL or file path in logs (release logs D too): they name the
                // content (F-9).
                Log.d(TAG, "Starting stream: trackers=${trackers.size} fileIdx=$fileIdx")
                // Non-cancellable so a caller cancelled mid-request still reaches the claim
                // below and drops what TorrServer added (bounded by the HTTP timeouts).
                withContext(NonCancellable) {
                    val added = api.addTorrent(magnetLink)
                        ?: throw TorrentException(appContext.getString(com.nuvio.tv.R.string.torrent_error_add_failed))
                    synchronized(lock) {
                        if (openSession != session) {
                            // Superseded or stopped while starting: drop our own torrent;
                            // never claim currentHash (it belongs to the newer session, if
                            // any). An owner shutdown that raced binary.start() may have
                            // missed the process, so stop the server again after the drop.
                            val stopServer = ownerShutDownLocked(session)
                            teardownJob = chainTeardownLocked(added, stopServer)
                            if (stopServer) serverMayBeUp = false
                            throw sessionEnded(session)
                        }
                        currentHash = added
                    }
                    hash = added
                }
            }
            val ownHash = checkNotNull(hash)

            // Resolve file index, then the stream URL. TorrServer handles all
            // buffering/piece management.
            val resolvedIdx = resolveFileIndex(ownHash, fileIdx, filename)
            val url = api.getStreamUrl(magnetLink, resolvedIdx)
            Log.d(TAG, "Stream URL ready: file id=$resolvedIdx")

            synchronized(lock) {
                if (openSession != session || currentHash != ownHash) throw sessionEnded(session)
                startStatsPollingLocked(ownHash, resolvedIdx)
                _state.value = TorrentState.Streaming(
                    localUrl = url,
                    downloadSpeed = 0,
                    uploadSpeed = 0,
                    peers = 0,
                    seeds = 0,
                    totalProgress = 0f
                )
            }
            url
        } catch (e: Throwable) {
            synchronized(lock) {
                // Drop the torrent this start added if this session still holds it (a stop or
                // a newer session already tore it down otherwise). Error is the terminal state
                // both consumer collectors implement; cancellation ends at Idle.
                if (openSession == session && hash != null && currentHash == hash) {
                    teardownLocked(stopServer = false)
                } else if (serverMayBeUp && hash == null && ownerShutDownLocked(session)) {
                    // Ended before the claim: the owner's shutdown may have raced
                    // binary.start() (its stop saw no process yet), so stop the server again.
                    teardownJob = chainTeardownLocked(null, stopServer = true)
                }
                if (e !is CancellationException && openSession == session) {
                    _state.value = TorrentState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
            throw e
        }
    }

    /**
     * Drops [session]'s torrent without blocking the caller (callers include the main thread;
     * the drop is an HTTP call with 10 s/30 s timeouts). A stale session is a no-op.
     */
    fun stopStream(session: Long) = endSession(session, stopServer = false)

    /**
     * Drops [session]'s torrent and stops the TorrServer process, asynchronously. Called when
     * the owner of a torrent session is done (player release, external player return) so the
     * server does not hold RAM for the app's lifetime; the next startStream() restarts it
     * lazily. A stale session is a no-op.
     */
    fun shutdown(session: Long) = endSession(session, stopServer = true)

    private fun endSession(session: Long, stopServer: Boolean) {
        synchronized(lock) {
            if (session != sessionGeneration) return
            openSession = null
            if (stopServer) shutdownRequested = true
            teardownLocked(stopServer)
        }
    }

    // [session] is the latest and its owner shut it down: nothing else will stop the server.
    private fun ownerShutDownLocked(session: Long) =
        sessionGeneration == session && openSession == null && shutdownRequested

    private fun ensureOpenLocked(session: Long) {
        if (openSession != session) throw sessionEnded(session)
    }

    private fun sessionEnded(session: Long) =
        CancellationException("Torrent session $session superseded or stopped")

    private fun teardownLocked(stopServer: Boolean) {
        statsJob?.cancel()
        statsJob = null
        val hash = currentHash
        currentHash = null
        _state.value = TorrentState.Idle
        if (hash != null || stopServer) teardownJob = chainTeardownLocked(hash, stopServer)
    }

    private fun chainTeardownLocked(hash: String?, stopServer: Boolean): Job {
        val previous = teardownJob
        return scope.launch {
            previous?.join()
            try {
                if (hash != null) api.dropTorrent(hash)
                if (stopServer) binary.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error tearing down torrent stream", e)
            }
        }
    }

    // infoHash and trackers come from addon responses: validate the hash and encode each
    // tracker so neither can inject extra magnet parameters.
    private fun buildMagnetUri(infoHash: String, extraTrackers: List<String>): String {
        if (!INFO_HASH.matches(infoHash)) {
            throw TorrentException(appContext.getString(com.nuvio.tv.R.string.torrent_error_invalid_info_hash))
        }
        val trackers = (DEFAULT_TRACKERS + extraTrackers).distinct()
        val trackerParams = trackers.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:$infoHash$trackerParams"
    }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?): Int {
        // Poll for metadata — magnet links may not have it immediately
        val deadline = System.currentTimeMillis() + 15_000L
        var files: List<TorrServerFile> = emptyList()

        while (System.currentTimeMillis() < deadline) {
            files = api.getTorrentStats(hash)?.files ?: emptyList()
            if (files.isNotEmpty()) break
            Log.d(TAG, "Waiting for torrent metadata...")
            delay(1_000L)
        }

        if (files.isEmpty()) {
            Log.w(TAG, "No files after metadata timeout, guessing index ${requestedIdx?.plus(1) ?: 1}")
            return requestedIdx?.plus(1) ?: 1
        }

        Log.d(TAG, "Torrent has ${files.size} files")

        // Strategy 1: Match by filename (most reliable for season packs)
        if (!filename.isNullOrBlank()) {
            val name = filename.trim()
            // Exact basename match
            val exact = files.firstOrNull { f ->
                f.path.substringAfterLast('/').equals(name, ignoreCase = true)
            }
            if (exact != null) {
                Log.d(TAG, "File resolved by exact filename match: id=${exact.id}")
                return exact.id
            }
            // Contains match (addon filename may be substring of full path)
            val contains = files.firstOrNull { f ->
                f.path.contains(name, ignoreCase = true)
            }
            if (contains != null) {
                Log.d(TAG, "File resolved by filename contains match: id=${contains.id}")
                return contains.id
            }
        }

        // Strategy 2: Match by ID offset (requestedIdx + 1)
        if (requestedIdx != null) {
            val tsIdx = requestedIdx + 1
            if (files.any { it.id == tsIdx }) {
                Log.d(TAG, "File resolved by ID offset: id=$tsIdx")
                return tsIdx
            }
        }

        // Strategy 3: Positional index (handles TorrServer alphabetical sort mismatch)
        if (requestedIdx != null && requestedIdx in files.indices) {
            val positionalFile = files[requestedIdx]
            Log.d(TAG, "File resolved by positional index: [$requestedIdx] -> id=${positionalFile.id}")
            return positionalFile.id
        }

        // Strategy 4: Fallback to largest video file
        val videoFile = files
            .filter { f ->
                val ext = f.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.length }

        val result = videoFile?.id ?: files.maxByOrNull { it.length }?.id ?: 1
        Log.d(TAG, "File resolved by largest video fallback: id=$result")
        return result
    }

    private fun startStatsPollingLocked(hash: String, fileId: Int) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val stats = api.getTorrentStats(hash)
                    if (stats != null) {
                        synchronized(lock) {
                            // A teardown between the fetch and here must not resurrect Streaming.
                            val currentState = _state.value
                            if (currentHash == hash && currentState is TorrentState.Streaming) {
                                _state.value = currentState.copy(
                                    downloadSpeed = stats.downloadSpeed,
                                    uploadSpeed = stats.uploadSpeed,
                                    peers = stats.peers,
                                    seeds = stats.seeds,
                                    totalProgress = stats.totalProgress,
                                    preloadedBytes = stats.preloadedBytes,
                                    torrentSize = stats.torrentSize,
                                    fileNumber = stats.files.indexOfFirst { it.id == fileId } + 1,
                                    fileCount = stats.files.size
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stats polling error", e)
                }
                delay(1000)
            }
        }
    }
}
