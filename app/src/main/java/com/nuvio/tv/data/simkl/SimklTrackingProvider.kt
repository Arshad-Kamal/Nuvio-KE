package com.nuvio.tv.data.simkl

import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStatus
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingDeviceAuth
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderSetup
import com.nuvio.tv.core.tracking.TrackingScrobbler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Simkl in the tracker registry (Spec 106), bound in every build (D9). Capabilities grow with the slices that build
 * them (S2a reads progress and watched state, S2b writes and scrobbles, S3a the library). The [scrobbler] keeps the
 * defaults: seeks send nothing, a pause saves a playback (S2 plan). [isAuthenticated] follows the
 * active profile's stored account and this TV's app id, so it flips with a profile switch (Spec 104 S2 deferral) and
 * is false while the app id is not set up or the service revoked the grant. [accountStatus] is the active profile's
 * daily allowance (E2/E5).
 */
class SimklTrackingProvider(
    accounts: TrackingAccountStore,
    override val deviceAuth: TrackingDeviceAuth,
    override val setup: TrackingProviderSetup,
    override val scrobbler: TrackingScrobbler,
    quota: SimklQuota,
    activeProfileId: StateFlow<Int>,
    scope: CoroutineScope,
    nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.SCROBBLE
        ),
        logoRes = R.drawable.ic_tracker_simkl
    )

    override val isAuthenticated: StateFlow<Boolean> =
        combine(accounts.account(TrackingProviderId.SIMKL), setup.savedHint, ::simklConnected)
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val accountStatus: Flow<TrackingAccountStatus?> =
        combine(quota.all, activeProfileId) { all, profileId -> all[profileId]?.toAccountStatus(nowEpochMs()) }
            .distinctUntilChanged()
}

/** Connected = this TV has an app id ([appIdOrHint]: the id or its hint) and the profile a grant Simkl still accepts. */
internal fun simklConnected(account: TrackingAccount?, appIdOrHint: String?): Boolean =
    appIdOrHint != null && account != null && !account.revoked

/** Where a Simkl account owner sees each app's share of the allowance (E2/E5). */
internal const val SIMKL_USAGE_PAGE = "simkl.com/settings/connected-apps"

/**
 * E2/E5's box: Simkl's own numbers; null before any counted reply. While paused the reset is the pause's end, else
 * the next New York midnight as of [nowEpochMs]. A pause stays in the status after it ends (the panel reads
 * [TrackingAccountStatus.isPaused]) until the next reply clears it.
 */
internal fun SimklQuotaState.toAccountStatus(nowEpochMs: Long): TrackingAccountStatus? {
    if (limit == null && remaining == null && pausedUntilEpochMs == null) return null
    return TrackingAccountStatus(
        limit = limit,
        remaining = remaining,
        resetsAtEpochMs = pausedUntilEpochMs ?: nextSimklResetEpochMs(nowEpochMs),
        pausedUntilEpochMs = pausedUntilEpochMs,
        usagePage = SIMKL_USAGE_PAGE
    )
}
