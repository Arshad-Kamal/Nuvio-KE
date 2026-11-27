package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.server.PasteCheck

/**
 * A tracker that connects with a key pasted from the phone instead of a device-code sign-in (Spec 106 K seam, for
 * Spec 105's MDBList API key). Every call is for [profileId], the profile the Trackers screen was opened on.
 */
interface TrackingKeyAuth {
    /**
     * Checks [key] with the service and, when it is accepted, saves the account on [profileId]. [stillWanted] false
     * (the phone page closed meanwhile) saves nothing and answers [PasteCheck.Closed].
     */
    suspend fun connect(profileId: Int, key: String, stillWanted: () -> Boolean): PasteCheck

    /** Forgets this profile's key for the tracker. */
    suspend fun disconnect(profileId: Int)
}
