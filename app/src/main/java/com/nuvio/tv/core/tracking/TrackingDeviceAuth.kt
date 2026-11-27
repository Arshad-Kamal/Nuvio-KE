package com.nuvio.tv.core.tracking

/**
 * One device-code sign-in in progress (OAuth 2.0 device authorization grant, RFC 8628; Spec 104 S3a). [deviceCode] is
 * the secret the TV polls with: [toString] keeps it out of logs. [verificationUriComplete] carries the code, so the
 * QR fills it in; [userCode] and [verificationUri] are for typing it by hand.
 */
data class TrackingDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int
) {
    override fun toString(): String =
        "TrackingDeviceCode(userCode=$userCode, verificationUri=$verificationUri, expiresAtEpochMs=$expiresAtEpochMs, " +
            "intervalSeconds=$intervalSeconds)"
}

/** One poll's answer. */
sealed interface TrackingDevicePoll {
    /** Not approved yet: poll again after the interval. */
    data object Pending : TrackingDevicePoll

    /** The service asks the TV to poll slower (RFC 8628 3.5: add 5 s to the interval). */
    data object SlowDown : TrackingDevicePoll

    /** Approved: the provider has saved the account for the profile the sign-in was started on. */
    data object Approved : TrackingDevicePoll

    /** The user declined on the phone. */
    data object Denied : TrackingDevicePoll

    /** The code ran out; a new one is needed. */
    data object Expired : TrackingDevicePoll
}

/**
 * A tracker's sign-in (Spec 104 S3a). Every call is for [profileId] (the profile the Trackers screen was opened on), so
 * a profile switch during a sign-in never saves the account onto another profile. Network failures throw (the sign-in
 * screen shows "Couldn't reach ..."); calls are cancellable (the screen closing stops the polling).
 */
interface TrackingDeviceAuth {
    suspend fun start(profileId: Int): TrackingDeviceCode

    suspend fun poll(profileId: Int, code: TrackingDeviceCode): TrackingDevicePoll

    /** Forgets this profile's tokens for the tracker (a best-effort revoke at the service is the provider's business). */
    suspend fun disconnect(profileId: Int)
}
