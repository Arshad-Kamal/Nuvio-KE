package com.nuvio.tv.data.mdblist

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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update

/**
 * MDBList as the Continue Watching source (Spec 105 S2a): the active profile's [MdbListSnapshot] projected by
 * [projectMdbList], plus the plays and marks made on this TV that the snapshot does not hold yet. Same contract and
 * overlay rules as Spec 106's Simkl provider; joins the registry in S1b (after Spec 106, D9).
 *
 * Episodes reach readers in the addon's numbering (S6, N4): the snapshot's rows are moved through the stored TVDB maps
 * ([MdbListSnapshot.inAddonNumbering]) before anything is picked from them, and recomputed whenever the snapshot or the
 * maps change; a `tt` show without a map is withheld. The overlay (this TV's plays, already in the addon's numbering)
 * goes on top. After each refresh that brought MDBList's state, [MdbListEpisodeNumbering.launchFill] fetches missing
 * or stale maps.
 *
 * Such an optimistic entry stays until a sync that started at least [OPTIMISTIC_SETTLE_MS] after it has finished (its
 * write had time to reach MDBList, so the snapshot now holds the truth), or the key changes. It lives in memory: a
 * restart drops it, and the next sync shows MDBList's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MdbListTrackingProgressProvider(
    private val store: MdbListSyncStore,
    private val sync: MdbListSync,
    accounts: TrackingAccountStore,
    private val activeProfileId: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val projectionDispatcher: CoroutineDispatcher,
    private val numbering: MdbListEpisodeNumbering,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.MDBLIST

    override val isAuthenticated: Flow<Boolean> = accounts.account(TrackingProviderId.MDBLIST)
        .map { account -> account != null && !account.revoked }
        .distinctUntilChanged()

    /** progressKey -> the local entry, or null for a removal; per profile. */
    private val overlays = MutableStateFlow<Map<Int, Map<String, MdbListOptimistic>>>(emptyMap())

    /**
     * One shared projection per profile, so a value replayed after a profile switch is always that profile's; its
     * cache empties once nobody reads it, so a later reader never starts from a stale value.
     */
    private val perProfile = ConcurrentHashMap<Int, Flow<MdbListProjected>>()

    private fun projectedFor(profileId: Int): Flow<MdbListProjected> = perProfile.computeIfAbsent(profileId) {
        combine(
            combine(store.snapshot(profileId), numbering.maps) { snapshot, maps -> projectMdbList(snapshot?.inAddonNumbering(maps)) }
                .flowOn(projectionDispatcher),
            overlays.map { it[profileId].orEmpty() }.distinctUntilChanged(),
            ::applyMdbListOptimistic
        ).shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS, replayExpirationMillis = 0L), replay = 1)
    }

    private val projected: Flow<MdbListProjected> = activeProfileId.flatMapLatest(::projectedFor)

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
        numbering.launchFill(profileId) { store.get(profileId) }
        overlays.update { all ->
            val kept = all[profileId].orEmpty().filterValues { it.atEpochMs > startedAt - OPTIMISTIC_SETTLE_MS }
            if (kept.isEmpty()) all - profileId else all + (profileId to kept)
        }
    }

    /**
     * Continue Watching removes a whole title (no numbers needed). An episode always comes in the addon's numbers (the
     * detail screen's unmarks, D14): it is moved into MDBList's numbering as any write is, and exactly that MDBList
     * episode is cleared; nothing is sent when MDBList has no counterpart.
     */
    override suspend fun removeProgress(profileId: Int, contentId: String, season: Int?, episode: Int?) {
        if (season == null || episode == null) return sync.removePlayback(profileId, contentId, season, episode)
        val (trackerSeason, trackerEpisode) = numbering.removalEpisode(profileId, contentId, season, episode) ?: return
        sync.removePlayback(profileId, contentId, trackerSeason, trackerEpisode)
    }

    override fun applyOptimisticProgress(progress: WatchProgress) = putOptimistic(progress.progressKey, progress)

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) =
        putOptimistic(watchProgressKey(contentId, season, episode), null)

    override fun retainsLocal(contentId: String): Boolean = isMdbListUnmatchable(contentId)

    override fun retainsLocalEpisode(contentId: String, season: Int?, episode: Int?, lastWatched: Long): Boolean =
        numbering.retainsLocalEpisode(contentId, season, episode, lastWatched)

    override val localRetentionChanges: Flow<Unit> = numbering.maps.map { it.retentionView() }.distinctUntilChanged().map { }

    /** The key on [profileId] changed (connect or disconnect): its plays on this TV were the old account's. */
    fun forget(profileId: Int) {
        overlays.update { it - profileId }
    }

    private fun putOptimistic(key: String, progress: WatchProgress?) {
        val profileId = activeProfileId.value
        val entry = MdbListOptimistic(progress, nowEpochMs())
        overlays.update { all -> all + (profileId to (all[profileId].orEmpty() + (key to entry))) }
    }

    private companion object {
        const val SHARE_TIMEOUT_MS = 5_000L

        /** How long a play's or mark's write gets to reach MDBList before a sync's answer replaces it. */
        const val OPTIMISTIC_SETTLE_MS = 60_000L
    }
}

/** [progress] null = the key was removed on this TV. */
internal data class MdbListOptimistic(val progress: WatchProgress?, val atEpochMs: Long)

/**
 * The snapshot's view with this TV's newer plays and marks on top: a removal hides the key; an entry at MDBList's
 * watched mark ([MDBLIST_WATCHED_PERCENT]) is a watched mark (and leaves Continue Watching); a lower one replaces the
 * title's resume point when newer.
 */
internal fun applyMdbListOptimistic(base: MdbListProjected, overlay: Map<String, MdbListOptimistic>): MdbListProjected {
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
            // A whole-title removal also drops its episodes' resume points; their watched marks stay.
            episodeProgress[key]?.entries?.removeAll { (_, p) -> p.mdbListPlaybackId != null || p.progressPercent != 100f }
            return@forEach
        }
        val episodeKey = entry.season?.let { season -> entry.episode?.let { season to it } }
        episodeKey?.let { episodeProgress.getOrPut(entry.contentId) { mutableMapOf() }[it] = entry }
        if (entry.isCompleted((MDBLIST_WATCHED_PERCENT / 100.0).toFloat())) {
            watchedItems = watchedItems + WatchedItem(
                contentId = entry.contentId,
                contentType = entry.contentType,
                title = entry.name,
                season = entry.season,
                episode = entry.episode,
                watchedAt = entry.lastWatched,
                trackingProviderId = TrackingProviderId.MDBLIST.storageId
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
