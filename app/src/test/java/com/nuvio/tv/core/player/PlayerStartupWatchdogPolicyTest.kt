package com.nuvio.tv.core.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.StartupLoadHold.ALL_ERRORS
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.StartupLoadHold.NONE
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.StartupLoadHold.TIMEOUTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupWatchdogPolicyTest {

    private val interval = PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS
    private val ceiling = PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS

    // --- shouldExtend: buffered-ahead growth gate ---

    @Test
    fun extendsWhileBufferedAheadGrowsUnderCeiling() {
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 5_000L,
                lastBufferedAheadMs = 0L,
            )
        )
    }

    @Test
    fun flatBufferedAheadFiresEvenUnderCeiling() {
        // A flat buffer is a stall, not a slow start — growth must be strict.
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 4_000L,
                lastBufferedAheadMs = 4_000L,
            )
        )
    }

    @Test
    fun shrunkBufferedAheadFires() {
        // Buffer draining (e.g. playhead eating into it) is not progress either.
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 3_000L,
                lastBufferedAheadMs = 4_000L,
            )
        )
    }

    @Test
    fun arrivingDataExtendsAFlatBufferUnderTheCeilingOnly() {
        // Spec 86 D3: nothing buffered yet, but bytes still arrive.
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 0L,
                lastBufferedAheadMs = 0L,
                dataArriving = true,
            )
        )
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling,
                bufferedAheadMs = 0L,
                lastBufferedAheadMs = 0L,
                dataArriving = true,
            )
        )
    }

    // --- waitsForData: Spec 86 D3, only a start whose video is selected ---

    private fun input(
        tracks: Boolean = true,
        hasVideo: Boolean = true,
        videoSelected: Boolean = true,
        bytesGrew: Boolean? = true,
    ) = PlayerStartupWatchdogPolicy.FireInput(tracks, hasVideo, videoSelected, bytesGrew)

    @Test
    fun waitsForDataOnlyWithVideoSelectedAndBytesArriving() {
        assertTrue(PlayerStartupWatchdogPolicy.waitsForData(input()))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(bytesGrew = false)))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(bytesGrew = null)))
        // Unread tracks, a refused or missing video track: no frame can come, so the fire stays at 20 s.
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(tracks = false)))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(videoSelected = false)))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(hasVideo = false, videoSelected = false)))
    }

    // --- shouldExtend: ceiling arithmetic (checks land at ~20/~40/~60 s) ---

    @Test
    fun ceilingBoundaryExtendsAtFortySecondCheckDespiteDrift() {
        // delay() never undershoots, so the ~40 s check lands a few ms past 40 s and
        // must still grant the re-arm — a zero-drift boundary would deny it and cap
        // the grace window at ~40 s instead of the documented ~60 s.
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = 2 * interval + 50,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
    }

    @Test
    fun ceilingBoundaryFiresOnceCheckLandsAtCeilingOrLater() {
        // Extend while the check lands before the ceiling; at or past it, fire.
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling - 1,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
        // A pathologically late check (main thread stalled for seconds) never re-arms,
        // so the worst-case fire is the ceiling plus one interval.
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling + interval,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
    }

    // --- fireReason: four-way classification, scan gate first ---

    @Test
    fun tracksNeverReadIsSourceFacingEvenWithStaleTrackFlags() {
        // Order pin: a previous stream's stale video flags must not masquerade as
        // decoder blame when the new stream's headers never arrived.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = false,
                    streamHasVideoTrack = true,
                    videoTrackSelected = true,
                )
            ),
        )
    }

    @Test
    fun videoTrackPresentButUnselectedMeansFormatRejected() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = true,
                    streamHasVideoTrack = true,
                    videoTrackSelected = false,
                )
            ),
        )
    }

    @Test
    fun noVideoTrackMeansTracklessStream() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = true,
                    streamHasVideoTrack = false,
                    videoTrackSelected = false,
                )
            ),
        )
    }

    @Test
    fun videoSelectedWithoutFrameIsTheGenuineDecoderWedge() {
        // Unknown byte flow (mpv's input) keeps today's decoder reason.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = true,
                    streamHasVideoTrack = true,
                    videoTrackSelected = true,
                )
            ),
        )
        // No bytes arriving: the Codec2 wedge blocks the playback thread, so loading stops.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive,
            PlayerStartupWatchdogPolicy.fireReason(input(bytesGrew = false)),
        )
    }

    @Test
    fun videoSelectedWithBytesStillArrivingIsADataReason() {
        // Spec 86 R9/D3 (Shield window 2026-11-12): 36 of 38 Mbit/s arriving read as a stuck decoder.
        assertEquals(PlayerStartupWatchdogPolicy.FireReason.DataStillArriving, PlayerStartupWatchdogPolicy.fireReason(input()))
        // The track checks still come first.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
            PlayerStartupWatchdogPolicy.fireReason(input(tracks = false)),
        )
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported,
            PlayerStartupWatchdogPolicy.fireReason(input(videoSelected = false)),
        )
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
            PlayerStartupWatchdogPolicy.fireReason(input(hasVideo = false, videoSelected = false)),
        )
    }

    // --- failsOver: Spec 86 R4/D3 ---

    @Test
    fun sourceFacingFiresFailOverRefusedFormatAndStuckDecoderStayOnTheBox() {
        val failing = PlayerStartupWatchdogPolicy.FireReason.entries.filter(PlayerStartupWatchdogPolicy::failsOver)
        assertEquals(
            listOf(
                PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
                PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
                PlayerStartupWatchdogPolicy.FireReason.DataStillArriving,
            ),
            failing,
        )
    }

    // --- messageRes: source-facing reasons share one string ---

    @Test
    fun messageMappingMatchesReasonSeverity() {
        assertEquals(
            R.string.player_error_startup_video_track_unsupported,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported),
        )
        assertEquals(
            R.string.player_error_startup_no_stream_data,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.TracksNotRead),
        )
        assertEquals(
            R.string.player_error_startup_no_stream_data,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack),
        )
        assertEquals(
            R.string.player_error_startup_no_stream_data,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.DataStillArriving),
        )
        assertEquals(
            R.string.player_error_startup_timeout,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive),
        )
    }

    // --- shouldStopAfterGrace: Spec 56 R1 stop after the post-fire grace ---

    private fun stopAfterGrace(
        hasRenderedFirstFrame: Boolean = false,
        samePlayer: Boolean = true,
        currentError: String? = "fire",
    ) = PlayerStartupWatchdogPolicy.shouldStopAfterGrace(
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        samePlayer = samePlayer,
        currentError = currentError,
        fire = "fire",
    )

    @Test
    fun stopsWhenNothingChangedDuringGrace() {
        assertTrue(stopAfterGrace())
    }

    @Test
    fun lateFirstFrameKeepsThePlayer() {
        assertFalse(stopAfterGrace(hasRenderedFirstFrame = true))
    }

    @Test
    fun replacedPlayerIsNotStopped() {
        assertFalse(stopAfterGrace(samePlayer = false))
    }

    @Test
    fun retractedOrReplacedErrorKeepsThePlayer() {
        assertFalse(stopAfterGrace(currentError = null))
        assertFalse(stopAfterGrace(currentError = "decode failure"))
    }

    // --- Spec 138: torrent starts ---

    private val torrentIdle = PlayerStartupWatchdogPolicy.TORRENT_IDLE_LIMIT_MS
    private val torrentCeiling = PlayerStartupWatchdogPolicy.TORRENT_CEILING_MS

    private fun torrentExtends(elapsedMs: Long, swarmProgressed: Boolean, buffered: Long = 0L, last: Long = 0L) =
        PlayerStartupWatchdogPolicy.shouldExtend(
            elapsedMs = elapsedMs,
            bufferedAheadMs = buffered,
            lastBufferedAheadMs = last,
            swarmProgressed = swarmProgressed,
        )

    @Test
    fun torrentWithNoProgressWaitsTheIdleLimitThenFires() {
        // Checks at ~20 and ~40 s extend; the ~60 s check (a few ms past) fires: a dead swarm moves on.
        assertTrue(torrentExtends(interval + 5L, swarmProgressed = false))
        assertTrue(torrentExtends(2 * interval + 5L, swarmProgressed = false))
        assertFalse(torrentExtends(torrentIdle + 5L, swarmProgressed = false))
    }

    @Test
    fun torrentWithSwarmProgressWaitsUpToItsCeiling() {
        assertTrue(torrentExtends(torrentIdle + 5L, swarmProgressed = true))
        assertTrue(torrentExtends(torrentCeiling - 15_000L, swarmProgressed = true))
        assertFalse(torrentExtends(torrentCeiling + 5L, swarmProgressed = true))
    }

    @Test
    fun torrentBufferGrowthCountsAsProgressPastTheIdleLimit() {
        assertTrue(torrentExtends(torrentIdle + 5L, swarmProgressed = false, buffered = 3_000L, last = 1_000L))
        assertFalse(torrentExtends(torrentIdle + 5L, swarmProgressed = false, buffered = 3_000L, last = 3_000L))
    }

    @Test
    fun nullSignalFiresWhereATorrentWithNoProgressStillWaits() {
        // Same inputs, flat buffer at ~20 s: a non-torrent (null) fires, a torrent (false) extends.
        assertFalse(PlayerStartupWatchdogPolicy.shouldExtend(interval + 5L, 0L, 0L, swarmProgressed = null))
        assertTrue(PlayerStartupWatchdogPolicy.shouldExtend(interval + 5L, 0L, 0L, swarmProgressed = false))
    }

    @Test
    fun torrentLimitsAreExclusiveAtTheirExactBoundaries() {
        assertTrue(torrentExtends(torrentIdle - 1L, swarmProgressed = false))
        assertFalse(torrentExtends(torrentIdle, swarmProgressed = false))
        assertTrue(torrentExtends(torrentCeiling - 1L, swarmProgressed = true))
        assertFalse(torrentExtends(torrentCeiling, swarmProgressed = true))
    }

    @Test
    fun swarmProgressIsIncomingBytesOrCacheGrowth() {
        val last = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 1_000L, downloadSpeed = 0L)
        assertTrue(PlayerStartupWatchdogPolicy.swarmProgressed(last.copy(downloadSpeed = 1L), last))
        assertTrue(PlayerStartupWatchdogPolicy.swarmProgressed(last.copy(cachedBytes = 2_000L), last))
        assertFalse(PlayerStartupWatchdogPolicy.swarmProgressed(last, last))
        // Cache evicted under the cap: not progress by itself.
        assertFalse(PlayerStartupWatchdogPolicy.swarmProgressed(last.copy(cachedBytes = 500L), last))
        assertFalse(PlayerStartupWatchdogPolicy.swarmProgressed(last, null))
    }

    @Test
    fun swarmBytesAreTheCacheGrowthSinceArmNeverNegative() {
        val armed = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 4_000L, downloadSpeed = 0L)
        assertEquals(6_000L, PlayerStartupWatchdogPolicy.swarmBytesSince(armed.copy(cachedBytes = 10_000L), armed))
        assertEquals(0L, PlayerStartupWatchdogPolicy.swarmBytesSince(armed.copy(cachedBytes = 1_000L), armed))
        assertEquals(0L, PlayerStartupWatchdogPolicy.swarmBytesSince(armed.copy(cachedBytes = 10_000L), null))
    }

    @Test
    fun aFrozenSampleWithASpeedIsNotProgress() {
        // A failed TorrServer poll keeps the last state: same sample, speed still > 0.
        val frozen = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 1_000L, downloadSpeed = 50_000L)
        assertFalse(PlayerStartupWatchdogPolicy.swarmProgressed(frozen, frozen))
        assertTrue(PlayerStartupWatchdogPolicy.swarmProgressed(frozen.copy(downloadSpeed = 40_000L), frozen))
    }

    @Test
    fun onlyCacheGrowthCountsAsDelivered() {
        val last = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 1_000L, downloadSpeed = 0L)
        assertFalse(PlayerStartupWatchdogPolicy.swarmDelivered(last.copy(downloadSpeed = 9_000L), last))
        assertTrue(PlayerStartupWatchdogPolicy.swarmDelivered(last.copy(cachedBytes = 1_001L), last))
        assertFalse(PlayerStartupWatchdogPolicy.swarmDelivered(last, null))
    }

    @Test
    fun extendSignalIsNullForNonTorrentAndRefusedFormatFalseWithoutStats() {
        val s = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 2_000L, downloadSpeed = 1L)
        val last = s.copy(cachedBytes = 1_000L)
        val r = PlayerStartupWatchdogPolicy.FireReason.TracksNotRead
        val refused = PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported
        assertNull(PlayerStartupWatchdogPolicy.torrentExtendSignal(false, s, last, r))
        assertNull(PlayerStartupWatchdogPolicy.torrentExtendSignal(true, s, last, refused))
        assertEquals(false, PlayerStartupWatchdogPolicy.torrentExtendSignal(true, null, last, r))
        assertEquals(true, PlayerStartupWatchdogPolicy.torrentExtendSignal(true, s, last, r))
    }

    @Test
    fun aWindowArmedBeforeTheFirstPollTakesItsFirstSampleAsBaselineAndCountsItsSpeed() {
        // Checks read null, null, then TorrServer's first stats arrive with a speed at the 60 s check.
        var armed: PlayerStartupWatchdogPolicy.SwarmSample? = null
        val last: PlayerStartupWatchdogPolicy.SwarmSample? = null
        armed = PlayerStartupWatchdogPolicy.swarmBaseline(armed, null)
        assertNull(armed)
        val first = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 3_000L, downloadSpeed = 80_000L)
        armed = PlayerStartupWatchdogPolicy.swarmBaseline(armed, first)
        assertEquals(first, armed)
        val signal = PlayerStartupWatchdogPolicy.torrentExtendSignal(
            true, first, last, PlayerStartupWatchdogPolicy.FireReason.TracksNotRead
        )
        assertEquals(true, signal)
        assertTrue(torrentExtends(torrentIdle + 5L, swarmProgressed = signal!!))
        // A later sample never moves the baseline.
        assertEquals(first, PlayerStartupWatchdogPolicy.swarmBaseline(armed, first.copy(cachedBytes = 9_000L)))
    }

    @Test
    fun fireWindowBytesAreSwarmBytesForATorrentLoopbackOtherwise() {
        val armed = PlayerStartupWatchdogPolicy.SwarmSample(cachedBytes = 4_000L, downloadSpeed = 0L)
        val now = armed.copy(cachedBytes = 9_000L)
        assertEquals(5_000L, PlayerStartupWatchdogPolicy.fireWindowBytes(true, now, armed, loopbackBytes = 123L))
        assertEquals(0L, PlayerStartupWatchdogPolicy.fireWindowBytes(true, null, armed, loopbackBytes = 123L))
        assertEquals(123L, PlayerStartupWatchdogPolicy.fireWindowBytes(false, now, armed, loopbackBytes = 123L))
        assertNull(PlayerStartupWatchdogPolicy.fireWindowBytes(false, null, null, loopbackBytes = null))
    }

    private fun holds(
        host: String? = com.nuvio.tv.core.torrent.TorrServerBinary.HOST,
        port: Int = com.nuvio.tv.core.torrent.TorrServerBinary.PORT,
        serverPort: Int = com.nuvio.tv.core.torrent.TorrServerBinary.PORT,
        torrent: Boolean = true,
        firstFrame: Boolean = false,
        jobActive: Boolean = true,
        background: Boolean = false,
        error: Boolean = false,
        watchdogBox: Boolean = false,
        current: Boolean = true,
    ) = PlayerStartupWatchdogPolicy.torrentStartLoadHold(
        host, port, serverPort, current, torrent, firstFrame, jobActive, background, error, watchdogBox
    )

    @Test
    fun holdFollowsTorrServerOnAFallbackPort() {
        // Spec 148: TorrServer started on a free port because 8091 was taken.
        assertEquals(TIMEOUTS, holds(port = 41234, serverPort = 41234))
        assertEquals(NONE, holds(port = 8091, serverPort = 41234))
    }

    @Test
    fun timeoutHoldOnlyForAJudgedForegroundTorrentLoopbackLoad() {
        assertEquals(TIMEOUTS, holds())
        assertEquals(NONE, holds(host = "cdn.example.com"))
        assertEquals(NONE, holds(port = 8090))
        assertEquals(NONE, holds(host = null))
        assertEquals(NONE, holds(torrent = false))
        assertEquals(NONE, holds(firstFrame = true))
        assertEquals(NONE, holds(jobActive = false))
        assertEquals(NONE, holds(background = true))
        assertEquals(NONE, holds(error = true))
        assertEquals(NONE, holds(current = false))
    }

    @Test
    fun theWatchdogsOwnBoxHoldsEveryErrorUntilItsGraceStop() {
        // F1: TorrServer's HTTP 500 thrown at the box let the auto-retry clear it and restart the torrent.
        assertEquals(ALL_ERRORS, holds(error = true, watchdogBox = true))
        // Home during the box's grace: still held, so no background release rebuilds the judged torrent.
        assertEquals(ALL_ERRORS, holds(error = true, watchdogBox = true, background = true))
        // The grace stop ended the job: keepsBoxOnLateError takes over, so no hold outlives it.
        assertEquals(NONE, holds(error = true, watchdogBox = true, jobActive = false))
        assertEquals(NONE, holds(error = true, watchdogBox = true, firstFrame = true))
        assertEquals(NONE, holds(error = true, watchdogBox = true, current = false))
        assertEquals(NONE, holds(error = true, watchdogBox = true, torrent = false))
        assertEquals(NONE, holds(error = true, watchdogBox = true, host = "cdn.example.com"))
        assertEquals(NONE, holds(error = true, watchdogBox = true, port = 8090))
        assertEquals(NONE, holds(error = true, watchdogBox = true, host = null))
    }

    @Test
    fun aLateErrorAfterTheBoxsGraceStopKeepsTheBoxForATorrentOnly() {
        fun keeps(
            torrent: Boolean = true,
            graceStopped: Boolean = true,
            watchdogBox: Boolean = true,
            firstFrame: Boolean = false,
        ) = PlayerStartupWatchdogPolicy.keepsBoxOnLateError(torrent, graceStopped, watchdogBox, firstFrame)

        // F1: the error MSG_STOP's lag lets through, or a later re-prepare's, never reaches the auto-retry.
        assertTrue(keeps())
        assertFalse(keeps(torrent = false))
        // Still judging or in the grace: the load hold owns it; a re-armed start reset the flag.
        assertFalse(keeps(graceStopped = false))
        // Another error on screen owns its own handling; a first frame retracted the box.
        assertFalse(keeps(watchdogBox = false))
        assertFalse(keeps(firstFrame = true))
    }

    @Test
    fun loopbackDataArrivingExtendsATorrentPastTheIdleLimitOnly() {
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                torrentIdle + 5L, 0L, 0L, dataArriving = true, swarmProgressed = false
            )
        )
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                torrentCeiling, 0L, 0L, dataArriving = true, swarmProgressed = false
            )
        )
    }
}
