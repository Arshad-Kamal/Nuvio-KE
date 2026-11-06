package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.StartupSyncPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"
private const val FORCE_RESYNC_MIN_INTERVAL_MS = 30_000L
private const val FULL_STARTUP_PULL_TTL_MS = 6 * 60 * 60 * 1000L
private const val FOREGROUND_ACTIVITY_PULL_DELAY_MS = 2_500L
private const val FOREGROUND_ACTIVITY_PULL_MIN_INTERVAL_MS = 2 * 60_000L
private const val PERIODIC_SURFACE_PULL_INTERVAL_MS = 15 * 60_000L

internal data class SurfacePullFreshness(
    val key: String? = null,
    val pulledAtMs: Long = 0L
) {
    fun isRecent(candidateKey: String, nowMs: Long, minIntervalMs: Long): Boolean {
        return key == candidateKey &&
            pulledAtMs > 0L &&
            nowMs >= pulledAtMs &&
            nowMs - pulledAtMs < minIntervalMs
    }
}

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val addonSyncService: AddonSyncService,
    private val collectionSyncService: CollectionSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val profileSyncService: ProfileSyncService,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val profileManager: ProfileManager,
    private val startupSyncPreferences: StartupSyncPreferences,
    private val pushHealth: SyncPushHealth,
    private val cwEnrichmentCache: com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Scheduler state below is touched from Main (requestSyncNow) and IO (auth collector,
    // job bodies): the job fields, pendingResync* writes and lastPulled* reads and writes
    // hold this object's monitor (L9-66). The force* flags are volatile, last-writer-wins.
    private var startupPullJob: Job? = null
    private var activityPullJob: Job? = null
    private var periodicSurfacePullJob: Job? = null
    private var lastPulledKey: String? = null
    private var lastPulledIncludedProfileSettings: Boolean = false
    private var lastPulledAtMs: Long = 0L
    private var activityPullFreshness = SurfacePullFreshness()
    @Volatile
    private var forceSyncRequested: Boolean = false
    @Volatile
    private var forceSyncIncludesProfileSettings: Boolean = true
    @Volatile
    private var pendingResyncKey: String? = null
    @Volatile
    private var pendingResyncIncludesProfileSettings: Boolean = false

    /** Pull job bodies (startup or activity) running now; > 0 = the sync line says syncing. */
    private val runningPulls = MutableStateFlow(0)

    /** The Account pane's sync line; null until the stored last-ok time has loaded (else it would read "never"). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val syncStatus: StateFlow<SyncStatus?> = combine(
        runningPulls,
        profileManager.activeProfileId.flatMapLatest { profileId ->
            startupSyncPreferences.lastSyncOkAtMs(profileId).map { profileId to it }
        },
        pushHealth.state
    ) { running, (profileId, lastOkAtMs), health ->
        SyncStatus(
            running = running > 0,
            lastOkAtMs = lastOkAtMs,
            pending = pendingChanges(health, profileId)
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        scope.launch {
            // Collector-local: push failures recorded while signed out (ungated profile edits) or
            // by a push still in flight at sign-out must not show as the next account's waiting changes.
            var healthUserId: String? = null
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.FullAccount -> {
                        if (state.userId != healthUserId) {
                            pushHealth.clear()
                            healthUserId = state.userId
                        }
                        val force = forceSyncRequested
                        val includeProfileSettings = if (force) forceSyncIncludesProfileSettings else true
                        val started = scheduleStartupPull(
                            userId = state.userId,
                            force = force,
                            includeProfileSettings = includeProfileSettings
                        )
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.SignedOut -> synchronized(this@StartupSyncService) {
                        startupPullJob?.cancel()
                        startupPullJob = null
                        activityPullJob?.cancel()
                        activityPullJob = null
                        periodicSurfacePullJob?.cancel()
                        periodicSurfacePullJob = null
                        lastPulledKey = null
                        lastPulledIncludedProfileSettings = false
                        lastPulledAtMs = 0L
                        activityPullFreshness = SurfacePullFreshness()
                        forceSyncRequested = false
                        forceSyncIncludesProfileSettings = true
                        pendingResyncKey = null
                        pendingResyncIncludesProfileSettings = false
                        pushHealth.clear()
                        healthUserId = null
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    @Synchronized
    fun startPeriodicSurfacePulls() {
        if (periodicSurfacePullJob?.isActive == true) return
        periodicSurfacePullJob = scope.launch {
            while (true) {
                delay(PERIODIC_SURFACE_PULL_INTERVAL_MS)
                scheduleActivityPull(reason = "periodic")
            }
        }
    }

    @Synchronized
    fun stopPeriodicSurfacePulls() {
        periodicSurfacePullJob?.cancel()
        periodicSurfacePullJob = null
    }

    /**
     * Forces a startup pull. Returns whether one started now: false when signed out, parked
     * behind a running pull (re-runs after only if that pull fails or does not cover it), or
     * throttled within [FORCE_RESYNC_MIN_INTERVAL_MS] of the last pull of the same key.
     */
    fun requestSyncNow(includeProfileSettings: Boolean = true): Boolean {
        forceSyncRequested = true
        forceSyncIncludesProfileSettings = forceSyncIncludesProfileSettings || includeProfileSettings
        val state = authManager.authState.value as? AuthState.FullAccount ?: return false
        val started = scheduleStartupPull(
            userId = state.userId,
            force = true,
            includeProfileSettings = includeProfileSettings
        )
        if (started) forceSyncRequested = false
        return started
    }

    fun requestForegroundSync() {
        scheduleActivityPull(
            reason = "foreground",
            delayMs = FOREGROUND_ACTIVITY_PULL_DELAY_MS,
            minIntervalMs = FOREGROUND_ACTIVITY_PULL_MIN_INTERVAL_MS
        )
    }

    fun requestAddonSyncNow() {
        val profileId = profileManager.activeProfileId.value
        Log.d(TAG, "Manual addon sync enqueued for profile $profileId")
        scope.launch {
            Log.d(TAG, "Manual addon sync starting for profile $profileId")

            addonRepository.isSyncingFromRemote = true
            try {
                val remoteAddonUrls = addonSyncService.fetchAndApplyRemoteAddonUrls(profileId).getOrElse { throw it }

                addonRepository.reconcileWithRemoteAddonUrls(
                    remoteUrls = remoteAddonUrls,
                    removeMissingLocal = true,
                    profileId = profileId
                )

                Log.d(TAG, "Manual addon sync pulled ${remoteAddonUrls.size} addons for profile $profileId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Manual addon sync failed for profile $profileId", e)
            } finally {
                addonRepository.isSyncingFromRemote = false
            }
        }
    }

    @Synchronized
    private fun scheduleActivityPull(
        reason: String,
        delayMs: Long = 0L,
        minIntervalMs: Long = 0L
    ): Boolean {
        val state = authManager.authState.value as? AuthState.FullAccount ?: return false
        val key = pullKey(state.userId)
        val now = SystemClock.elapsedRealtime()
        if (startupPullJob?.isActive == true || activityPullJob?.isActive == true) return false
        if (activityPullFreshness.isRecent(key, now, minIntervalMs)) return false

        activityPullJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            val currentState = authManager.authState.value as? AuthState.FullAccount ?: return@launch
            if (pullKey(currentState.userId) != key || startupPullJob?.isActive == true) return@launch
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Activity sync started profile=$profileId reason=$reason")
            runningPulls.update { it + 1 }
            try {
                val succeeded = coroutineScope {
                    val watchState = async { pullPeriodicWatchState(profileId) }
                    val library = async { pullPeriodicLibrary(profileId) }
                    watchState.await() && library.await()
                }
                if (succeeded) {
                    synchronized(this@StartupSyncService) {
                        activityPullFreshness = SurfacePullFreshness(
                            key = key,
                            pulledAtMs = SystemClock.elapsedRealtime()
                        )
                    }
                    // Both activity steps succeeded: refresh the sync line (Spec 31 defaults).
                    markSyncOk(profileId)
                }
                Log.d(TAG, "Activity sync completed profile=$profileId reason=$reason succeeded=$succeeded")
            } finally {
                runningPulls.update { it - 1 }
            }
        }
        return true
    }

    private suspend fun pullPeriodicWatchState(profileId: Int): Boolean {
        if (authManager.authState.value !is AuthState.FullAccount) return false

        Log.d(TAG, "Periodic watch state pull: profile=$profileId")

        val watchedItemsSucceeded = pullWatchedItemsDelta(profileId)
        val watchProgressSucceeded = syncWatchProgressRemote(
            profileId = profileId,
            failureMessage = "Periodic watch progress pull failed",
            useSnapshot = false
        ).isSuccess
        return watchedItemsSucceeded && watchProgressSucceeded
    }

    private suspend fun pullPeriodicLibrary(profileId: Int): Boolean {
        if (authManager.authState.value !is AuthState.FullAccount) return false

        Log.d(TAG, "Periodic library pull requested profile=$profileId")
        return pullNuvioLibrary(profileId)
    }

    private fun pullKey(userId: String, profileId: Int = profileManager.activeProfileId.value): String =
        "${userId}_p${profileId}"

    @Synchronized
    private fun scheduleStartupPull(
        userId: String,
        force: Boolean = false,
        includeProfileSettings: Boolean = true
    ): Boolean {
        // The profile is fixed here and passed down: the job pulls exactly the profile its
        // key names, even if the user switches profile while it runs (L9-66).
        val profileId = profileManager.activeProfileId.value
        val key = pullKey(userId, profileId)
        val now = SystemClock.elapsedRealtime()
        val sameKey = lastPulledKey == key
        val coversProfileSettings = !includeProfileSettings || lastPulledIncludedProfileSettings
        if (!force && sameKey && coversProfileSettings) {
            return false
        }
        if (
            force &&
            sameKey &&
            coversProfileSettings &&
            startupPullJob?.isActive != true &&
            now - lastPulledAtMs < FORCE_RESYNC_MIN_INTERVAL_MS
        ) {
            return false
        }
        // Never cancel an active sync — it may be mid-write to DataStore.
        // Instead, schedule a follow-up sync after the current one finishes.
        if (startupPullJob?.isActive == true) {
            if (force) {
                pendingResyncKey = key
                pendingResyncIncludesProfileSettings =
                    pendingResyncIncludesProfileSettings || includeProfileSettings
            }
            return false
        }
        val cancelledActivityJob = activityPullJob
        cancelledActivityJob?.cancel()
        activityPullJob = null

        runningPulls.update { it + 1 }
        startupPullJob = scope.launch {
            // cancel() does not wait: let the activity job's finally blocks reset the shared
            // isSyncingFromRemote flags BEFORE this job sets them (L9-76).
            cancelledActivityJob?.join()
            val maxAttempts = 3
            var syncCompleted = false
            for (attempt in 1..maxAttempts) {
                val result = pullRemoteData(
                    userId = userId,
                    profileId = profileId,
                    force = force,
                    includeProfileSettings = includeProfileSettings
                )
                if (result.isSuccess) {
                    synchronized(this@StartupSyncService) {
                        lastPulledKey = key
                        lastPulledIncludedProfileSettings = includeProfileSettings
                        lastPulledAtMs = SystemClock.elapsedRealtime()
                        activityPullFreshness = SurfacePullFreshness(
                            key = key,
                            pulledAtMs = lastPulledAtMs
                        )
                    }
                    syncCompleted = true
                    break
                }

                Log.w(TAG, "Startup sync attempt $attempt failed for key=$key", result.exceptionOrNull())
                if (attempt < maxAttempts) {
                    delay(3000)
                }
            }
            
            // Release the job slot in the same critical section that drains the parked
            // request: a requestSyncNow after this point schedules directly instead of
            // parking behind a job that will never drain again, and the drained request is
            // scheduled below without seeing this job as active (L9-66).
            val thisJob = coroutineContext.job
            val resyncIncludesProfileSettings = synchronized(this@StartupSyncService) {
                if (startupPullJob === thisJob) startupPullJob = null
                val resyncKey = pendingResyncKey ?: return@synchronized null
                val includes = pendingResyncIncludesProfileSettings
                pendingResyncKey = null
                pendingResyncIncludesProfileSettings = false
                includes.takeIf {
                    !syncCompleted ||
                        resyncKey != lastPulledKey ||
                        (includes && !lastPulledIncludedProfileSettings)
                }
            }
            if (resyncIncludesProfileSettings != null) {
                val state = authManager.authState.value as? AuthState.FullAccount ?: return@launch
                scheduleStartupPull(
                    userId = state.userId,
                    force = true,
                    includeProfileSettings = resyncIncludesProfileSettings
                )
            }
        }.also { job ->
            // Runs once per job, also when cancelled before it started: balances the increment above.
            job.invokeOnCompletion { runningPulls.update { it - 1 } }
        }
        return true
    }

    /**
     * One startup pull: broad data, then watched items + watch progress as a snapshot,
     * or as deltas (warm) when this key pulled fully within [FULL_STARTUP_PULL_TTL_MS].
     * Each isSyncingFromRemote flag is reset by the finally of the step that set it;
     * resetting them here as well could clear a concurrent requestAddonSyncNow's flag.
     */
    private suspend fun pullRemoteData(
        userId: String,
        profileId: Int,
        force: Boolean,
        includeProfileSettings: Boolean
    ): Result<Unit> {
        try {
            val syncState = startupSyncPreferences.getState(profileId)
            val pulledThisKey = synchronized(this) {
                lastPulledKey == pullKey(userId, profileId) && lastPulledAtMs > 0L
            }
            val warm = !force &&
                pulledThisKey &&
                syncState.lastFullPullUserId == userId &&
                syncState.lastFullPullAtMs > 0L &&
                System.currentTimeMillis() - syncState.lastFullPullAtMs < FULL_STARTUP_PULL_TTL_MS &&
                (!includeProfileSettings || syncState.lastFullPullIncludedProfileSettings)

            Log.d(TAG, "Pulling remote data for profile $profileId warm=$warm")
            val broad = pullBroadRemoteData(profileId, includeProfileSettings)

            Log.d(TAG, "Watch progress sync: profile=$profileId warm=$warm")
            val watchedItemsOk =
                if (warm) pullWatchedItemsDelta(profileId) else pullWatchedItemsSnapshot(profileId)
            val watchProgressOk = syncWatchProgressRemote(
                profileId = profileId,
                failureMessage = "Failed to sync watch progress (warm=$warm), continuing",
                useSnapshot = !warm
            ).isSuccess
            val outcomes = mapOf(
                CorePullStep.PROFILES to true, // reached only when the profiles pull succeeded (it throws)
                CorePullStep.ADDONS to broad.addonsOk,
                CorePullStep.LIBRARY to broad.libraryOk,
                CorePullStep.WATCHED_ITEMS to watchedItemsOk,
                CorePullStep.WATCH_PROGRESS to watchProgressOk
            )
            // Warm/cold bookkeeping: marked even when steps failed (they keep local data).
            startupSyncPreferences.markFullPull(
                profileId = profileId,
                userId = userId,
                includeProfileSettings = includeProfileSettings
            )
            if (pullSucceeded(outcomes)) {
                markSyncOk(profileId)
            } else {
                Log.w(TAG, "Startup sync finished with failed steps: $outcomes")
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            // Review 2026-08-29-110531: without this clause the caller converts a
            // cancellation into Result.failure on a cancelled scope
            // (startupPullJob?.cancel() on sign-out is the realistic trigger).
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Startup sync failed", e)
            return Result.failure(e)
        }
    }

    private data class BroadPullOutcome(val addonsOk: Boolean, val libraryOk: Boolean)

    /**
     * The sync-line stamp is display state: a failed write is logged, never fails the pull
     * (a startup retry) or escapes the activity job (no handler on [scope]).
     */
    private suspend fun markSyncOk(profileId: Int) {
        try {
            startupSyncPreferences.markSyncOk(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store the last sync time for profile $profileId", e)
        }
    }

    /** Pulls everything but watch state; a profiles failure throws, addons / library report their outcome. */
    private suspend fun pullBroadRemoteData(
        profileId: Int,
        includeProfileSettings: Boolean
    ): BroadPullOutcome {
        profileSyncService.pullFromRemote().getOrElse { throw it }
        Log.d(TAG, "Pulled profiles from remote")

        if (includeProfileSettings) {
            profileSettingsSyncService.pullCurrentProfileFromRemote(profileId)
                .onSuccess { applied ->
                    Log.d(TAG, "Profile settings blob pull completed for profile $profileId (applied=$applied)")
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to pull profile settings blob, keeping local settings", e)
                }
        }

        return coroutineScope {
            val libraryJob = async { pullNuvioLibrary(profileId) }

            val addonJob = async {
                addonRepository.isSyncingFromRemote = true
                try {
                    val remoteAddonUrls = addonSyncService.fetchAndApplyRemoteAddonUrls(profileId).getOrElse { throw it }
                    addonRepository.reconcileWithRemoteAddonUrls(
                        remoteUrls = remoteAddonUrls,
                        removeMissingLocal = true,
                        profileId = profileId
                    )
                    Log.d(TAG, "Pulled ${remoteAddonUrls.size} addons from remote for profile $profileId")
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull addons from remote, keeping local cache", e)
                    false
                } finally {
                    addonRepository.isSyncingFromRemote = false
                }
            }

            val collectionJob = async {
                try {
                    collectionSyncService.pullFromRemote(profileId)
                        .onSuccess { applied ->
                            Log.d(TAG, "Collections pull completed for profile $profileId (applied=$applied)")
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Failed to pull collections from remote, keeping local", e)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull collections from remote", e)
                }
            }

            val homeCatalogJob = async {
                try {
                    homeCatalogSettingsSyncService.pullFromRemote(profileId)
                        .onSuccess { applied ->
                            Log.d(TAG, "Home catalog settings pull completed for profile $profileId (applied=$applied)")
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Failed to pull home catalog settings from remote, keeping local", e)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull home catalog settings from remote", e)
                }
            }

            val addonsOk = addonJob.await()
            collectionJob.await()
            homeCatalogJob.await()
            BroadPullOutcome(addonsOk = addonsOk, libraryOk = libraryJob.await())
        }
    }

    private suspend fun pullNuvioLibrary(profileId: Int): Boolean {
        libraryRepository.isSyncingFromRemote = true
        return try {
            val result = librarySyncService.syncFromRemote(profileId).getOrElse { throw it }
            libraryRepository.hasCompletedInitialPull = true
            Log.d(
                TAG,
                "Library pull completed profile=$profileId snapshot=${result.usedSnapshot} " +
                    "upserts=${result.appliedUpserts} deletes=${result.appliedDeletes}"
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            libraryRepository.hasCompletedInitialPull = true
            Log.e(TAG, "Library pull failed profile=$profileId, continuing", e)
            false
        } finally {
            libraryRepository.isSyncingFromRemote = false
        }
    }

    private suspend fun pullWatchedItemsDelta(profileId: Int): Boolean {
        return try {
            Log.d(TAG, "Starting watched items delta sync for profile $profileId")
            val watchedItemsResult = watchedItemsSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            Log.d(
                TAG,
                "Watched items sync applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes (snapshot=${watchedItemsResult.usedSnapshot})"
            )
            // Unconfirmed after a DELTA pull (e.g. the startup snapshot failed): a full push
            // confirms the profile so incremental pushes advance the deletion watermark again
            // (PushWatermarkGate). Delta pulls applied remote deletes locally first; a snapshot
            // result never triggers it (an empty snapshot keeps every local item unreported).
            val unconfirmedAfterDelta = !watchedItemsResult.usedSnapshot &&
                !watchedItemsSyncService.isPushConfirmed(profileId)
            if (watchedItemsResult.preservedLocalItems || unconfirmedAfterDelta) {
                Log.d(TAG, "Detected unsynced or unconfirmed watched items, pushing to remote")
                watchedItemsSyncService.pushToRemote(profileId)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items, continuing with other syncs", e)
            false
        }
    }

    private suspend fun pullWatchedItemsSnapshot(profileId: Int): Boolean {
        return try {
            Log.d(TAG, "Starting watched items snapshot sync for profile $profileId")
            val watchedItemsResult = watchedItemsSyncService.syncSnapshotFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            Log.d(
                TAG,
                "Watched items snapshot applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes (snapshot=${watchedItemsResult.usedSnapshot})"
            )
            if (watchedItemsResult.preservedLocalItems) {
                Log.d(TAG, "Detected unsynced watched items after snapshot, pushing to remote")
                watchedItemsSyncService.pushToRemote(profileId)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items snapshot, continuing with other syncs", e)
            false
        }
    }

    /** Evicts CW cache cards of [profileId] whose content has no progress entry left (L9-09). */
    private suspend fun evictDeletedFromContinueWatchingCache(profileId: Int) {
        val currentContentIds = watchProgressPreferences.getAllRawEntries(profileId)
            .values.mapTo(mutableSetOf()) { it.contentId }
        val cachedInProgress = cwEnrichmentCache.getInProgressSnapshot(profileId)
        val cachedNextUp = cwEnrichmentCache.getNextUpSnapshot(profileId)
        val filteredInProgress = cachedInProgress.filter { it.contentId in currentContentIds }
        val filteredNextUp = cachedNextUp.filter { it.contentId in currentContentIds }
        if (filteredInProgress.size != cachedInProgress.size) {
            cwEnrichmentCache.saveInProgressSnapshot(filteredInProgress, force = true, profileId = profileId)
        }
        if (filteredNextUp.size != cachedNextUp.size) {
            cwEnrichmentCache.saveNextUpSnapshot(filteredNextUp, force = true, profileId = profileId)
        }
    }

    private suspend fun syncWatchProgressRemote(
        profileId: Int,
        failureMessage: String,
        useSnapshot: Boolean
    ): Result<Unit> {
        watchProgressRepository.isSyncingFromRemote = true
        try {
            val syncResult = if (useSnapshot) {
                watchProgressSyncService.syncSnapshotFromRemote(profileId).getOrElse { throw it }
            } else {
                watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            }
            watchProgressRepository.hasCompletedInitialPull = true
            Log.d(
                TAG,
                "Watch progress sync applied ${syncResult.upsertedEntries} upserts and ${syncResult.deletedEntries} deletes (snapshot=${syncResult.usedSnapshot})"
            )
            // Evict deleted entries from CW enrichment cache so stale items
            // don't persist on screen via loadContinueWatching() cache restore.
            if (syncResult.deletedEntries > 0) {
                evictDeletedFromContinueWatchingCache(profileId)
            }
            // Unconfirmed after a delta pull: same rule as pullWatchedItemsDelta.
            val unconfirmedAfterDelta = !syncResult.usedSnapshot &&
                !watchProgressSyncService.isPushConfirmed(profileId)
            if (syncResult.preservedLocalItems || unconfirmedAfterDelta) {
                Log.d(TAG, "Detected unsynced or unconfirmed watch progress, pushing to remote")
                watchProgressSyncService.pushToRemote(profileId)
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, failureMessage, e)
            return Result.failure(e)
        } finally {
            watchProgressRepository.isSyncingFromRemote = false
        }
    }
}
