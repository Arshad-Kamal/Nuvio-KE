package com.nuvio.tv.core.tracking

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "TrackingSource"

/**
 * Owns the Continue Watching and Library source choices of the active profile (Spec 104 C1, C2, D9, D10).
 *
 * A switch only changes where reads come from: local progress, watched marks and the local library are never cleared,
 * so switching back to Nuvio loses nothing, with or without a Nuvio account (S2-R1; upstream NuvioTV cleared the local
 * watched marks here, the S1 red-team item). A tracker that is signed out by its service keeps the choice and reads
 * fall back to Nuvio until it reconnects ([effectiveTrackingSource], mockup C3).
 */
@Singleton
class TrackingSourceController @Inject constructor(
    private val settings: ContinueWatchingSettingsDataStore,
    private val registry: TrackingProviderRegistry,
    private val progressProviders: TrackingProgressProviderRegistry,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val enrichmentCache: ContinueWatchingEnrichmentCache,
    private val profileManager: ProfileManager
) {
    /** The Continue Watching source in use for the active profile. */
    val effectiveProgressSource: Flow<TrackingSource> =
        combine(settings.progressSource, registry.connectedProviderIdsFlow, ::effectiveTrackingSource).distinctUntilChanged()

    /** The Library source in use for the active profile. */
    val effectiveLibrarySource: Flow<TrackingSource> =
        combine(settings.librarySource, registry.connectedProviderIdsFlow, ::effectiveTrackingSource).distinctUntilChanged()

    /** The tracker Continue Watching reads from, or null for Nuvio (always null in a build with no tracker). */
    val activeProgressProvider: Flow<TrackingProgressProvider?> =
        if (progressProviders.providers().isEmpty()) flowOf(null)
        else effectiveProgressSource.map { it.providerId?.let(progressProviders::provider) }.distinctUntilChanged()

    /** The tracker the Library reads from, or null for Nuvio (always null in a build with no tracker). */
    val activeLibraryProvider: Flow<TrackingLibraryProvider?> =
        if (libraryProviders.providers().isEmpty()) flowOf(null)
        else effectiveLibrarySource.map { it.providerId?.let(libraryProviders::provider) }.distinctUntilChanged()

    /** The tracker the Library reads from, for its source label (mockup C4), or null for Nuvio. */
    val activeLibraryTracker: Flow<TrackingProviderDescriptor?> =
        activeLibraryProvider.map { provider -> provider?.providerId?.let(registry::provider)?.descriptor }.distinctUntilChanged()

    private val mutex = Mutex()

    /** Returns false (nothing saved) when [source] is a tracker that is not connected on the active profile. */
    suspend fun selectProgressSource(source: TrackingSource): Boolean = select(
        source = source,
        current = settings::getProgressSource,
        save = { profileId ->
            settings.setProgressSource(source, profileId)
            // The cached Continue Watching rows came from the old source; the next pipeline run fills them again.
            enrichmentCache.saveInProgressSnapshot(emptyList(), force = true, profileId = profileId)
            enrichmentCache.saveNextUpSnapshot(emptyList(), force = true, profileId = profileId)
        },
        refresh = { id -> progressProviders.provider(id)?.refresh(TrackingRefreshIntent.USER_INITIATED) }
    )

    /** Returns false (nothing saved) when [source] is a tracker that is not connected on the active profile. */
    suspend fun selectLibrarySource(source: TrackingSource): Boolean = select(
        source = source,
        current = settings::getLibrarySource,
        save = { profileId -> settings.setLibrarySource(source, profileId) },
        refresh = { id -> libraryProviders.provider(id)?.refresh(TrackingRefreshIntent.USER_INITIATED) }
    )

    /** Saves [source] for the active profile under the lock, then refreshes its tracker once, outside the lock. */
    private suspend fun select(
        source: TrackingSource,
        current: suspend (Int) -> TrackingSource,
        save: suspend (Int) -> Unit,
        refresh: suspend (TrackingProviderId) -> Unit
    ): Boolean {
        val profileId = profileManager.activeProfileId.value
        mutex.withLock {
            if (!isSelectable(source)) return false
            if (current(profileId) == source) return true
            save(profileId)
        }
        val providerId = source.providerId
        if (providerId != null && profileManager.activeProfileId.value == profileId) {
            refreshAfterSwitch(providerId) { refresh(providerId) }
        }
        return true
    }

    /**
     * The user disconnected [providerId] on [profileId] (mockup A4): every source pointing at it goes back to Nuvio.
     * A token the service revoked is not a disconnect: the choice stays (C3).
     */
    suspend fun onProviderDisconnected(providerId: TrackingProviderId, profileId: Int) {
        mutex.withLock {
            if (settings.getProgressSource(profileId).providerId == providerId) {
                settings.setProgressSource(TrackingSource.NUVIO, profileId)
            }
            if (settings.getLibrarySource(profileId).providerId == providerId) {
                settings.setLibrarySource(TrackingSource.NUVIO, profileId)
            }
        }
    }

    private fun isSelectable(source: TrackingSource): Boolean =
        source in availableTrackingSources(registry.connectedProviderIds())

    // The choice is already saved; a tracker that is rate limited or offline is retried at the next app resume.
    private suspend fun refreshAfterSwitch(providerId: TrackingProviderId, refresh: suspend () -> Unit) {
        trackerCallFailure(refresh)?.let { error ->
            Log.w(TAG, "${providerId.storageId} refresh after a source switch failed: ${error.javaClass.simpleName}")
        }
    }
}
