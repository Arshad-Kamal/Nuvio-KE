package com.nuvio.tv.core.tracking

import androidx.annotation.DrawableRes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The trackers this fork can sign in to (Spec 104). [storageId] is the on-disk and wire name: never rename it.
 * Declaration order is display order (Settings › Trackers lists MDBList first). Trakt stays out (Spec 104 D2).
 */
enum class TrackingProviderId(val storageId: String) {
    MDBLIST("mdblist"),
    SIMKL("simkl");

    companion object {
        fun fromStorage(value: String?): TrackingProviderId? {
            val normalized = value?.trim() ?: return null
            return entries.firstOrNull { it.storageId.equals(normalized, ignoreCase = true) }
        }
    }
}

enum class TrackingCapability {
    LIBRARY_READ,
    LIBRARY_WRITE,
    WATCHED_READ,
    WATCHED_WRITE,
    PROGRESS_READ,
    PROGRESS_WRITE,
    SCROBBLE
}

/** [logoRes]: the tracker's square glyph (Settings › Trackers, the sign-in, the Library source label); null draws a letter tile. */
data class TrackingProviderDescriptor(
    val id: TrackingProviderId,
    val displayName: String,
    val capabilities: Set<TrackingCapability>,
    @DrawableRes val logoRes: Int? = null
)

/**
 * One tracker. Builds carry no tracker id (Spec 106 D9): a tracker that needs one has a [setup] the user completes per
 * TV, and is bound in every build. An empty registry hides the Trackers hub (Spec 104 A6).
 */
interface TrackingProvider {
    val descriptor: TrackingProviderDescriptor

    /**
     * Signed in on the active profile with a token the service still accepts (false while [TrackingAccount.revoked],
     * and while the [setup] is not done).
     */
    val isAuthenticated: StateFlow<Boolean>

    /** Settings › Trackers device-code sign-in and disconnect (Spec 104 S3a); null for a tracker that uses [keyAuth]. */
    val deviceAuth: TrackingDeviceAuth?
        get() = null

    /** A pasted-key connect (Spec 105); null for a device-code tracker. */
    val keyAuth: TrackingKeyAuth?
        get() = null

    /** What must be pasted per TV before anyone can connect (Spec 106 K1-K5); null = nothing. */
    val setup: TrackingProviderSetup?
        get() = null

    /** The active profile's allowance, as the service last reported it (E2/E5); null = nothing to show. */
    val accountStatus: Flow<TrackingAccountStatus?>
        get() = flowOf(null)

    val scrobbler: TrackingScrobbler?
        get() = null
}

/** Disconnect through whichever sign-in the tracker has (Spec 104 A4). */
suspend fun TrackingProvider.disconnect(profileId: Int) {
    val device = deviceAuth
    if (device != null) device.disconnect(profileId) else keyAuth?.disconnect(profileId)
}

@Singleton
class TrackingProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingProvider>
) {
    private val providersById: Map<TrackingProviderId, TrackingProvider> = providers.associateBy { it.descriptor.id }

    init {
        require(providersById.size == providers.size) { "Two tracking providers share an id" }
    }

    /** False with no tracker bound (tests; every build binds Simkl, Spec 106 D9): Settings › Trackers is hidden. */
    val hasProviders: Boolean
        get() = providersById.isNotEmpty()

    fun providers(): List<TrackingProvider> = providersById.values.sortedBy { it.descriptor.id.ordinal }

    fun provider(id: TrackingProviderId): TrackingProvider? = providersById[id]

    fun isAuthenticated(id: TrackingProviderId): Boolean = providersById[id]?.isAuthenticated?.value == true

    fun connectedProviderIds(): Set<TrackingProviderId> = providers()
        .filter { it.isAuthenticated.value }
        .mapTo(linkedSetOf()) { it.descriptor.id }

    /** [connectedProviderIds] as it changes (sign-in, sign-out, a revoked token, a profile switch). */
    val connectedProviderIdsFlow: Flow<Set<TrackingProviderId>> = providers().let { all ->
        if (all.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(all.map { provider -> provider.isAuthenticated.map { connected -> provider.descriptor.id.takeIf { connected } } }) {
                it.filterNotNullTo(linkedSetOf())
            }.distinctUntilChanged()
        }
    }

    fun connectedScrobblers(): List<TrackingScrobbler> = providers()
        .filter { it.isAuthenticated.value && TrackingCapability.SCROBBLE in it.descriptor.capabilities }
        .mapNotNull(TrackingProvider::scrobbler)
}
