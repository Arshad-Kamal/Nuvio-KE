package com.nuvio.tv.core.tracking

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

private const val TAG = "TrackingProgress"

/** Upper bound of one queued tracker write; a provider's own HTTP timeouts are expected to end it sooner. */
internal const val TRACKING_WRITE_TIMEOUT_MS = 30_000L

/**
 * The Continue Watching side of the trackers for `WatchProgressRepositoryImpl` (Spec 104 S2b): which tracker reads
 * come from, and the extra writes trackers get. Local writes stay in the repository and never change (S2-R1).
 *
 * Tracker writes run in the background, one after another in call order (an unmark never overtakes its mark), so a
 * slow tracker never holds up the UI. Each call is bounded ([TRACKING_WRITE_TIMEOUT_MS]), so a hung tracker delays the
 * queue by at most that. An Error (not an Exception) ends the queue and reaches the crash handler, as anywhere else.
 * A failed write is logged (tracker and error class only) and not retried: the next refresh shows the tracker's state.
 */
@Singleton
class TrackingProgressRouter internal constructor(
    private val activeProviderFlow: Flow<TrackingProgressProvider?>,
    /** False in a build with no tracker: every read is the local one, with no extra flow in between. */
    private val hasProgressProviders: Boolean,
    private val historyWriters: TrackingHistoryWriterRegistry,
    private val registry: TrackingProviderRegistry,
    scope: CoroutineScope
) {
    @Inject
    constructor(
        sourceController: TrackingSourceController,
        progressProviders: TrackingProgressProviderRegistry,
        historyWriters: TrackingHistoryWriterRegistry,
        registry: TrackingProviderRegistry
    ) : this(
        sourceController.activeProgressProvider,
        progressProviders.providers().isNotEmpty(),
        historyWriters,
        registry,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) write()
        }
    }

    /** [local] while Nuvio is the source, else [tracker] of the source's tracker; follows source switches. */
    fun <T> route(local: Flow<T>, tracker: (TrackingProgressProvider) -> Flow<T>): Flow<T> =
        if (!hasProgressProviders) local else activeProviderFlow.routeTo(local, tracker)

    /**
     * The [route] every projected read uses: while a tracker is the source, its [tracker] flow merged with [local]
     * by [merge] (run with the provider as receiver, for its retainsLocal checks; re-run when they may have changed).
     */
    fun <A, T> project(
        local: Flow<T>,
        tracker: TrackingProgressProvider.() -> Flow<A>,
        merge: TrackingProgressProvider.(tracker: A, local: T) -> T
    ): Flow<T> = route(local) { provider ->
        combine(provider.tracker(), local, provider.localRetentionChanges) { fromTracker, own, _ -> provider.merge(fromTracker, own) }
    }

    /** The tracker Continue Watching reads from now, or null for Nuvio. */
    suspend fun activeProvider(): TrackingProgressProvider? = if (!hasProgressProviders) null else activeProviderFlow.first()

    /** Marks reach every tracker connected now (not only the source), as plays do (mockup C1). */
    fun addToHistory(profileId: Int, items: List<TrackingHistoryItem>) {
        val sent = items.filterNot { it.media.isWholeSeries }
        val writers = connectedWriters()
        if (sent.isEmpty() || writers.isEmpty()) return
        enqueue("history add", writers) { writer -> writer.addToHistory(profileId, sent) }
    }

    /** A whole-series removal is never sent: it would wipe every watched episode of the show on the tracker. */
    fun removeFromHistory(profileId: Int, items: List<TrackingMediaReference>) {
        val sent = items.filterNot(TrackingMediaReference::isWholeSeries)
        val writers = connectedWriters()
        if (sent.isEmpty() || writers.isEmpty()) return
        enqueue("history removal", writers) { writer -> writer.removeFromHistory(profileId, sent) }
    }

    fun removeProgress(profileId: Int, provider: TrackingProgressProvider, contentId: String, season: Int?, episode: Int?) {
        writes.trySend { boundedCall(provider.providerId, "progress removal") { provider.removeProgress(profileId, contentId, season, episode) } }
    }

    /** For tests: returns once every write queued before the call has run. */
    internal suspend fun awaitQueuedWrites() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    /**
     * One tracker call: bounded by [TRACKING_WRITE_TIMEOUT_MS] so a hung tracker cannot stall the queue, a failure
     * logged by tracker and error class only (no title or token).
     */
    private suspend fun boundedCall(providerId: TrackingProviderId, what: String, call: suspend () -> Unit) {
        trackerCallFailure { withTimeout(TRACKING_WRITE_TIMEOUT_MS) { call() } }
            ?.let { Log.w(TAG, "${providerId.storageId} $what failed: ${it.javaClass.simpleName}") }
    }

    private fun connectedWriters(): List<TrackingHistoryWriter> {
        val connected = registry.connectedProviderIds()
        return historyWriters.writers().filter { it.providerId in connected }
    }

    /** One queued write; the trackers inside it run at once, each failing on its own. */
    private fun enqueue(what: String, writers: List<TrackingHistoryWriter>, call: suspend (TrackingHistoryWriter) -> Unit) {
        writes.trySend {
            supervisorScope {
                writers.map { writer -> async { boundedCall(writer.providerId, what) { call(writer) } } }.awaitAll()
            }
        }
    }
}
