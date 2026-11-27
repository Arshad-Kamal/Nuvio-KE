package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A tracker as the Continue Watching source: its progress, Up next seeds and watched state, all for the active
 * profile. Read through [TrackingProgressRouter] by `WatchProgressRepositoryImpl` (Spec 104 S2b). Trimmed from upstream
 * NuvioTV's contract to the members KE consumes; a tracker that needs another hook adds it in its own spec.
 * Every flow follows the active profile (a profile switch re-emits that profile's state, `isAuthenticated` included),
 * and its first emission is loaded data (cached or fetched), never a placeholder: callers may take `first()`.
 * The optimistic mutators are called from several coroutines at once (player saves, UI marks): implementations must be
 * thread-safe (e.g. `MutableStateFlow.update`). Suspending calls must be cancellable (Retrofit `suspend`, not a
 * blocking `execute()`), so the router's time bound can end them.
 */
interface TrackingProgressProvider {
    val providerId: TrackingProviderId
    val isAuthenticated: Flow<Boolean>

    /** Every progress entry the tracker holds (in progress and finished), newest first, one per title. */
    val allProgress: Flow<List<WatchProgress>>

    /** Finished episodes to build Up next from, one per show (the tracker's own pick of which episode). */
    val nextUpSeeds: Flow<List<WatchProgress>>

    /** Ids of watched films, in every id form the tracker knows ("tt…", "tmdb:…"). */
    val watchedMovieIds: Flow<Set<String>>

    /** Watched marks: films without season/episode, episodes with both. */
    val watchedItems: Flow<List<WatchedItem>>

    fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>

    fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean>

    suspend fun refresh(intent: TrackingRefreshIntent)

    /** Drops the title's (or episode's) resume point on [profileId]'s account (Continue Watching › Remove). */
    suspend fun removeProgress(profileId: Int, contentId: String, season: Int?, episode: Int?)

    /** Shows a local save at once, before the next refresh; a finished entry also counts as watched. */
    fun applyOptimisticProgress(progress: WatchProgress)

    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)

    /**
     * True for a title the tracker cannot hold (an id it does not know): its local progress and marks stay visible
     * while the tracker is the source.
     */
    fun retainsLocal(contentId: String): Boolean = false

    /**
     * [retainsLocal] for one local entry: true when the tracker could not have received it (Spec 105 S6: an episode it
     * has no counterpart for, or played before it had one), so it stays visible beside the tracker's state. [season] and
     * [episode] null = the title itself; [lastWatched] is the entry's own time.
     */
    fun retainsLocalEpisode(contentId: String, season: Int?, episode: Int?, lastWatched: Long): Boolean = retainsLocal(contentId)

    /**
     * Emits whenever the answers of [retainsLocalEpisode] may have changed without the tracker's own flows changing
     * (Spec 105 S6: a TVDB map arrived); merges re-run on it. Default: one value, never again.
     */
    val localRetentionChanges: Flow<Unit> get() = flowOf(Unit)
}

@Singleton
class TrackingProgressProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingProgressProvider>
) {
    private val providersById: Map<TrackingProviderId, TrackingProgressProvider> =
        providers.associateBy(TrackingProgressProvider::providerId)

    init {
        require(providersById.size == providers.size) { "Two progress providers share an id" }
    }

    fun providers(): List<TrackingProgressProvider> = providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingProgressProvider? = providersById[id]
}

@Singleton
class TrackingHistoryWriterRegistry @Inject constructor(
    writers: Set<@JvmSuppressWildcards TrackingHistoryWriter>
) {
    private val writersById: Map<TrackingProviderId, TrackingHistoryWriter> =
        writers.associateBy(TrackingHistoryWriter::providerId)

    init {
        require(writersById.size == writers.size) { "Two history writers share an id" }
    }

    fun writers(): List<TrackingHistoryWriter> = writersById.values.sortedBy { it.providerId.ordinal }

    fun writer(id: TrackingProviderId): TrackingHistoryWriter? = writersById[id]
}
