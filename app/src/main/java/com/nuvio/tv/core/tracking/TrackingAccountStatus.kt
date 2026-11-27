package com.nuvio.tv.core.tracking

/**
 * What a tracker reports about the account's allowance (Simkl's daily quota, Spec 106 E2/E5), drawn by the Trackers
 * panel for any tracker (Spec 105 A2: MDBList's limits and plan). Null fields are not shown.
 */
data class TrackingAccountStatus(
    val limit: Int? = null,
    val remaining: Int? = null,
    val resetsAtEpochMs: Long? = null,
    /** Set while the service refuses calls until then (E4/E5): sync is paused. */
    val pausedUntilEpochMs: Long? = null,
    /** The service's page where the owner sees each app's use ("simkl.com/settings/connected-apps"). */
    val usagePage: String? = null,
    /** The account's plan as the service names it (MDBList's "free"); drawn as A2's Plan line. */
    val plan: String? = null
) {
    /** The pause's end while it lasts at [nowEpochMs], else null. */
    fun pausedUntil(nowEpochMs: Long): Long? = pausedUntilEpochMs?.takeIf { nowEpochMs < it }

    fun isPaused(nowEpochMs: Long): Boolean = pausedUntil(nowEpochMs) != null
}
