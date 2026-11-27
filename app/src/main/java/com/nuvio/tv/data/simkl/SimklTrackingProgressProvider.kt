package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.progressKey
import com.nuvio.tv.domain.model.watchProgressKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update

/**
 * Simkl as the Continue Watching source (Spec 106 S2a): the active profile's [SimklSnapshot] projected by
 * [projectSimkl] under the profile's [SimklAnimeSeasons] (S5: a change re-projects with no call), plus the plays and marks
 * made on this TV that the snapshot does not hold yet.
 *
 * Such an optimistic entry stays until a sync that started at least [OPTIMISTIC_SETTLE_MS] after it has finished (its
 * write had time to reach Simkl, so the snapshot now holds the truth), or the account changes. It lives in memory: a
 * restart drops it, and the next sync shows Simkl's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklTrackingProgressProvider(
    private val store: SimklSyncStore,
    private val sync: SimklSync,
    accounts: TrackingAccountStore,
    appId: Flow<String?>,
    private val activeProfileId: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val projectionDispatcher: CoroutineDispatcher,
    private val animeSeasons: (profileId: Int) -> Flow<SimklAnimeSeasons> = { flowOf(SimklAnimeSeasons.DEFAULT) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.SIMKL

    /** [SimklTrackingProvider.isAuthenticated]'s rule ([simklConnected]), as a flow for the refresh coordinator. */
    override val isAuthenticated: Flow<Boolean> = combine(accounts.account(TrackingProviderId.SIMKL), appId, ::simklConnected)
        .distinctUntilChanged()

    /** progressKey -> the local entry, or null for a removal; per profile. */
    private val overlays = MutableStateFlow<Map<Int, Map<String, Optimistic>>>(emptyMap())

    /**
     * One shared projection per profile, so a value replayed after a profile switch is always that profile's; its
     * cache empties once nobody reads it, so a later reader never starts from a stale value.
     */
    private val perProfile = ConcurrentHashMap<Int, Flow<SimklProjected>>()

    private fun projectedFor(profileId: Int): Flow<SimklProjected> = perProfile.computeIfAbsent(profileId) {
        combine(
            combine(store.snapshot(profileId), animeSeasons(profileId).distinctUntilChanged(), ::projectSimkl)
                .flowOn(projectionDispatcher),
            overlays.map { it[profileId].orEmpty() }.distinctUntilChanged(),
            ::applyOptimistic
        ).shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS, replayExpirationMillis = 0L), replay = 1)
    }

    private val projected: Flow<SimklProjected> = activeProfileId.flatMapLatest(::projectedFor)

    override val allProgress: Flow<List<WatchProgress>> = projected.map { it.progress }.distinctUntilChanged()
    override val nextUpSeeds: Flow<List<WatchProgress>> = projected.map { it.nextUpSeeds }.distinctUntilChanged()
    override val watchedMovieIds: Flow<Set<String>> = projected.map { it.watchedMovieIds }.distinctUntilChanged()
    override val watchedItems: Flow<List<WatchedItem>> = projected.map { it.watchedItems }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projected.map { it.episodeProgress[contentId].orEmpty() }.distinctUntilChanged()

    override fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean> =
        projected.map { projected ->
            watchProgressKey(contentId, season, episode) in projected.watchedKeys ||
                (season == null && episode == null && contentId in projected.watchedMovieIds)
        }.distinctUntilChanged()

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val profileId = activeProfileId.value
        val startedAt = nowEpochMs()
        if (!sync.refresh(profileId, intent)) return
        overlays.update { all ->
            val kept = all[profileId].orEmpty().filterValues { it.atEpochMs > startedAt - OPTIMISTIC_SETTLE_MS }
            if (kept.isEmpty()) all - profileId else all + (profileId to kept)
        }
    }

    override suspend fun removeProgress(profileId: Int, contentId: String, season: Int?, episode: Int?) {
        sync.removePlayback(profileId, contentId, season, episode, animeSeasons(profileId).first())
    }

    override fun applyOptimisticProgress(progress: WatchProgress) = putOptimistic(progress.progressKey, progress)

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) =
        putOptimistic(watchProgressKey(contentId, season, episode), null)

    override fun retainsLocal(contentId: String): Boolean = isSimklUnmatchable(contentId)

    /** The account on [profileId] changed (sign-in or sign-out): its plays on this TV were the old account's. */
    fun forget(profileId: Int) {
        overlays.update { it - profileId }
    }

    private fun putOptimistic(key: String, progress: WatchProgress?) {
        val profileId = activeProfileId.value
        val entry = Optimistic(progress, nowEpochMs())
        overlays.update { all -> all + (profileId to (all[profileId].orEmpty() + (key to entry))) }
    }

    private companion object {
        const val SHARE_TIMEOUT_MS = 5_000L

        /** How long a play's or mark's write gets to reach Simkl before a sync's answer replaces it. */
        const val OPTIMISTIC_SETTLE_MS = 60_000L
    }
}

/** [progress] null = the key was removed on this TV. */
internal data class Optimistic(val progress: WatchProgress?, val atEpochMs: Long)

/**
 * The snapshot's view with this TV's newer plays and marks on top: a removal hides the key; a finished entry is a
 * watched mark (and leaves Continue Watching); an unfinished one replaces the title's resume point when newer.
 */
internal fun applyOptimistic(base: SimklProjected, overlay: Map<String, Optimistic>): SimklProjected {
    if (overlay.isEmpty()) return base
    var progress = base.progress
    var watchedItems = base.watchedItems
    val watchedMovieIds = base.watchedMovieIds.toMutableSet()
    val episodeProgress = base.episodeProgress.mapValues { it.value.toMutableMap() }.toMutableMap()

    overlay.entries.sortedBy { it.value.atEpochMs }.forEach { (key, optimistic) ->
        val entry = optimistic.progress
        // A removal of a whole title (Continue Watching > Remove) drops each of its resume points.
        progress = progress.filterNot { it.progressKey == key || (entry == null && it.contentId == key) }
        watchedItems = watchedItems.filterNot { watchProgressKey(it.contentId, it.season, it.episode) == key }
        if (entry == null) {
            watchedMovieIds -= key
            episodeProgress.values.forEach { episodes -> episodes.entries.removeAll { (_, p) -> p.progressKey == key } }
            return@forEach
        }
        val episodeKey = entry.season?.let { season -> entry.episode?.let { season to it } }
        episodeKey?.let { episodeProgress.getOrPut(entry.contentId) { mutableMapOf() }[it] = entry }
        if (entry.isCompleted()) {
            watchedItems = watchedItems + WatchedItem(
                contentId = entry.contentId,
                contentType = entry.contentType,
                title = entry.name,
                season = entry.season,
                episode = entry.episode,
                watchedAt = entry.lastWatched,
                trackingProviderId = TrackingProviderId.SIMKL.storageId
            )
            if (episodeKey == null) watchedMovieIds += entry.contentId
        } else {
            val older = progress.firstOrNull { it.contentId == entry.contentId }
            if (older == null || older.lastWatched <= entry.lastWatched) {
                progress = progress.filterNot { it.contentId == entry.contentId } + entry
            }
        }
    }
    return base.copy(
        progress = progress.sortedByDescending(WatchProgress::lastWatched),
        watchedItems = watchedItems,
        watchedMovieIds = watchedMovieIds,
        episodeProgress = episodeProgress
    )
}
