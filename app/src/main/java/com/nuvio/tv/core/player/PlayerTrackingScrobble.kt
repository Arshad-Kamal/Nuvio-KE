package com.nuvio.tv.core.player

import android.os.SystemClock
import com.nuvio.tv.core.tracking.TRACKING_WRITE_TIMEOUT_MS
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The player's scrobble hook (Spec 104 S2e): reports start / pause / stop of what the internal player (ExoPlayer or
 * mpv) plays to every connected tracker. Local progress and Nuvio sync are untouched (S2-R1): the player's own
 * completion mark is not sent to trackers (markAsCompleted(broadcastToTrackers = false)); this hook reports the play.
 * External players are not scrobbled.
 */

/**
 * What the player is playing, as the hook sees it. [mediaKey] changes with the title or episode; [positionMs] and
 * [durationMs] come from the engine-neutral playback timeline.
 */
internal data class ScrobbleSnapshot(
    val media: TrackingMediaReference,
    val positionMs: Long,
    val durationMs: Long,
    /** Never scrobbled: a rejected placeholder stream, or a stream too short to be the title (the progress-save rule). */
    val placeholder: Boolean
) {
    val mediaKey: String
        get() = "${media.stableKey}|${media.episode?.season}|${media.episode?.number}"

    val progressPercent: Double
        get() = if (durationMs > 0) (positionMs.toDouble() / durationMs * 100.0).coerceIn(0.0, 100.0) else 0.0

    fun event(): TrackingScrobbleEvent = TrackingScrobbleEvent(
        media = media,
        progressPercent = progressPercent,
        durationSeconds = durationMs.takeIf { it > 0 }?.div(1000)
    )
}

/**
 * The snapshot of the player's current item, or null when there is nothing a tracker could record: no content id, a
 * changelog release video, a reference with no identity, or a whole series (no episode coordinates: a tracker would
 * mark every episode, see [TrackingMediaReference.isWholeSeries]). [contentName] is the series or film name, [title]
 * the screen title it falls back to (as the progress save does).
 */
internal fun scrobbleSnapshotOf(
    contentId: String?,
    contentType: String?,
    contentName: String?,
    title: String?,
    year: String?,
    videoId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    positionMs: Long,
    durationMs: Long,
    placeholderRejected: Boolean,
    releaseVideo: Boolean
): ScrobbleSnapshot? {
    if (releaseVideo) return null
    val parentId = contentId?.takeIf(String::isNotBlank) ?: return null
    val type = contentType?.takeIf(String::isNotBlank) ?: return null
    val media = buildTrackingMediaReference(
        contentType = type,
        parentMetaId = parentId,
        videoId = videoId,
        title = contentName ?: title,
        releaseInfo = year,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle
    )
    if (media.isWholeSeries || !media.hasResolvableIdentity) return null
    return ScrobbleSnapshot(
        media = media,
        positionMs = positionMs,
        durationMs = durationMs,
        placeholder = placeholderRejected || PlaceholderStreamPolicy.isShortPlaceholderDuration(durationMs)
    )
}

/** One observation for the state machine. */
internal data class PlayerScrobbleInput(
    val snapshot: ScrobbleSnapshot?,
    val playing: Boolean,
    val playIntent: Boolean,
    val ended: Boolean
)

/**
 * Reads the player for the hook: null while a scrub preview shows (the timeline is where playback is not). Playing =
 * frames moving (mpv reports isPlaying through a cache stall, flagged as buffering). A pause is the app's own pause
 * flag ([userPaused]: the user's Play/Pause, a remote or the app leaving the front), never the engine's
 * playWhenReady, which the controller also drops itself while preparing or releasing a player.
 */
internal fun playerScrobbleInput(
    ui: PlayerUiState,
    timeline: PlaybackTimelineState,
    contentId: String?,
    contentType: String?,
    contentName: String?,
    title: String?,
    year: String?,
    placeholderRejected: Boolean,
    releaseVideo: Boolean,
    userPaused: Boolean
): PlayerScrobbleInput? {
    if (ui.pendingPreviewSeekPosition != null) return null
    val playing = ui.isPlaying && !ui.isBuffering
    return PlayerScrobbleInput(
        snapshot = scrobbleSnapshotOf(
            contentId = contentId,
            contentType = contentType,
            contentName = contentName,
            title = title,
            year = year,
            videoId = ui.currentVideoId,
            season = ui.currentSeason,
            episode = ui.currentEpisode,
            episodeTitle = ui.currentEpisodeTitle,
            positionMs = timeline.currentPosition,
            durationMs = timeline.duration,
            placeholderRejected = placeholderRejected,
            releaseVideo = releaseVideo
        ),
        playing = playing,
        // The engine's own play state wins over the flag: mpv can play with a stale flag from a paused start.
        playIntent = ui.isPlaying || !userPaused,
        ended = ui.playbackEnded
    )
}

