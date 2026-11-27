package com.nuvio.tv.core.player

import com.nuvio.tv.core.tracking.FakeScrobbler
import com.nuvio.tv.core.tracking.FakeTrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import com.nuvio.tv.core.tracking.TRACKING_WRITE_TIMEOUT_MS
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleAction.PAUSE
import com.nuvio.tv.core.tracking.TrackingScrobbleAction.START
import com.nuvio.tv.core.tracking.TrackingScrobbleAction.STOP
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 104 S2e: the player scrobble hook's state machine, snapshot rules and send queue. */
class PlayerTrackingScrobbleTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun film(positionMs: Long, durationMs: Long = 2 * hour, placeholderRejected: Boolean = false) =
        scrobbleSnapshotOf(
            contentId = "tt0133093", contentType = "movie", contentName = "The Matrix", title = "The Matrix",
            year = "1999", videoId = null, season = null, episode = null, episodeTitle = null,
            positionMs = positionMs, durationMs = durationMs, placeholderRejected = placeholderRejected,
            releaseVideo = false
        )!!

    private fun episode(number: Int, positionMs: Long, durationMs: Long = hour) =
        scrobbleSnapshotOf(
            contentId = "tt0944947", contentType = "series", contentName = "Game of Thrones", title = "Winter Is Coming",
            year = "2011", videoId = "tt0944947:1:$number", season = 1, episode = number, episodeTitle = null,
            positionMs = positionMs, durationMs = durationMs, placeholderRejected = false, releaseVideo = false
        )!!

    private val state = PlayerScrobbleState()
    private var now = 0L

    private fun play(snapshot: ScrobbleSnapshot?) = state.observe(snapshot, playing = true, playIntent = true, ended = false, nowMs = now)
    private fun pause(snapshot: ScrobbleSnapshot?) = state.observe(snapshot, playing = false, playIntent = false, ended = false, nowMs = now)
    private fun buffer(snapshot: ScrobbleSnapshot?) = state.observe(snapshot, playing = false, playIntent = true, ended = false, nowMs = now)
    private fun end(snapshot: ScrobbleSnapshot?) = state.observe(snapshot, playing = false, playIntent = true, ended = true, nowMs = now)

    private fun List<ScrobbleCommand>.actions(): List<String> = map { command ->
        when (command) {
            is ScrobbleCommand.Send -> "${command.action.wireValue}@${command.event.progressPercent.toInt()}"
            is ScrobbleCommand.SeekRestart -> "seek@${command.stop.progressPercent.toInt()}->${command.restart.progressPercent.toInt()}"
            is ScrobbleCommand.StopWhenLeftPaused -> "stop-left-paused@${command.event.progressPercent.toInt()}"
        }
    }

    @Test
    fun `play pause play exit sends start pause start stop`() {
        assertEquals(listOf("start@10"), play(film(12 * minute)).actions())
        now += 1_000
        assertEquals(emptyList<String>(), play(film(12 * minute + 1_000)).actions())
        assertEquals(listOf("pause@10"), pause(film(12 * minute + 1_000)).actions())
        assertEquals(emptyList<String>(), pause(film(12 * minute + 1_000)).actions())
        assertEquals(listOf("start@10"), play(film(12 * minute + 1_000)).actions())
        assertEquals(listOf("stop@10"), state.exit().actions())
        assertEquals(emptyList<String>(), state.exit().actions())
    }

    @Test
    fun `buffering and a not-yet-playing load send nothing`() {
        assertEquals(emptyList<String>(), buffer(film(0)).actions())
        assertEquals(emptyList<String>(), pause(film(0)).actions())
        play(film(0))
        assertEquals(emptyList<String>(), buffer(film(0)).actions())
        assertEquals(emptyList<String>(), state.observe(film(0), playing = false, playIntent = true, ended = false, nowMs = now).actions())
    }

    @Test
    fun `nothing is sent before the stream has a length, and it never replaces the stop's position`() {
        assertEquals(emptyList<String>(), play(film(0, durationMs = 0)).actions())
        play(film(24 * minute))
        assertEquals(emptyList<String>(), play(film(0, durationMs = 0)).actions())
        assertEquals(listOf("stop@20"), state.exit().actions())
    }

    @Test
    fun `an episode change stops the old episode at its last position and starts the new one`() {
        play(episode(1, 30 * minute))
        val switched = buffer(episode(2, 30 * minute))
        assertEquals(listOf("stop@50"), switched.actions())
        assertEquals(1, ((switched.single() as ScrobbleCommand.Send).event.media.episode?.number))
        assertEquals(listOf("start@0"), play(episode(2, 10_000)).actions())
    }

    @Test
    fun `a jump while playing is a seek, normal play and buffering time are not`() {
        play(film(12 * minute))
        now += 60_000
        assertEquals(emptyList<String>(), play(film(13 * minute)).actions())
        now += 90_000
        assertEquals(emptyList<String>(), buffer(film(13 * minute)).actions())
        assertEquals(emptyList<String>(), play(film(13 * minute)).actions())
        now += 1_000
        assertEquals(listOf("seek@50->50"), play(film(60 * minute)).actions())
        now += 1_000
        assertEquals(listOf("seek@4->4"), play(film(5 * minute)).actions())
    }

    @Test
    fun `a seek through buffering is still a seek`() {
        play(film(12 * minute))
        now += 1_000
        buffer(film(60 * minute))
        now += 3_000
        assertEquals(listOf("seek@50->50"), play(film(60 * minute)).actions())
    }

    @Test
    fun `a seek into the last fifth or the first percent restarts nothing`() {
        play(film(12 * minute))
        now += 1_000
        assertEquals(emptyList<String>(), play(film(100 * minute)).actions())
        now += 1_000
        assertEquals(emptyList<String>(), play(film(30_000)).actions())
        assertEquals(listOf("stop@0"), state.exit().actions())
    }

    @Test
    fun `a placeholder never scrobbles, and ends a play it turns out to replace`() {
        val placeholder = film(10_000, durationMs = 20_000)
        assertTrue(placeholder.placeholder)
        assertEquals(emptyList<String>(), play(placeholder).actions())
        assertEquals(emptyList<String>(), state.exit().actions())

        play(film(12 * minute))
        assertEquals(listOf("stop@10"), play(film(12 * minute, placeholderRejected = true)).actions())
        assertEquals(emptyList<String>(), state.exit().actions())
    }

    @Test
    fun `no start past 80 percent, but its played stop still counts`() {
        assertEquals(emptyList<String>(), play(film(100 * minute)).actions())
        now += 5 * minute
        assertEquals(emptyList<String>(), play(film(105 * minute)).actions())
        assertEquals(listOf("stop@87"), state.exit().actions())
    }

    @Test
    fun `opening past 80 percent and leaving unplayed sends nothing`() {
        assertEquals(emptyList<String>(), pause(film(100 * minute)).actions())
        assertEquals(emptyList<String>(), state.exit().actions())
    }

    @Test
    fun `seeking back from a late play starts a session`() {
        play(film(100 * minute))
        now += 1_000
        assertEquals(listOf("start@25"), play(film(30 * minute)).actions())
    }

    @Test
    fun `the natural end stops once, and the next episode does not stop it again`() {
        play(episode(1, 50 * minute))
        assertEquals(listOf("stop@100"), end(episode(1, hour)).actions())
        assertEquals(emptyList<String>(), end(episode(1, hour)).actions())
        assertEquals(emptyList<String>(), buffer(episode(2, 0)).actions())
        assertEquals(listOf("start@0"), play(episode(2, 1_000)).actions())
    }

    @Test
    fun `a replay from the start after the end opens a new session`() {
        play(film(110 * minute))
        end(film(2 * hour))
        assertEquals(emptyList<String>(), play(film(2 * hour)).actions())
        now += 1_000
        assertEquals(listOf("start@0"), play(film(1_000)).actions())
    }

    @Test
    fun `leaving while paused sends the paused stop, or a full stop past 80 percent`() {
        play(film(12 * minute))
        pause(film(12 * minute))
        assertEquals(listOf("stop-left-paused@10"), state.exit().actions())

        val late = PlayerScrobbleState()
        late.observe(film(12 * minute), playing = true, playIntent = true, ended = false, nowMs = 0)
        late.observe(film(100 * minute), playing = false, playIntent = false, ended = false, nowMs = 1_000)
        assertEquals(listOf("stop@83"), late.exit().actions())
    }

    @Test
    fun `snapshots exist only for a film or an episode a tracker can record`() {
        assertNull(
            scrobbleSnapshotOf("tt0944947", "series", "Game of Thrones", "x", null, null, null, null, null, 0, hour, false, false)
        )
        assertNull(scrobbleSnapshotOf(null, "movie", "x", "x", null, null, null, null, null, 0, hour, false, false))
        assertNull(scrobbleSnapshotOf("tt0133093", "movie", "x", "x", null, null, null, null, null, 0, hour, false, releaseVideo = true))
        val anime = scrobbleSnapshotOf("kitsu:1", "anime", "Cowboy Bebop", "x", null, "kitsu:1:5", 1, 5, null, 0, hour, false, false)!!
        assertEquals(5, anime.media.episode?.number)
        assertEquals("Cowboy Bebop", anime.media.title)
        assertEquals(1999, film(0).media.year)
    }

    @Test
    fun `the placeholder rule is the progress save's`() {
        assertFalse(film(0, durationMs = 0).placeholder)
        assertTrue(film(0, durationMs = 120_999).placeholder)
        assertFalse(film(0, durationMs = 121_000).placeholder)
        assertTrue(film(0, placeholderRejected = true).placeholder)
    }

    @Test
    fun `the session sends in order to connected trackers and stops after exit`() = runTest {
        val seeker = FakeScrobbler(
            TrackingProviderId.SIMKL,
            seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART,
            stopsWhenLeftPaused = true
        )
        val plain = FakeScrobbler(TrackingProviderId.MDBLIST)
        val registry = TrackingProviderRegistry(
            setOf(
                FakeTrackingProvider(TrackingProviderId.SIMKL, connected = true, scrobbler = seeker),
                FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true, scrobbler = plain)
            )
        )
        var clock = 0L
        val session = PlayerTrackingScrobbleSession(PlayerScrobbleQueue(TrackingScrobbleCoordinator(registry), backgroundScope)) { clock }

        session.observe(PlayerScrobbleInput(film(12 * minute), playing = true, playIntent = true, ended = false))
        clock += 1_000
        session.observe(PlayerScrobbleInput(film(60 * minute), playing = true, playIntent = true, ended = false))
        session.observe(PlayerScrobbleInput(film(60 * minute), playing = false, playIntent = false, ended = false))
        session.exit()
        session.exit()
        session.observe(PlayerScrobbleInput(film(61 * minute), playing = true, playIntent = true, ended = false))
        testScheduler.runCurrent()

        fun FakeScrobbler.log() = calls.map { (action, event) -> "${action.wireValue}@${event.progressPercent.toInt()}" }
        assertEquals(listOf("start@10", "stop@50", "start@50", "pause@50", "stop@50"), seeker.log())
        assertEquals(listOf("start@10", "pause@50"), plain.log())
    }

    @Test
    fun `a failing or hanging tracker never holds up the queue`() = runTest {
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, failure = IllegalStateException("down"))
        val hanging = HangingScrobbler(TrackingProviderId.MDBLIST)
        val registry = TrackingProviderRegistry(
            setOf(
                FakeTrackingProvider(TrackingProviderId.SIMKL, connected = true, scrobbler = failing),
                FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true, scrobbler = hanging)
            )
        )
        val session = PlayerTrackingScrobbleSession(PlayerScrobbleQueue(TrackingScrobbleCoordinator(registry), backgroundScope)) { 0L }
        session.observe(PlayerScrobbleInput(film(12 * minute), playing = true, playIntent = true, ended = false))
        session.observe(PlayerScrobbleInput(film(12 * minute), playing = false, playIntent = false, ended = false))
        testScheduler.runCurrent()
        assertEquals(listOf(START), failing.calls.map { it.first })
        testScheduler.advanceTimeBy(TRACKING_WRITE_TIMEOUT_MS + 1)
        testScheduler.runCurrent()
        assertEquals(listOf(START, PAUSE), failing.calls.map { it.first })
        assertEquals(listOf(START, PAUSE), hanging.calls.map { it.first })
    }

    @Test
    fun `a closed screen's stop goes out before the next screen's start, even behind a hung tracker`() = runTest {
        val hanging = HangingScrobbler(TrackingProviderId.MDBLIST)
        val registry = TrackingProviderRegistry(setOf(FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true, scrobbler = hanging)))
        val queue = PlayerScrobbleQueue(TrackingScrobbleCoordinator(registry), backgroundScope)
        val first = PlayerTrackingScrobbleSession(queue) { 0L }
        first.observe(PlayerScrobbleInput(film(12 * minute), playing = true, playIntent = true, ended = false))
        testScheduler.runCurrent()
        first.exit()
        val next = PlayerTrackingScrobbleSession(queue) { 0L }
        next.observe(PlayerScrobbleInput(film(60 * minute), playing = true, playIntent = true, ended = false))
        testScheduler.advanceTimeBy(TRACKING_WRITE_TIMEOUT_MS + 1)
        testScheduler.runCurrent()
        assertEquals(listOf(START to 10, STOP to 10, START to 50), hanging.calls)
    }

    @Test
    fun `a pause, stall or reload that emits nothing for minutes is not a seek`() {
        play(film(12 * minute))
        assertEquals(emptyList<String>(), buffer(film(12 * minute)).actions())
        now += 10 * minute
        assertEquals(emptyList<String>(), play(film(12 * minute)).actions())
        now += 1_000
        assertEquals(emptyList<String>(), play(film(12 * minute + 1_000)).actions())
    }

    @Test
    fun `the player read maps the screen and treats only the app's pause flag as a pause`() {
        val timeline = PlaybackTimelineState(currentPosition = 30 * minute, duration = hour)
        fun input(ui: PlayerUiState, userPaused: Boolean = false) = playerScrobbleInput(
            ui = ui, timeline = timeline, contentId = "tt0944947", contentType = "series", contentName = "Game of Thrones",
            title = "Winter Is Coming", year = "2011", placeholderRejected = false, releaseVideo = false, userPaused = userPaused
        )
        val episodeUi = PlayerUiState(currentSeason = 1, currentEpisode = 2, currentVideoId = "tt0944947:1:2")

        assertNull(input(episodeUi.copy(isPlaying = true, isBuffering = false, pendingPreviewSeekPosition = 5 * minute)))
        val playing = input(episodeUi.copy(isPlaying = true, isBuffering = false))!!
        assertTrue(playing.playing)
        assertEquals(2, playing.snapshot?.media?.episode?.number)
        assertEquals("Game of Thrones", playing.snapshot?.media?.title)
        assertEquals(50.0, playing.snapshot!!.progressPercent, 0.001)
        // mpv's cache stall: isPlaying stays true, flagged as buffering.
        val stalled = input(episodeUi.copy(isPlaying = true, isBuffering = true))!!
        assertFalse(stalled.playing)
        assertTrue(stalled.playIntent)
        // A player being prepared or released is not playing and not paused by anyone.
        val preparing = input(episodeUi.copy(isPlaying = false, isBuffering = false))!!
        assertTrue(preparing.playIntent)
        assertFalse(input(episodeUi.copy(isPlaying = false, isBuffering = false), userPaused = true)!!.playIntent)
        assertTrue(input(episodeUi.copy(playbackEnded = true))!!.ended)
        // mpv started paused leaves the flag set while it plays: its stall is still not a pause.
        assertTrue(input(episodeUi.copy(isPlaying = true, isBuffering = true), userPaused = true)!!.playIntent)
    }

    @Test
    fun `a rebuild of the same item while playing sends nothing`() {
        play(film(12 * minute))
        now += 1_000
        assertEquals(emptyList<String>(), buffer(film(0, durationMs = 0)).actions())
        now += 60_000
        assertEquals(emptyList<String>(), buffer(film(12 * minute + 1_000)).actions())
        assertEquals(emptyList<String>(), play(film(12 * minute + 1_000)).actions())
    }

    @Test
    fun `a reload with no length is not playing time`() {
        play(film(12 * minute))
        now += 60_000
        assertEquals(emptyList<String>(), play(film(0, durationMs = 0)).actions())
        assertEquals(emptyList<String>(), play(film(12 * minute)).actions())
    }

    @Test
    fun `the natural end of a late or paused play stops it, and nothing follows the end`() {
        play(film(100 * minute))
        assertEquals(listOf("stop@100"), end(film(2 * hour)).actions())
        assertEquals(emptyList<String>(), state.exit().actions())

        val paused = PlayerScrobbleState()
        paused.observe(film(12 * minute), playing = true, playIntent = true, ended = false, nowMs = 0)
        paused.observe(film(12 * minute), playing = false, playIntent = false, ended = false, nowMs = 0)
        assertEquals(listOf("stop@100"), paused.observe(film(2 * hour), playing = false, playIntent = false, ended = true, nowMs = 0).actions())
    }

    @Test
    fun `a build with no tracker gets no session`() {
        assertNull(
            PlayerTrackingScrobbleSession.createIfTrackers(
                TrackingProviderRegistry(emptySet()),
                PlayerScrobbleQueue(TrackingScrobbleCoordinator(TrackingProviderRegistry(emptySet())))
            )
        )
    }
}

/** Never answers its first call; records every call. */
private class HangingScrobbler(override val providerId: TrackingProviderId) : TrackingScrobbler {
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.NONE
    override val stopsWhenLeftPaused = false
    val calls = mutableListOf<Pair<TrackingScrobbleAction, Int>>()

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        calls += action to event.progressPercent.toInt()
        if (calls.size == 1) awaitCancellation()
    }
}
