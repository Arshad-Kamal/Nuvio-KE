package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.recommendations.WatchNextRemoval
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.core.util.parseRuntimeMinutes
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.progressKey
import com.nuvio.tv.domain.model.watchProgressKey
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val REMOTE_PROGRESS_WRITE_DEDUP_WINDOW_MS = 5_000L
private const val PROGRESS_ARTWORK_HYDRATION_BATCH = 10

/**
 * Pure planner for progress artwork backfill: artwork-less entries not already
 * claimed, first [PROGRESS_ARTWORK_HYDRATION_BATCH] of them, in emission order.
 * The caller claims the whole batch BEFORE launching hydration (mirrors
 * planLogoHydrationBatch), so the hydration's own writes re-emitting allProgress
 * cannot relaunch hydration for entries still waiting in the batch. Claims are per profile
 * ([progressHydrationClaim]): another profile's claim must not block this one's entry (T31, P44).
 */
internal fun planProgressArtworkHydrationBatch(
    items: List<WatchProgress>,
    claims: Set<String>,
    profileId: Int
): List<WatchProgress> = items
    .filter {
        it.poster == null && it.backdrop == null &&
            progressHydrationClaim(profileId, it.contentId) !in claims
    }
    .take(PROGRESS_ARTWORK_HYDRATION_BATCH)

internal fun progressHydrationClaim(profileId: Int, contentId: String): String = "$profileId:$contentId"

private data class RemoteProgressWriteKey(
    val profileId: Int,
    val progressKey: String
)

private data class RemoteProgressWrite(
    val progress: WatchProgress,
    val sentAtMs: Long
)

internal class RemoteProgressWriteDeduplicator(
    private val windowMs: Long = REMOTE_PROGRESS_WRITE_DEDUP_WINDOW_MS
) {
    private val lock = Any()
    private val recentWrites = mutableMapOf<RemoteProgressWriteKey, RemoteProgressWrite>()

    fun shouldSend(
        profileId: Int,
        progressKey: String,
        progress: WatchProgress,
        nowMs: Long
    ): Boolean = synchronized(lock) {
        recentWrites.entries.removeAll { (_, write) ->
            val elapsedMs = nowMs - write.sentAtMs
            elapsedMs < 0L || elapsedMs >= windowMs
        }
        val key = RemoteProgressWriteKey(profileId, progressKey)
        val normalizedProgress = progress.copy(lastWatched = 0L)
        if (recentWrites[key]?.progress == normalizedProgress) {
            return@synchronized false
        }
        recentWrites[key] = RemoteProgressWrite(normalizedProgress, nowMs)
        true
    }
}