/** The controller's current state for the hook; main thread only (it reads the controller's fields). */
internal fun PlayerRuntimeController.scrobbleInput(): PlayerScrobbleInput? = playerScrobbleInput(
    ui = uiState.value,
    timeline = playbackTimeline.value,
    contentId = contentId,
    contentType = contentType,
    contentName = contentName,
    title = title,
    year = year,
    placeholderRejected = placeholderRejectedForStream,
    releaseVideo = releaseVideo,
    userPaused = userPausedManually
)

/** One scrobble to send: a plain [action], a seek (stop + restart), or a stop only trackers that keep a pause need. */
internal sealed interface ScrobbleCommand {
    data class Send(val action: TrackingScrobbleAction, val event: TrackingScrobbleEvent) : ScrobbleCommand
    data class SeekRestart(val stop: TrackingScrobbleEvent, val restart: TrackingScrobbleEvent) : ScrobbleCommand
    data class StopWhenLeftPaused(val event: TrackingScrobbleEvent) : ScrobbleCommand
}

/** A playhead this far from where playing explains counts as a seek. */
internal const val SCROBBLE_SEEK_THRESHOLD_MS = 30_000L

/**
 * Ported from upstream NuvioTV `PlayerScrobblePolicy` / `emitScrobbleStart`: no start at or past this (the title is
 * already as good as watched, a start would open a duplicate history entry); a stop from a play that sent no start
 * still counts at or past it, so finishing a title resumed late records it.
 */
internal const val SCROBBLE_WATCHED_PERCENT = 80.0

/** Upstream's seek floor: a seek to the first 1% restarts nothing. */
internal const val SCROBBLE_SEEK_MIN_PERCENT = 1.0

/**
 * The scrobble state machine: pure, no I/O, no clock of its own. Fed what the player does, it returns what to send.
 * One per player screen; called from one thread.
 */
internal class PlayerScrobbleState {
    private enum class Phase {
        /** Nothing sent and not played. */
        IDLE,

        /** A START was sent. */
        ACTIVE,

        /** A PAUSE was sent after a START. */
        PAUSED,

        /** Played past [SCROBBLE_WATCHED_PERCENT] with no START sent: its stop still counts. */
        PLAYED_LATE,

        /** A STOP was sent at the natural end; nothing more until a replay below the threshold. */
        DONE
    }

    private var phase = Phase.IDLE

    /** The latest snapshot of the current item: what a stop reports. */
    private var last: ScrobbleSnapshot? = null

    /** The last snapshot seen while playing, and when: the base of seek detection. */
    private var anchor: ScrobbleSnapshot? = null
    private var anchorAtMs = 0L

    /** The previous observation was playing. Only playing time between two playing observations moves the playhead:
     *  a pause, stall or reload may emit nothing for minutes, so it is never measured by the clock. */
    private var wasPlaying = false

    /**
     * The player's state changed or a timeline tick arrived. [playing]: frames are moving; [playIntent]: the player
     * means to play (false = paused by the user or the app; true while not playing = buffering or a seek, which is
     * neither a pause nor a stop); [ended]: the stream reached its natural end. [nowMs] is a monotonic clock.
     */
    fun observe(
        snapshot: ScrobbleSnapshot?,
        playing: Boolean,
        playIntent: Boolean,
        ended: Boolean,
        nowMs: Long
    ): List<ScrobbleCommand> {
        val commands = mutableListOf<ScrobbleCommand>()
        val previous = last
        // Another title or episode, or found to be a placeholder: what was going ends first, as it last was.
        if (previous != null && (snapshot == null || snapshot.placeholder || snapshot.mediaKey != previous.mediaKey)) {
            commands += finish(previous)
        }
        if (snapshot == null || snapshot.placeholder) {
            reset()
            return commands
        }
        if (snapshot.durationMs <= 0L) {
            // No length yet (a new stream loading): no progress to report, so it neither starts nor replaces what
            // a stop would report. Another item's arrival has already ended the old one above; for the same item the
            // reload is not playing time.
            if (previous != null && snapshot.mediaKey != previous.mediaKey) reset()
            wasPlaying = false
            return commands
        }
        last = snapshot
        val percent = snapshot.progressPercent
        when {
            ended -> if (phase == Phase.ACTIVE || phase == Phase.PAUSED || phase == Phase.PLAYED_LATE) {
                commands += ScrobbleCommand.Send(TrackingScrobbleAction.STOP, snapshot.event())
                phase = Phase.DONE
            }

            playing -> {
                when (phase) {
                    Phase.ACTIVE -> anchor?.let { from ->
                        val playedMs = if (wasPlaying) (nowMs - anchorAtMs).coerceAtLeast(0L) else 0L
                        if (abs(snapshot.positionMs - (from.positionMs + playedMs)) > SCROBBLE_SEEK_THRESHOLD_MS &&
                            percent >= SCROBBLE_SEEK_MIN_PERCENT && percent < SCROBBLE_WATCHED_PERCENT
                        ) {
                            // As upstream: both halves carry the new position, so a seek back from the last fifth
                            // never sends a stop that would mark the title watched.
                            val event = snapshot.event()
                            commands += ScrobbleCommand.SeekRestart(stop = event, restart = event)
                        }
                    }

                    // DONE stays DONE past the threshold (already reported); a PAUSED tracker session is left as
                    // is there: the late stop (finish) closes it.
                    else -> if (percent < SCROBBLE_WATCHED_PERCENT) {
                        commands += start(snapshot)
                    } else if (phase != Phase.DONE) {
                        phase = Phase.PLAYED_LATE
                    }
                }
                anchor = snapshot
                anchorAtMs = nowMs
            }

            !playIntent -> if (phase == Phase.ACTIVE) {
                commands += ScrobbleCommand.Send(TrackingScrobbleAction.PAUSE, snapshot.event())
                phase = Phase.PAUSED
            }

            // Buffering or seeking: neither a pause nor a stop.
            else -> Unit
        }
        wasPlaying = playing && !ended
        return commands
    }

