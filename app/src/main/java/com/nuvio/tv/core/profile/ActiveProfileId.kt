package com.nuvio.tv.core.profile

import com.nuvio.tv.data.local.ProfileDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * The active profile id for the per-profile title gates (rating cap, Spec 64; content filters,
 * Spec 117); null when it cannot be read, which each gate fails closed on. Read from the DataStore
 * itself, not ProfileManager's stateIn mirror: the picker opens Home as soon as its write
 * completes, before the mirror has caught up, so the mirror can still hold the previous profile.
 */
internal suspend fun ProfileDataStore.activeProfileIdOrNull(): Int? = try {
    activeProfileId.first()
} catch (e: IOException) {
    null
}

/** Profile [profileId]'s name for a gate's message ("Not on Zayn's profile"); null when the list cannot be read. */
internal suspend fun ProfileDataStore.profileNameOrNull(profileId: Int): String? = try {
    profilesList.first().firstOrNull { it.id == profileId }?.name
} catch (e: IOException) {
    null
}
