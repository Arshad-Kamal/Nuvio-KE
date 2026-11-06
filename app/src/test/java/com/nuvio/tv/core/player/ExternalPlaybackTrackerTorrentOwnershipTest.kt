package com.nuvio.tv.core.player

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * T-TS: the tracker owns an external player's torrent session from launchPlayer on, so the
 * launching Stream screen can be popped (auto-play) without dropping the stream. It shuts the
 * session down on return, and on any launch that ends without an external player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaybackTrackerTorrentOwnershipTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val context = mockk<Context>(relaxed = true)
    private val watchProgressRepository = mockk<WatchProgressRepository>()
    private val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
    private val torrentService = mockk<TorrentService>(relaxed = true)
    private val metaRepository = mockk<MetaRepository>()
    private val tracker = ExternalPlaybackTracker(
        appContext = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
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
        every { watchProgressRepository.getProgress(any()) } returns flowOf(null)
        every { playerSettingsDataStore.playerSettings } returns
            flowOf(mockk<PlayerSettings>(relaxed = true))
    }

    private suspend fun launchTorrent(session: Long): Boolean = tracker.launchPlayer(
        metadata = metadata,
        url = "http://127.0.0.1:8091/stream?link=x&index=1&play",
        title = "Movie",
        headers = null,
        torrentSession = session,
        context = context
    )

    @Test
    fun `launched torrent survives until the player returns, then is shut down once`() = runBlocking {
        tracker.activityLauncher = mockk<ActivityResultLauncher<ExternalPlayerInput>>(relaxed = true)

        assertTrue(launchTorrent(SESSION))
        verify(exactly = 0) { torrentService.shutdown(any()) }
        verify(exactly = 0) { torrentService.stopStream(any()) }

        tracker.stopTracking()
        tracker.stopTracking()

        verify(exactly = 1) { torrentService.shutdown(SESSION) }
    }

    @Test
    fun `launch failing before the intent shuts the torrent session down`() = runBlocking {
        every { playerSettingsDataStore.playerSettings } returns emptyFlow()

        try {
            launchTorrent(SESSION)
            fail("launchPlayer must propagate the failure")
        } catch (expected: NoSuchElementException) {
        }

        verify(exactly = 1) { torrentService.shutdown(SESSION) }
    }

    @Test
    fun `launch cancelled before the intent shuts the torrent session down`() = runBlocking {
        every { playerSettingsDataStore.playerSettings } returns flow { awaitCancellation() }

        val job = launch { launchTorrent(SESSION) }
        yield() // let the launch park on the settings read
        job.cancelAndJoin()

        verify(exactly = 1) { torrentService.shutdown(SESSION) }
    }

    @Test
    fun `late duration backfill for a superseded session leaves the newer launch alone`() = runBlocking {
        tracker.activityLauncher = mockk<ActivityResultLauncher<ExternalPlayerInput>>(relaxed = true)
        val metaGate = CompletableDeferred<Unit>()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns flow {
            metaGate.await()
            emit(NetworkResult.Error("offline"))
        }
        coEvery { watchProgressRepository.saveProgress(any(), any()) } just Runs

        assertTrue(launchTorrent(SESSION))
        // VLC-style return: a position but no duration parks the result on the meta backfill.
        tracker.onActivityResult(ExternalPlayerResult(positionMs = 600_000L, durationMs = null))
        val newer = metadata.copy(contentId = "tt0000002", videoId = "tt0000002")
        assertTrue(
            tracker.launchPlayer(
                metadata = newer,
                url = "http://127.0.0.1:8091/stream?link=y&index=1&play",
                title = "Other",
                headers = null,
                torrentSession = NEWER_SESSION,
                context = context
            )
        )

        metaGate.complete(Unit)

        verify(exactly = 0) { torrentService.shutdown(NEWER_SESSION) }
        assertTrue(tracker.pendingMetadata === newer)
    }

    private companion object {
        const val SESSION = 9L
        const val NEWER_SESSION = 10L
    }
}
