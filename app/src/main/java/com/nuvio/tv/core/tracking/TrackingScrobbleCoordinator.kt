package com.nuvio.tv.core.tracking

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

private const val TAG = "TrackingScrobble"

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val action: TrackingScrobbleAction,
    val cause: Throwable
)

/**
 * Reports playback (start / pause / stop) to every connected tracker at once; one tracker failing never stops another
 * (Spec 104 scope: the one player hook). Ported from upstream NuvioTV (`core/tracking/TrackingScrobbleCoordinator.kt`).
 */
@Singleton
class TrackingScrobbleCoordinator @Inject constructor(
    private val providerRegistry: TrackingProviderRegistry
) {
    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> =
        dispatchTrackingScrobble(providerRegistry.connectedScrobblers(), action, event)
            .also { failures -> failures.log(event, seek = false) }

    /**
     * [restart] runs per tracker after its own stop, so it is read again each time and may return null to skip the
     * start. It runs once per tracker, possibly at the same time on several threads of the caller's dispatcher: it
     * must only read a snapshot (never the Media3 Player directly). The stop's skippedProviders apply to the restart.
     */
    suspend fun scrobbleSeekRestart(
        stop: TrackingScrobbleEvent,
        restart: () -> TrackingScrobbleEvent?
    ): List<TrackingScrobbleFailure> =
        dispatchTrackingSeekRestart(providerRegistry.connectedScrobblers(), stop, restart)
            .also { failures -> failures.log(stop, seek = true) }

    suspend fun scrobbleStopWhenLeftPaused(event: TrackingScrobbleEvent): List<TrackingScrobbleFailure> =
        dispatchTrackingStopWhenLeftPaused(providerRegistry.connectedScrobblers(), event)
            .also { failures -> failures.log(event, seek = false) }

    // Only the tracker, the action, the media kind and the error class are logged: no title, token or body (KT-SEC-02).
    private fun List<TrackingScrobbleFailure>.log(event: TrackingScrobbleEvent, seek: Boolean) {
        forEach { failure ->
            Log.w(
                TAG,
                "${failure.providerId.storageId} ${if (seek) "seek " else ""}scrobble ${failure.action.wireValue} failed " +
                    "(${event.media.kind}): ${failure.cause.javaClass.simpleName}"
            )
        }
    }
}

suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers.filter { it.providerId !in event.skippedProviders }.map { scrobbler ->
        async { scrobbleCatching(scrobbler, action, event) }
    }.awaitAll().filterNotNull()
}

suspend fun dispatchTrackingStopWhenLeftPaused(
    scrobblers: Collection<TrackingScrobbler>,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> =
    dispatchTrackingScrobble(scrobblers.filter { it.stopsWhenLeftPaused }, TrackingScrobbleAction.STOP, event)

suspend fun dispatchTrackingSeekRestart(
    scrobblers: Collection<TrackingScrobbler>,
    stop: TrackingScrobbleEvent,
    restart: () -> TrackingScrobbleEvent?
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers
        .filter { it.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART && it.providerId !in stop.skippedProviders }
        .map { scrobbler ->
            async {
                val stopFailure = scrobbleCatching(scrobbler, TrackingScrobbleAction.STOP, stop)
                // restart() is the caller's code: it runs inside this tracker's guard, so its throw is this tracker's
                // START failure, not a crash of every tracker's seek.
                val startFailure = trackerCallFailure { restart()?.let { scrobbler.scrobble(TrackingScrobbleAction.START, it) } }
                    ?.let { TrackingScrobbleFailure(scrobbler.providerId, TrackingScrobbleAction.START, it) }
                listOfNotNull(stopFailure, startFailure)
            }
        }
        .awaitAll()
        .flatten()
}

private suspend fun scrobbleCatching(
    scrobbler: TrackingScrobbler,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): TrackingScrobbleFailure? =
    trackerCallFailure { scrobbler.scrobble(action, event) }?.let { TrackingScrobbleFailure(scrobbler.providerId, action, it) }

/**
 * Runs one tracker's call and returns its failure, or null. The one policy for every per-tracker call: a tracker's
 * own cancellation (a timeout, its account switched mid-call) is that tracker's failure, so the others go on; a
 * cancelled caller is rethrown by ensureActive (KT-CONC-03); an Error is never caught.
 */
internal suspend fun trackerCallFailure(block: suspend () -> Unit): Exception? = try {
    block()
    null
} catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    error
}
