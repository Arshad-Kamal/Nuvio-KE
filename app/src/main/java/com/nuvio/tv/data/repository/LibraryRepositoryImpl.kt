package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    override val isSyncing: Flow<Boolean> = _isSyncingFromRemote

    override val libraryItems: Flow<List<LibraryEntry>> = libraryPreferences.libraryItems
        .map { items ->
            items.map { saved ->
                LibraryEntry(
                    id = saved.id,
                    type = saved.type,
                    name = saved.name,
                    poster = saved.poster,
                    posterShape = saved.posterShape,
                    background = saved.background,
                    logo = saved.logo,
                    description = saved.description,
                    releaseInfo = saved.releaseInfo,
                    imdbRating = saved.imdbRating,
                    genres = saved.genres,
                    addonBaseUrl = saved.addonBaseUrl,
                    listedAt = saved.addedAt
                )
            }
        }
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

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> =
        libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            .distinctUntilChanged()

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        val profileId = profileManager.activeProfileId.value
        val isInLocal = libraryPreferences.containsItem(
            itemId = item.itemId,
            itemType = item.itemType,
            profileId = profileId
        )
        writeMembership(item, profileId, inLibrary = !isInLocal)
    }

    override suspend fun setInLibrary(item: LibraryEntryInput, inLibrary: Boolean) {
        val profileId = profileManager.activeProfileId.value
        val isInLocal = libraryPreferences.containsItem(
            itemId = item.itemId,
            itemType = item.itemType,
            profileId = profileId
        )
        if (isInLocal == inLibrary) return
        writeMembership(item, profileId, inLibrary)
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

    private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem {
        return SavedLibraryItem(
            id = itemId,
            type = itemType,
            name = title,
            poster = poster,
            posterShape = posterShape,
            background = background,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl,
            logo = logo
        )
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