@Singleton
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val continueWatchingSettings: ContinueWatchingSettingsDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val tvRecommendationManager: TvRecommendationManager,
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
    }

    private data class EpisodeMetadata(
        val runtimeMs: Long = 0L
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>,
        val runtimeMs: Long = 0L
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Read in allProgress.onEach on collector threads and written there too: concurrent set.
    private val hydrationClaims: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var syncJob: Job? = null
    private var watchedItemsSyncJob: Job? = null
    private val pendingWatchedItemsLock = Any()
    private val pendingWatchedItems = linkedMapOf<WatchedItemSyncKey, WatchedItem>()
    private val remoteProgressWriteDeduplicator = RemoteProgressWriteDeduplicator()
    @Volatile
    var isSyncingFromRemote = false
    // Written by StartupSyncService / AccountViewModel, read by the sync triggers on other threads.
    @Volatile
    var hasCompletedInitialPull = false
    @Volatile
    var hasCompletedInitialWatchedItemsPull = false

    // Optimistic watched-movie toggles, keyed by the profile that made them (Spec 48 P2, NM 198acd375).
    private val optimisticWatchedMovieAdditions = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())
    private val optimisticWatchedMovieRemovals = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())

    /**
     * Drops the launcher Watch Next rows of items whose progress was removed or finished, so
     * a row cannot relaunch them: an episode's row when season/episode are given, else the
     * whole title; one launcher scan per call. Callers run it LAST, so a cancellation here can
     * only skip the prune, never their remote delete or sync trigger; a launcher failure is
     * logged and never fails the repository operation.
     */
    private suspend fun pruneWatchNext(removals: List<WatchNextRemoval>) {
        try {
            tvRecommendationManager.onProgressRemoved(removals.distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Watch Next prune failed for ${removals.size} item(s)", e)
        }
    }

    private fun triggerRemoteSync(profileId: Int = profileManager.activeProfileId.value) {
        if (!authManager.isAuthenticated) return
        if (isSyncingFromRemote || !hasCompletedInitialPull) {
            // The change stays local for now: keep the watermark below it until a full push.
            watchProgressSyncService.markPushSkipped(profileId)
            return
        }
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(2000)
            withContext(NonCancellable) {
                watchProgressSyncService.pushToRemote(profileId)
            }
        }
    }

    private fun triggerWatchedItemsSync(
        items: Collection<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (items.isEmpty()) return
        if (!authManager.isAuthenticated) return
        if (isSyncingFromRemote || !hasCompletedInitialWatchedItemsPull) {
            // The change stays local for now: keep the watermark below it until a full push.
            watchedItemsSyncService.markPushSkipped(profileId)
            return
        }
        synchronized(pendingWatchedItemsLock) {
            items.forEach { item ->
                pendingWatchedItems[item.syncKey(profileId)] = item
            }
        }
        watchedItemsSyncJob?.cancel()
        watchedItemsSyncJob = syncScope.launch {
            delay(2000)
            // Pending items keep the profile they were marked under: a re-trigger after a
            // profile switch must not push profile A's items as profile B's.
            val batchesByProfile = synchronized(pendingWatchedItemsLock) {
                pendingWatchedItems.entries
                    .groupBy({ it.key.profileId }, { it.value })
                    .also { pendingWatchedItems.clear() }
            }
            if (batchesByProfile.isEmpty()) return@launch
            withContext(NonCancellable) {
                batchesByProfile.forEach { (batchProfileId, batch) ->
                    watchedItemsSyncService.pushItemsToRemote(batch, profileId = batchProfileId)
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            runtimeMs = (video.runtime ?: 0).toLong() * 60_000L
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.backdropUrl,
                    logo = meta.logo,
                    episodes = episodes,
                    runtimeMs = (parseRuntimeMinutes(meta.runtime) ?: 0).toLong() * 60_000L
                )
            }
        }
        return null
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = watchProgressPreferences.allProgress
            .onEach { items ->
                // Captured at the claim: the merge must not land in whichever profile is active
                // when the fetch ends (T31, P44). A list still in flight across a profile switch
                // can be claimed for the new profile; accepted: the merge only fills empty
                // content fields of an existing same-key entry, and the old one stays unclaimed.
                val profileId = profileManager.activeProfileId.value
                // add() is the atomic claim: concurrent collectors cannot both take an id.
                val batch = planProgressArtworkHydrationBatch(items, hydrationClaims, profileId)
                    .filter { hydrationClaims.add(progressHydrationClaim(profileId, it.contentId)) }
                if (batch.isNotEmpty()) {
                    syncScope.launch { hydrateProgressArtwork(batch, profileId) }
                }
            }

    override val watchedItems: Flow<List<WatchedItem>>
        get() = watchedItemsPreferences.allItems.distinctUntilChanged()

    override fun getProgress(contentId: String): Flow<WatchProgress?> =
        watchProgressPreferences.getProgress(contentId)

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> =
        watchProgressPreferences.getEpisodeProgress(contentId, season, episode)

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        watchProgressPreferences.getAllEpisodeProgress(contentId)

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> {
        // Use watched items (fully synced with pagination) to build seeds
        // instead of watch progress (limited to 1000 entries).
        return combine(
            watchedItemsPreferences.allItems,
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
        ) { items, useFurthest ->
            items
                .filter { item ->
                    (item.contentType.equals("series", ignoreCase = true) ||
                        item.contentType.equals("tv", ignoreCase = true)) &&
                        item.season != null &&
                        item.episode != null &&
                        item.season != 0 &&
                        !isMalformedNextUpSeedContentId(item.contentId)
                }
                .groupBy { it.contentId }
                .mapNotNull { (_, episodes) ->
                    val latest = episodes.maxWithOrNull(
                        if (useFurthest) {
                            compareBy<WatchedItem> { it.season ?: 0 }
                                .thenBy { it.episode ?: 0 }
                                .thenBy { it.watchedAt }
                        } else {
                            compareBy<WatchedItem> { it.watchedAt }
                                .thenBy { it.season ?: 0 }
                                .thenBy { it.episode ?: 0 }
                        }
                    ) ?: return@mapNotNull null
                    WatchProgress(
                        contentId = latest.contentId,
                        contentType = latest.contentType,
                        name = latest.title,
                        poster = null,
                        backdrop = null,
                        logo = null,
                        videoId = latest.contentId,
                        season = latest.season,
                        episode = latest.episode,
                        episodeTitle = null,
                        position = 1L,
                        duration = 1L,
                        lastWatched = latest.watchedAt,
                        progressPercent = 100f
                    )
                }
        }.flowOn(Dispatchers.Default)
            .distinctUntilChanged()
    }

    private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lowered = trimmed.lowercase()
        return lowered == "tmdb" ||
            lowered == "imdb" ||
            lowered == "trakt" ||
            lowered == "tmdb:" ||
            lowered == "imdb:" ||
            lowered == "trakt:"
    }

    @OptIn(FlowPreview::class)
    override fun observeWatchedMovieIds(): Flow<Set<String>> {
        val baseFlow = combine(
            watchProgressPreferences.allProgress,
            watchedItemsPreferences.allItems
        ) { progressList, watchedItems ->
            val completedIds = mutableSetOf<String>()
            val replayingIds = mutableSetOf<String>()
            for (progress in progressList) {
                if (progress.isCompleted()) {
                    completedIds.add(progress.contentId)
                } else if (progress.position > 0L ||
                    progress.progressPercent?.let { it > 0f } == true
                ) {
                    replayingIds.add(progress.contentId)
                }
            }
            val watchedItemIds = watchedItems
                .filter { it.season == null && it.episode == null }
                .map { it.contentId }
                .toSet()
            (completedIds + watchedItemIds) - replayingIds
        }.debounce(500)
        return combine(
            baseFlow,
            profileManager.activeProfileId,
            optimisticWatchedMovieAdditions,
            optimisticWatchedMovieRemovals
        ) { base, profileId, additions, removals ->
            (base + additions[profileId].orEmpty()) - removals[profileId].orEmpty()
        }.distinctUntilChanged()
    }

    override fun applyOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {
        val profileId = profileManager.activeProfileId.value
        if (add) {
            optimisticWatchedMovieAdditions.update { it.plusIds(profileId, ids) }
            optimisticWatchedMovieRemovals.update { it.minusIds(profileId, ids) }
        } else {
            optimisticWatchedMovieRemovals.update { it.plusIds(profileId, ids) }
            optimisticWatchedMovieAdditions.update { it.minusIds(profileId, ids) }
        }
    }

    override fun revertOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {
        // The active profile may have changed since the toggle, so revert it wherever it is.
        val target = if (add) optimisticWatchedMovieAdditions else optimisticWatchedMovieRemovals
        target.update { byProfile -> byProfile.keys.fold(byProfile) { acc, profileId -> acc.minusIds(profileId, ids) } }
    }

    private fun Map<Int, Set<String>>.plusIds(profileId: Int, ids: Set<String>): Map<Int, Set<String>> =
        this + (profileId to (this[profileId].orEmpty() + ids))

    private fun Map<Int, Set<String>>.minusIds(profileId: Int, ids: Set<String>): Map<Int, Set<String>> {
        val remaining = this[profileId].orEmpty() - ids
        return if (remaining.isEmpty()) this - profileId else this + (profileId to remaining)
    }

    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        val profileId = profileManager.activeProfileId.value
        return watchedItemsPreferences.getAllItems(profileId)
            .filter { it.season != null && it.episode != null }
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.map { it.season!! to it.episode!! }.toSet() }
    }

    override fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean> {
        val progressFlow = if (season != null && episode != null) {
            watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
        } else {
            watchProgressPreferences.getProgress(contentId)
        }
        return combine(
            progressFlow,
            watchedItemsPreferences.isWatched(contentId, season, episode)
        ) { progressEntry, itemWatched ->
            val hasStartedReplay = progressEntry?.let { entry ->
                !entry.isCompleted() &&
                    (entry.position > 0L || entry.progressPercent?.let { it > 0f } == true)
            } == true

            if (hasStartedReplay) {
                false
            } else {
                (progressEntry?.isCompleted() == true) || itemWatched
            }
        }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        // Clear any CW dismiss keys for this series so it reappears in Continue Watching.
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            continueWatchingSettings.removeDismissedNextUpKeysForContent(progress.contentId)
        }
        val profileId = profileManager.activeProfileId.value
        val progressKey = progress.progressKey
        val shouldPushRemote = syncRemote &&
            authManager.isAuthenticated &&
            remoteProgressWriteDeduplicator.shouldSend(
                profileId = profileId,
                progressKey = progressKey,
                progress = progress,
                nowMs = SystemClock.elapsedRealtime()
            )
        watchProgressPreferences.saveProgress(progress, profileId = profileId)

        if (shouldPushRemote) {
            syncScope.launch {
                withContext(NonCancellable) {
                    watchProgressSyncService.pushSingleToRemote(progressKey, progress, profileId)
                        .onFailure { error ->
                            Log.w(TAG, "Failed single progress push; falling back to full sync next cycle", error)
                        }
                }
            }
        }

        if (progress.isCompleted()) {
            val watchedItem = progress.toWatchedItem()
            watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId)
            if (shouldPushRemote) {
                triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
            }
        }
    }

    override suspend fun mergeContinueWatchingMetadata(enriched: List<WatchProgress>, profileId: Int) {
        watchProgressPreferences.mergeContinueWatchingMetadata(enriched, profileId = profileId)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode, profileId = profileId)
        watchProgressPreferences.removeProgress(contentId, season, episode, profileId = profileId)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeProgress remote delete failed; relying on push sync", error)
                }
        }
        triggerRemoteSync(profileId = profileId)
        pruneWatchNext(listOf(WatchNextRemoval(contentId, season, episode)))
    }

    override suspend fun removeFromHistory(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode, profileId = profileId)
        watchProgressPreferences.removeProgress(contentId, season, episode, profileId = profileId)
        watchedItemsPreferences.unmarkAsWatched(contentId, season, episode, profileId = profileId)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory remote delete failed; relying on push sync", error)
                }
        }
        if (authManager.isAuthenticated) {
            watchedItemsSyncService.deleteFromRemote(contentId, season, episode, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory watched item remote delete failed", error)
                }
        }
        triggerRemoteSync(profileId = profileId)
        pruneWatchNext(listOf(WatchNextRemoval(contentId, season, episode)))
    }

    override suspend fun removeFromHistoryBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>
    ) {
        if (episodes.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        watchProgressPreferences.removeProgressBatch(contentId, episodes, profileId = profileId)
        watchedItemsPreferences.unmarkAsWatchedBatch(contentId, episodes, profileId = profileId)
        val remoteDeleteKeys = episodes.map { (season, episode) ->
            watchProgressKey(contentId, season, episode)
        } + contentId
        if (authManager.isAuthenticated) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys.distinct(), profileId = profileId)
                .onFailure { error -> Log.w(TAG, "removeFromHistoryBatch remote delete failed", error) }
            watchedItemsSyncService.deleteFromRemoteBatch(contentId, episodes, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistoryBatch watched item remote delete failed", error)
                }
        }
        triggerRemoteSync(profileId = profileId)
        pruneWatchNext(episodes.map { (season, episode) -> WatchNextRemoval(contentId, season, episode) })
    }

    override suspend fun markAsCompleted(progress: WatchProgress) {
        val profileId = profileManager.activeProfileId.value
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            continueWatchingSettings.removeDismissedNextUpKeysForContent(progress.contentId)
        }
        val now = System.currentTimeMillis()
        watchProgressPreferences.markAsCompleted(progress, profileId = profileId)
        val watchedItem = progress.toWatchedItem(watchedAt = now)
        watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId)
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
        pruneWatchNext(listOf(WatchNextRemoval(progress.contentId, progress.season, progress.episode)))
    }

    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val firstProgress = progressList.first()
        if (firstProgress.contentType.equals("series", ignoreCase = true) ||
            firstProgress.contentType.equals("tv", ignoreCase = true)) {
            continueWatchingSettings.removeDismissedNextUpKeysForContent(firstProgress.contentId)
        }
        val now = System.currentTimeMillis()
        watchProgressPreferences.markAsCompletedBatch(progressList, profileId = profileId)
        val watchedItems = progressList.map { progress -> progress.toWatchedItem(watchedAt = now) }
        watchedItemsPreferences.markAsWatchedBatch(watchedItems, profileId = profileId)
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(watchedItems, profileId = profileId)
        pruneWatchNext(progressList.map { WatchNextRemoval(it.contentId, it.season, it.episode) })
    }

    private fun WatchProgress.toWatchedItem(watchedAt: Long = System.currentTimeMillis()): WatchedItem =
        WatchedItem(
            contentId = contentId,
            contentType = contentType,
            title = name,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

    private fun WatchedItem.syncKey(profileId: Int): WatchedItemSyncKey =
        WatchedItemSyncKey(profileId, contentId, season, episode)

    private data class WatchedItemSyncKey(
        val profileId: Int,
        val contentId: String,
        val season: Int?,
        val episode: Int?
    )

    /** [items] is a claimed batch from [planProgressArtworkHydrationBatch] for [profileId]. */
    private suspend fun hydrateProgressArtwork(items: List<WatchProgress>, profileId: Int) {
        items.forEach { progress ->
            runCatching {
                val metadata = fetchContentMetadata(
                    contentId = progress.contentId,
                    contentType = progress.contentType
                ) ?: return@runCatching
                val episodeRuntimeMs = if (progress.season != null && progress.episode != null)
                    metadata.episodes[progress.season to progress.episode]?.runtimeMs ?: 0L
                else 0L
                val durationMs = progress.duration.takeIf { it > 0 }
                    ?: episodeRuntimeMs.takeIf { it > 0 }
                    ?: metadata.runtimeMs

                // If addon returned no backdrop or poster, fall back to TMDB
                var backdropToSave = progress.backdrop ?: metadata.backdrop
                var posterToSave = progress.poster ?: metadata.poster
                if (backdropToSave == null && posterToSave == null) {
                    val tmdbImages = tmdbService.fetchImdbImages(progress.contentId, progress.contentType)
                    backdropToSave = tmdbImages?.backdropUrl
                    posterToSave = tmdbImages?.posterUrl
                }

                val hasNewData = posterToSave != null || backdropToSave != null
                    || metadata.logo != null || durationMs > 0
                if (hasNewData) {
                    // Merge into the entry as it is NOW, not the launch-time snapshot:
                    // playback may have saved a newer position during the fetch.
                    watchProgressPreferences.mergeHydratedArtwork(
                        snapshot = progress,
                        poster = posterToSave,
                        backdrop = backdropToSave,
                        logo = metadata.logo,
                        name = metadata.name,
                        durationMs = durationMs,
                        profileId = profileId
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Progress artwork hydration failed for ${progress.contentId}", error)
            }
        }
    }

    private suspend fun resolveRemoteDeleteKeys(
        contentId: String,
        season: Int?,
        episode: Int?,
        profileId: Int = profileManager.activeProfileId.value
    ): List<String> {
        val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)
        val keys = if (season != null && episode != null) {
            listOf(watchProgressKey(contentId, season, episode), contentId)
        } else {
            val matchingLocalKeys = rawEntries
                .keys
                .filter { key ->
                    key == contentId || key.startsWith("${contentId}_")
                }
            matchingLocalKeys + contentId
        }
        val resolvedKeys = keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return resolvedKeys
    }

}
