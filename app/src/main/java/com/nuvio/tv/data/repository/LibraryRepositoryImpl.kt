package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.core.tracking.routeTo
import com.nuvio.tv.core.tracking.trackerCallFailure
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.toSavedLibraryItem
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val LOGO_HYDRATION_BATCH = 20

/**
 * Pure planner for local-library logo backfill: the logo-less entries not
 * already marked, first [LOGO_HYDRATION_BATCH] of them, in emission order.
 * The caller marks the returned entries BEFORE launching hydration, so a
 * re-emission before a successful write lands cannot queue the same entry
 * twice; entries whose meta has no logo stay marked and are never re-queried.
 */
internal fun planLogoHydrationBatch(
    items: List<LibraryEntry>,
    markedIds: Set<String>
): List<LibraryEntry> = items
    .filter { it.logo == null && it.id !in markedIds }
    .take(LOGO_HYDRATION_BATCH)

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val libraryPreferences: LibraryPreferences,
    private val librarySyncService: LibrarySyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository,
    private val profileManager: ProfileManager,
    // Spec 104 S2c: with a tracker as the Library source, reads and writes go to it; the local library is left as is.
    private val trackingSources: TrackingSourceController,
) : LibraryRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydratedLogoIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var syncJob: Job? = null
    private val _isSyncingFromRemote = MutableStateFlow(false)
    var isSyncingFromRemote: Boolean
        get() = _isSyncingFromRemote.value
        set(value) { _isSyncingFromRemote.value = value }
    @Volatile
    var hasCompletedInitialPull = false

    private fun triggerRemoteSync(profileId: Int) {
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            librarySyncService.pushToRemote(profileId)
        }
    }

    override val isSyncing: Flow<Boolean> =
        trackingSources.activeLibraryProvider.routeTo(_isSyncingFromRemote, TrackingLibraryProvider::isRefreshing)

    private val localLibraryItems: Flow<List<LibraryEntry>> = libraryPreferences.libraryItems
        .map { items -> items.map(SavedLibraryItem::toLibraryEntry) }
        .onEach { items ->
            // Logo hydration: backfill clear-logo art for local entries missing it.
            // See planLogoHydrationBatch for the mark-then-batch policy; add() is the
            // atomic claim, so concurrent collectors cannot both take an id.
            val batch = planLogoHydrationBatch(items, hydratedLogoIds)
                .filter { hydratedLogoIds.add(it.id) }
            if (batch.isEmpty()) return@onEach
            syncScope.launch { hydrateLibraryLogos(batch) }
        }
        .distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> =
        trackingSources.activeLibraryProvider.routeTo(localLibraryItems, TrackingLibraryProvider::items)

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> =
        trackingSources.activeLibraryProvider.routeTo(libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)) {
            it.isInLibrary(itemId, itemType)
        }.distinctUntilChanged()

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        val profileId = profileManager.activeProfileId.value
        trackingSources.activeLibraryProvider.first()?.let { provider ->
            return writeToTracker(provider, profileId, item) { !provider.isInLibrary(item.itemId, item.itemType).first() }
        }
        val isInLocal = libraryPreferences.containsItem(
            itemId = item.itemId,
            itemType = item.itemType,
            profileId = profileId
        )
        writeMembership(item, profileId, inLibrary = !isInLocal)
    }

    override suspend fun setInLibrary(item: LibraryEntryInput, inLibrary: Boolean) {
        val profileId = profileManager.activeProfileId.value
        trackingSources.activeLibraryProvider.first()?.let { provider ->
            return writeToTracker(provider, profileId, item) { inLibrary }
        }
        val isInLocal = libraryPreferences.containsItem(
            itemId = item.itemId,
            itemType = item.itemType,
            profileId = profileId
        )
        if (isInLocal == inLibrary) return
        writeMembership(item, profileId, inLibrary)
    }

    /**
     * A tracker that fails (offline, rate limited) leaves the title as it was. The failure is logged by tracker and error
     * class only and rethrown, so the callers' error paths (Detail's toast and picker error) run as for a local failure.
     */
    private suspend fun writeToTracker(
        provider: TrackingLibraryProvider,
        profileId: Int,
        item: LibraryEntryInput,
        wanted: suspend () -> Boolean
    ) {
        trackerCallFailure { provider.setInLibrary(profileId, item, wanted()) }?.let { error ->
            Log.w("LibraryRepo", "${provider.providerId.storageId} library write failed: ${error.javaClass.simpleName}")
            throw error
        }
    }

    /** Shared add/remove + sync trigger, so toggle and set-to-state write identical payloads. */
    private suspend fun writeMembership(item: LibraryEntryInput, profileId: Int, inLibrary: Boolean) {
        if (inLibrary) {
            libraryPreferences.addItem(
                item = item.toSavedLibraryItem(),
                profileId = profileId
            )
        } else {
            libraryPreferences.removeItem(
                itemId = item.itemId,
                itemType = item.itemType,
                profileId = profileId
            )
        }
        triggerRemoteSync(profileId)
    }


    private suspend fun hydrateLibraryLogos(batch: List<LibraryEntry>) {
        batch.forEach { entry ->
            runCatching {
                val result = metaRepository.getMetaFromPrimaryAddon(entry.type, entry.id)
                    .firstOrNull { it is NetworkResult.Success }
                val logo = (result as? NetworkResult.Success)?.data?.logo
                if (logo != null) {
                    libraryPreferences.updateLogo(entry.id, entry.type, logo)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w("LibraryRepo", "Logo hydration failed for ${entry.id}", error)
            }
        }
    }
}