    /** The player screen closes (Back, a new player screen, an external player, the screen cleared). */
    fun exit(): List<ScrobbleCommand> {
        val previous = last ?: return emptyList()
        return finish(previous).also { reset() }
    }

    private fun start(snapshot: ScrobbleSnapshot): ScrobbleCommand {
        phase = Phase.ACTIVE
        return ScrobbleCommand.Send(TrackingScrobbleAction.START, snapshot.event())
    }

    private fun finish(previous: ScrobbleSnapshot): List<ScrobbleCommand> {
        val late = previous.progressPercent >= SCROBBLE_WATCHED_PERCENT
        return when (phase) {
            Phase.ACTIVE -> listOf(ScrobbleCommand.Send(TrackingScrobbleAction.STOP, previous.event()))
            Phase.PAUSED -> listOf(
                if (late) {
                    ScrobbleCommand.Send(TrackingScrobbleAction.STOP, previous.event())
                } else {
                    ScrobbleCommand.StopWhenLeftPaused(previous.event())
                }
            )
            Phase.PLAYED_LATE -> if (late) listOf(ScrobbleCommand.Send(TrackingScrobbleAction.STOP, previous.event())) else emptyList()
            Phase.IDLE, Phase.DONE -> emptyList()
        }.also { phase = Phase.IDLE }
    }

    private fun reset() {
        phase = Phase.IDLE
        last = null
        anchor = null
        wasPlaying = false
    }
}

/**
 * The one app-wide send queue: commands go out one at a time in the order screens produced them (on the main
 * thread), so a closed screen's last stop reaches trackers before the next screen's start. It lives on its own
 * scope: an exit's stop outlives the player screen. Each command is bounded by [TRACKING_WRITE_TIMEOUT_MS]; a
 * tracker's failure is logged by the coordinator and never stops the queue.
 */
@Singleton
class PlayerScrobbleQueue internal constructor(
    private val coordinator: TrackingScrobbleCoordinator,
    scope: CoroutineScope
) {
    @Inject
    constructor(coordinator: TrackingScrobbleCoordinator) : this(coordinator, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val commands = Channel<ScrobbleCommand>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                withTimeoutOrNull(TRACKING_WRITE_TIMEOUT_MS) { send(command) }
            }
        }
    }

    internal fun enqueue(command: ScrobbleCommand) {
        commands.trySend(command)
    }

    private suspend fun send(command: ScrobbleCommand) {
        when (command) {
            is ScrobbleCommand.Send -> coordinator.scrobble(command.action, command.event)
            is ScrobbleCommand.SeekRestart -> coordinator.scrobbleSeekRestart(command.stop) { command.restart }
            is ScrobbleCommand.StopWhenLeftPaused -> coordinator.scrobbleStopWhenLeftPaused(command.event)
        }
    }
}

/** One player screen's scrobble hook; [observe] and [exit] run on the main thread. After [exit] nothing is sent. */
internal class PlayerTrackingScrobbleSession(
    private val queue: PlayerScrobbleQueue,
    private val nowMs: () -> Long
) {
    private val state = PlayerScrobbleState()
    private var exited = false

    fun observe(input: PlayerScrobbleInput) {
        if (exited) return
        state.observe(input.snapshot, input.playing, input.playIntent, input.ended, nowMs()).forEach(queue::enqueue)
    }

    /** Idempotent: the first call sends the final stop, if any. */
    fun exit() {
        if (exited) return
        exited = true
        state.exit().forEach(queue::enqueue)
    }

    companion object {
        /** Null in a build with no tracker (the public keyless build, Spec 104 D6): the player then does no work. */
        fun createIfTrackers(registry: TrackingProviderRegistry, queue: PlayerScrobbleQueue): PlayerTrackingScrobbleSession? =
            if (registry.hasProviders) PlayerTrackingScrobbleSession(queue, SystemClock::elapsedRealtime) else null
    }
}
