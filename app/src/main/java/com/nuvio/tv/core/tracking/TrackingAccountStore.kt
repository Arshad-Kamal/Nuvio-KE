package com.nuvio.tv.core.tracking

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.localStoreJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** A profile's sign-in to one tracker. Never logged: [toString] keeps the tokens out. */
@Serializable
data class TrackingAccount(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
    /** Shown on the Trackers hub ("arshadk"). */
    val accountName: String? = null,
    /**
     * The service refused the token (password changed, app removed there): the hub's row turns amber and offers to
     * reconnect; the provider reports not authenticated, so sources read from Nuvio meanwhile (Spec 104 C3).
     */
    val revoked: Boolean = false,
    /** The last refresh that succeeded, written by the provider ("synced 2 min ago" on the hub). */
    val lastSyncedAtEpochMs: Long? = null
) {
    override fun toString(): String =
        "TrackingAccount(accountName=$accountName, expiresAtEpochMs=$expiresAtEpochMs, revoked=$revoked, " +
            "lastSyncedAtEpochMs=$lastSyncedAtEpochMs)"
}

/**
 * Per-profile tracker sign-ins (Spec 104 S1d), one entry per [TrackingProviderId.storageId]. The store is not in
 * `profileSettingsSyncedFeatures`, so tokens never leave this TV (D10); as a profile-scoped store it is wiped with the
 * profile and on sign-out from the Nuvio account.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingAccountStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        /** On-disk name: never rename (it would sign every profile out). */
        const val FEATURE = "tracking_accounts"
    }

    private fun key(id: TrackingProviderId) = stringPreferencesKey(id.storageId)

    /** The active profile's account for [id]; follows profile switches. */
    fun account(id: TrackingProviderId): Flow<TrackingAccount?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> decodeTrackingAccount(prefs[key(id)]) }
    }.distinctUntilChanged()

    suspend fun get(profileId: Int, id: TrackingProviderId): TrackingAccount? =
        decodeTrackingAccount(factory.get(profileId, FEATURE).data.first()[key(id)])

    suspend fun save(profileId: Int, id: TrackingProviderId, account: TrackingAccount) {
        val encoded = localStoreJson.encodeToString(TrackingAccount.serializer(), account)
        factory.get(profileId, FEATURE).edit { prefs -> prefs[key(id)] = encoded }
    }

    /**
     * Reads and writes in one edit, so nothing lands between them. A token refresh must use this, returning the new
     * account only while the stored one is still the one it refreshed (same refresh token), else null: null leaves the
     * entry as it is, so neither a sign-out nor a newer sign-in made during the network call is undone. Sign-out is
     * [clear], never this. This closes the stale-write window only: a provider whose refresh token rotates must also
     * run one refresh at a time per tracker, or the second network call fails.
     */
    suspend fun update(profileId: Int, id: TrackingProviderId, transform: (TrackingAccount?) -> TrackingAccount?) {
        factory.get(profileId, FEATURE).edit { prefs ->
            transform(decodeTrackingAccount(prefs[key(id)]))?.let { next ->
                prefs[key(id)] = localStoreJson.encodeToString(TrackingAccount.serializer(), next)
            }
        }
    }

    suspend fun clear(profileId: Int, id: TrackingProviderId) {
        factory.get(profileId, FEATURE).edit { prefs -> prefs -= key(id) }
    }
}

/** A missing, blank or unreadable entry reads as signed out; the cause is not logged (it may hold a token, KT-JSON-04). */
internal fun decodeTrackingAccount(raw: String?): TrackingAccount? {
    if (raw.isNullOrBlank()) return null
    return try {
        localStoreJson.decodeFromString(TrackingAccount.serializer(), raw).takeIf { it.accessToken.isNotBlank() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
