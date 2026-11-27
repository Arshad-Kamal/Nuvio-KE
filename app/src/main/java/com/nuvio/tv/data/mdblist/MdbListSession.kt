package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProviderId

/**
 * MDBList calls for one profile with its stored key (Spec 105 D6-D7): the quota gate, the key and the quota record. A
 * `401` means MDBList no longer accepts the key (regenerated or account gone): the account is marked revoked, so the
 * Trackers row turns amber and sources fall back to Nuvio (Spec 104 C3). A `403` is a supporter-only feature, not a
 * bad key, and is passed through.
 */
class MdbListSession(
    private val client: MdbListClient,
    private val accounts: TrackingAccountStore,
    val quota: MdbListQuota
) {
    suspend fun call(profileId: Int, request: MdbListRequest): MdbListResponse {
        quota.checkAvailable(profileId)
        try {
            return client.execute(
                request,
                apiKey = {
                    // Read under the client's lock: a key disconnected or replaced while this call waited is not sent.
                    quota.checkAvailable(profileId)
                    currentKey(profileId) ?: throw MdbListSignedOutException()
                },
                onAnswer = { key, response, failure ->
                    // A disconnect or a new key landed during the call: its answer says nothing about the profile now.
                    if (currentKey(profileId) == key) {
                        response?.let { quota.record(profileId, it) }
                        failure?.let { quota.recordFailure(profileId, it) }
                        if (failure?.status == 401) {
                            accounts.update(profileId, TrackingProviderId.MDBLIST) { current ->
                                current?.takeIf { it.accessToken == key && !it.revoked }?.copy(revoked = true)
                            }
                        }
                    }
                }
            )
        } catch (failure: MdbListApiException) {
            if (failure.status == 401) throw MdbListSignedOutException()
            throw failure
        }
    }

    /** The profile's key while MDBList still accepts it (a revoked key is no key). */
    internal suspend fun currentKey(profileId: Int): String? =
        accounts.get(profileId, TrackingProviderId.MDBLIST)?.takeUnless { it.revoked }?.accessToken
}
