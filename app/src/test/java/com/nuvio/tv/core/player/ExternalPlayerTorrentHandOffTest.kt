package com.nuvio.tv.core.player

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.torrent.TorrServerApi
import com.nuvio.tv.core.torrent.TorrServerBinary
import com.nuvio.tv.core.torrent.TorrServerFile
import com.nuvio.tv.core.torrent.TorrServerStats
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tracker-side ownership of a handed-off torrent session: a released player that holds no
 * session leaves the stream up, and ExternalPlaybackTracker shuts it down when the external
 * player returns. PlayerViewModel's own detach (controller.torrentSession nulled before
 * stopAndRelease) is NOT pinned here (no PlayerRuntimeController harness); it is covered by
 * on-device check S8 in docs/specs/012-shield-checks.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlayerTorrentHandOffTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val context = mockk<Context>(relaxed = true)
    private val binary = mockk<TorrServerBinary>()
    private val api = mockk<TorrServerApi>()
    private val torrentSettings = mockk<TorrentSettings> {
        every { settings } returns flowOf(TorrentSettingsData(p2pEnabled = true))
    }
    private val torrentService = TorrentService(context, binary, api, torrentSettings)
    private val watchProgressRepository = mockk<WatchProgressRepository>()
    private val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
    private val tracker = ExternalPlaybackTracker(
        appContext = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = mockk<MetaRepository>(),
        playerSettingsDataStore = playerSettingsDataStore,
        skipIntroRepository = mockk<SkipIntroRepository>(),
        torrentService = torrentService,
        watchCountDataStore = mockk(relaxed = true)
    )

    private val metadata = ExternalPlaybackMetadata(
        contentId = "tt0000001", contentType = "movie", contentName = "Movie",
        poster = null, backdrop = null, logo = null, videoId = "tt0000001",
        season = null, episode = null, episodeTitle = null, year = null
    )

    init {
        coJustRun { binary.start() }
        coJustRun { binary.stop() }
        coJustRun { api.dropTorrent(any()) }
        coEvery { api.addTorrent(any()) } returns HASH
        coEvery { api.getTorrentStats(any()) } returns TorrServerStats(
            downloadSpeed = 0, uploadSpeed = 0, peers = 0, seeds = 0, preloadedBytes = 0,
            loadedSize = 0, torrentSize = 0,
            files = listOf(TorrServerFile(id = 1, path = "movie.mkv", length = 100))
        )
        every { api.getStreamUrl(any(), any()) } returns URL
        every { watchProgressRepository.getProgress(any()) } returns flowOf(null)
        every { playerSettingsDataStore.playerSettings } returns
            flowOf(mockk<PlayerSettings>(relaxed = true))
    }

    @Test
    fun `handed-off torrent outlives the player release and is shut down by the tracker`() = runBlocking {
        tracker.activityLauncher = mockk<ActivityResultLauncher<ExternalPlayerInput>>(relaxed = true)
        val session = torrentService.beginSession()
        val url = torrentService.startStream(session, HASH, fileIdx = 0)

        // Stands in for the detached controller (its session field is null at release).
        val detachedField: Long? = null
        shutdownTorrServerAfterSession(detachedField, torrentService)
        val launched = tracker.launchPlayer(
            metadata = metadata,
            url = url,
            title = "Movie",
            headers = null,
            torrentSession = session,
            context = context
        )

        assertTrue(launched)
        Thread.sleep(200)
        coVerify(exactly = 0) { api.dropTorrent(any()) }
        coVerify(exactly = 0) { binary.stop() }
        assertTrue(torrentService.state.value is TorrentState.Streaming)

        tracker.stopTracking() // external player returned

        coVerify(timeout = 2_000) { binary.stop() }
        coVerify(exactly = 1) { api.dropTorrent(HASH) }
    }

    private companion object {
        const val HASH = "abcdef0123456789abcdef0123456789abcdef01"
        const val URL = "http://127.0.0.1:8091/stream?link=x&index=1&play"
    }
}
