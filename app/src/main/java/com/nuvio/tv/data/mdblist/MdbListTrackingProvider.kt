package com.nuvio.tv.data.mdblist

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingAccountStatus
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.trackerCallFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * MDBList in the tracker registry (Spec 105 S1b), bound in every build (D8): it connects with the profile's own pasted
 * key ([keyAuth], E1-E4), so there is nothing to set up per TV. Capabilities grow with the slices that build them (S2a
 * reads progress and watched marks, S2b writes and scrobbles, S3a the library: the watchlist). [isAuthenticated] follows the active
 * profile's stored key and is false once MDBList refused it (`401`, 104 C3). [accountStatus] is the active profile's
 * daily allowance and plan (A2/A3); while it is collected (the Trackers screen), each profile that becomes active
 * with its plan unknown has it read once with `/user` (the quota lives in memory, so after a restart it is unknown).
 */
class MdbListTrackingProvider(
    accounts: TrackingAccountStore,
    override val keyAuth: MdbListKeyAuth,
    override val scrobbler: TrackingScrobbler,
    quota: MdbListQuota,
    private val activeProfileId: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.MDBLIST,
        displayName = "MDBList",
        capabilities = setOf(
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.SCROBBLE
        ),
        logoRes = R.drawable.ic_tracker_mdblist
    )

    override val isAuthenticated: StateFlow<Boolean> = accounts.account(TrackingProviderId.MDBLIST)
        .map { account -> account != null && !account.revoked }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Profiles whose plan is being read: a second collector (or switch back) meanwhile skips it. */
    private val planLoads: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override val accountStatus: Flow<TrackingAccountStatus?> =
        combine(quota.all, activeProfileId.onEach { loadPlanOnce(it) }) { all, profileId -> all[profileId]?.toAccountStatus(nowEpochMs()) }
            .distinctUntilChanged()

    private fun loadPlanOnce(profileId: Int) {
        if (!planLoads.add(profileId)) return
        scope.launch {
            try {
                trackerCallFailure { keyAuth.loadPlan(profileId) }
                    ?.let { Log.w(TAG, "plan read failed: ${it.javaClass.simpleName}") }
            } finally {
                planLoads.remove(profileId)
            }
        }
    }

    private companion object {
        const val TAG = "MdbListTracker"
    }
}

/**
 * A2/A3's box: MDBList's own numbers and the plan; null before anything is known. The reset is the pause's end while
 * paused, else MDBList's reported reset, else the next 00:00 UTC as of [nowEpochMs]. MDBList shows no per-app use, so
 * there is no usage page.
 */
internal fun MdbListQuotaState.toAccountStatus(nowEpochMs: Long): TrackingAccountStatus? {
    if (limit == null && remaining == null && pausedUntilEpochMs == null && plan == null) return null
    return TrackingAccountStatus(
        limit = limit,
        remaining = remaining,
        resetsAtEpochMs = pausedUntilEpochMs ?: resetsAtEpochMs?.takeIf { it > nowEpochMs } ?: nextMdbListResetEpochMs(nowEpochMs),
        pausedUntilEpochMs = pausedUntilEpochMs,
        plan = plan
    )
}
