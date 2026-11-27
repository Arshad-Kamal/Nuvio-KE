package com.nuvio.tv.core.tracking

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TrackingRefreshIntent {
    /** App start or return to the front; no timer polling (both services penalise it, Spec 104 scope). */
    AUTOMATIC,
    USER_INITIATED,
    INVALIDATED
}

/**
 * Collapses refreshes that pile up: a caller skips when a refresh for the same profile generation that STARTED after
 * the caller arrived has finished (so its data is at least as new as the caller asked for). A refresh already in
 * flight when the caller arrived never satisfies it: a write followed by INVALIDATED is not answered by older data.
 * A cancelled run counts for nobody; a failed one counts (the next app resume retries). [shouldRun] (the caller's
 * own staleness rule) is read under the lock. [block] runs under the lock, so it must be bounded (each tracker call
 * carries its own timeout) and must never call back into the same gate (the Mutex is not reentrant). Reworked from upstream NuvioTV's `TrackingRefreshGate` (Spec 104 S1
 * review 7b3a4557).
 */
class TrackingRefreshGate {
    private data class Run(val startIndex: Long, val profileGeneration: Long)

    private val mutex = Mutex()
    @Volatile private var startedRuns = 0L
    private var lastFinished: Run? = null

    suspend fun runIfNeeded(
        profileGeneration: Long,
        shouldRun: () -> Boolean,
        block: suspend () -> Unit
    ) {
        val arrival = startedRuns
        mutex.withLock {
            val finished = lastFinished
            if (finished != null && finished.startIndex >= arrival && finished.profileGeneration == profileGeneration) return
            if (!shouldRun()) return
            val run = Run(startedRuns, profileGeneration)
            startedRuns += 1L
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFinished = run
                throw error
            }
            lastFinished = run
        }
    }
}

data class TrackingProgressRefreshFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingProgressRefreshCoordinator @Inject constructor(
    private val providers: TrackingProgressProviderRegistry
) {
    /** Refreshes every connected tracker; one failing never stops another. [oneAtATime] is for refreshes nobody waits on. */
    suspend fun refreshConnected(
        intent: TrackingRefreshIntent,
        oneAtATime: Boolean = false
    ): List<TrackingProgressRefreshFailure> = supervisorScope {
        // The connection check is inside each tracker's guard: one tracker's failing check stops only that tracker.
        suspend fun refresh(provider: TrackingProgressProvider): TrackingProgressRefreshFailure? =
            trackerCallFailure { if (provider.isAuthenticated.first()) provider.refresh(intent) }
                ?.let { TrackingProgressRefreshFailure(provider.providerId, it) }
        val all = providers.providers()
        if (oneAtATime) {
            all.mapNotNull { refresh(it) }
        } else {
            all.map { provider -> async { refresh(provider) } }.mapNotNull { it.await() }
        }
    }
}
