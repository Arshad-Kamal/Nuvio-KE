package com.nuvio.tv.core.tracking

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "TrackingSync"

/** A tracker refresh at most this often on app resume; a profile switch always refreshes (Spec 104 S2d, mockup A2). */
internal const val TRACKING_AUTOMATIC_REFRESH_INTERVAL_MS = 5L * 60_000L

/**
 * When connected trackers refresh by themselves (Spec 104 S2d): when the app comes to the front (cold start included)
 * at most every [TRACKING_AUTOMATIC_REFRESH_INTERVAL_MS], and on a profile switch. Never on a timer: both services
 * penalise polling. Runs apart from `StartupSyncService`, which is gated on a Nuvio account; trackers sync without one.
 * In a build with no tracker it does nothing.
 */
@Singleton
class TrackingSyncScheduler internal constructor(
    private val progressRefresh: TrackingProgressRefreshCoordinator,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val registry: TrackingProviderRegistry,
    private val profileManager: ProfileManager,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        progressRefresh: TrackingProgressRefreshCoordinator,
        libraryProviders: TrackingLibraryProviderRegistry,
        registry: TrackingProviderRegistry,
        profileManager: ProfileManager
    ) : this(
        progressRefresh, libraryProviders, registry, profileManager,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SystemClock::elapsedRealtime
    )

    private val gate = TrackingRefreshGate()

    // Read and written only inside the gate's block or its shouldRun, both under the gate's lock.
    private val lastRunAtMs = mutableMapOf<Int, Long>()

    init {
        if (registry.hasProviders) {
            scope.launch {
                // The stored id, not activeProfileId (1 until it loads): the load itself is not a switch.
                profileManager.storedActiveProfileId.filterNotNull().distinctUntilChanged().drop(1)
                    .collect { refresh(force = true) }
            }
        }
    }

    /** MainActivity.onResume. */
    fun onAppForeground() {
        if (!registry.hasProviders) return
        scope.launch { refresh(force = false) }
    }

    internal suspend fun refresh(force: Boolean) {
        val profileId = profileManager.storedActiveProfileId.filterNotNull().first()
        gate.runIfNeeded(
            profileGeneration = profileId.toLong(),
            shouldRun = {
                force || lastRunAtMs[profileId]?.let { nowMs() - it >= TRACKING_AUTOMATIC_REFRESH_INTERVAL_MS } ?: true
            }
        ) {
            lastRunAtMs[profileId] = nowMs()
            progressRefresh.refreshConnected(TrackingRefreshIntent.AUTOMATIC, oneAtATime = true).forEach { failure ->
                Log.w(TAG, "${failure.providerId.storageId} progress refresh failed: ${failure.cause.javaClass.simpleName}")
            }
            val connected = registry.connectedProviderIds()
            libraryProviders.providers().filter { it.providerId in connected }.forEach { provider ->
                trackerCallFailure { provider.refresh(TrackingRefreshIntent.AUTOMATIC) }?.let { error ->
                    Log.w(TAG, "${provider.providerId.storageId} library refresh failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }
}
