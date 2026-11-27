package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Brings a profile's [SimklSnapshot] up to date the way Simkl requires (Spec 106 S2 plan; api.simkl.org guides/sync):
 * `/sync/activities` first, and only the endpoints whose buckets moved since the saved reply. Polling the library
 * without that gate can get the client id suspended. The activities reply is read before the fetches it anchors and
 * saved only when every fetch succeeded, so a failed run resends the same `date_from` next time.
 *
 * Automatic runs (app start, resume, profile switch) skip within [AUTOMATIC_FLOOR_MS] of the last successful one
 * (Simkl's 15-30 min advice for TV apps); a run the user asked for, or one after a write, always goes. Nothing is sent
 * while the profile's daily allowance is spent. Marks kept while it was spent ([replayPending], S2b) go first, every
 * run, so the sync that follows reads them back.
 */
class SimklSync(
    private val session: SimklSession,
    private val store: SimklSyncStore,
    private val accounts: TrackingAccountStore,
    private val replayPending: suspend (profileId: Int) -> Unit = {},
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val locksGuard = Mutex()
    private val locks = mutableMapOf<Int, Mutex>()

    /** Per profile: when the last run that settled ([refresh] true) started. */
    private val settledStarts = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** Profiles with a pull running now. */
    private val pulling = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * When [profileId]'s last settled run started (null: none since the app started). A change made on this TV before
     * that, with time to reach Simkl, is in the snapshot now (the S3 library's optimistic changes leave by it).
     */
    fun settledRunStart(profileId: Int): Flow<Long?> = settledStarts.map { it[profileId] }.distinctUntilChanged()

    fun isPulling(profileId: Int): Flow<Boolean> = pulling.map { profileId in it }.distinctUntilChanged()

    /**
     * True when the snapshot now holds Simkl's state as of this call (synced, or nothing had moved) and every kept mark
     * went; false when it was skipped (signed out, allowance spent, the automatic floor), the account changed meanwhile,
     * or kept marks are still unsent. Simkl failures of the pull throw.
     */
    suspend fun refresh(profileId: Int, intent: TrackingRefreshIntent): Boolean {
        val startedAt = nowEpochMs()
        val settled = run(profileId, intent)
        // Never backwards: runs overlap (progress and library refreshes, a transfer read) and may end out of order.
        if (settled) settledStarts.update { it + (profileId to maxOf(it[profileId] ?: startedAt, startedAt)) }
        return settled
    }

    private suspend fun run(profileId: Int, intent: TrackingRefreshIntent): Boolean {
        if (!signedIn(profileId) || session.quota.isPaused(profileId)) return false
        // Outside the sync lock (its 21 s write-lock waits must not hold a removal's snapshot edit). A batch that fails
        // for now must not hold the sync back either: it stays kept and goes next run. The run then reports not
        // settled, so the screen keeps the marks Simkl has not got yet.
        val replayed = try {
            replayPending(profileId)
            true
        } catch (failure: IOException) {
            Log.w(TAG, "Simkl pending marks not sent: ${failure.javaClass.simpleName}")
            false
        }
        return pull(profileId, intent) && replayed
    }

    /**
     * A pull only (no kept-mark replay), for a decision taken under [SimklHistoryWriter]'s lock: the replay takes that
     * lock, the pull only the sync lock. True when the snapshot now holds Simkl's state.
     */
    internal suspend fun pullNow(profileId: Int): Boolean =
        signedIn(profileId) && !session.quota.isPaused(profileId) && pull(profileId, TrackingRefreshIntent.USER_INITIATED)

    private suspend fun signedIn(profileId: Int): Boolean =
        accounts.get(profileId, TrackingProviderId.SIMKL)?.let { !it.revoked } == true

    private suspend fun pull(profileId: Int, intent: TrackingRefreshIntent): Boolean = lockFor(profileId).withLock {
        pulling.update { it + profileId }
        try {
            pullLocked(profileId, intent)
        } finally {
            pulling.update { it - profileId }
        }
    }

    private suspend fun pullLocked(profileId: Int, intent: TrackingRefreshIntent): Boolean {
        val account = accounts.get(profileId, TrackingProviderId.SIMKL)?.takeIf { !it.revoked } ?: return false
        val now = nowEpochMs()
        // Read again: the replay may have spent the day.
        if (session.quota.isPaused(profileId)) return false
        val generation = store.generation(profileId)
        val current = store.get(profileId)
        val sinceLast = account.lastSyncedAtEpochMs?.let { now - it }
        if (intent == TrackingRefreshIntent.AUTOMATIC && current?.activities != null && sinceLast != null &&
            sinceLast in 0 until AUTOMATIC_FLOOR_MS
        ) {
            return false
        }

        val activities = decode(SimklActivities.serializer(), call(profileId, "/sync/activities"))
        val saved = current?.activities
        val next = when {
            current == null -> fullPull(profileId)
            activities.all == saved?.all -> null
            // An account so new that it had no stamp yet: a delta needs a date_from, so pull it all.
            saved?.all == null -> fullPull(profileId)
            else -> delta(profileId, current, saved, activities)
        }
        val written = next == null || store.saveIf(profileId, generation, next.copy(activities = activities))
        if (!written) return false
        // Only for the grant this run started on (Simkl's refresh token stays the same for a grant's life).
        accounts.update(profileId, TrackingProviderId.SIMKL) { stored ->
            stored?.takeIf { it.refreshToken == account.refreshToken && !it.revoked }?.copy(lastSyncedAtEpochMs = nowEpochMs()) ?: stored
        }
        return true
    }

    /**
     * Deletes the saved playbacks of [contentId] (only the episode's when [season] and [episode] are given) at Simkl,
     * then from the snapshot. The targets come from Simkl's current list, not the snapshot: a pause made since the last
     * sync must go too. The calls run outside the sync lock (a long full pull must not hold a Continue Watching removal
     * past the router's time bound); only the snapshot edit takes it. A sync that read the list before the delete may
     * show the playback again until the next sync, which refetches it (the playback stamp moved). A playback already
     * gone (404) counts as deleted; any other failure is thrown after the others were tried. [anime] is the profile's
     * mode, so [contentId] and the episode are read as Continue Watching named them.
     */
    suspend fun removePlayback(
        profileId: Int,
        contentId: String,
        season: Int?,
        episode: Int?,
        anime: SimklAnimeSeasons = SimklAnimeSeasons.DEFAULT
    ) {
        val generation = store.generation(profileId)
        val targets = fetchPlayback(profileId).filter { session ->
            val entry = session.toWatchProgress(anime) ?: return@filter false
            entry.contentId == contentId && (season == null || episode == null || (entry.season == season && entry.episode == episode))
        }.mapNotNull(SimklPlayback::id)
        var firstFailure: SimklException? = null
        val deleted = targets.filterTo(HashSet()) { id ->
            try {
                session.call(profileId, SimklRequest(SimklHttpMethod.DELETE, "/sync/playback/$id"))
                true
            } catch (failure: SimklException) {
                val gone = failure is SimklApiException && failure.status == 404
                if (!gone && firstFailure == null) firstFailure = failure
                gone
            }
        }
        if (deleted.isNotEmpty()) {
            lockFor(profileId).withLock {
                val snapshot = store.get(profileId) ?: return@withLock
                store.saveIf(profileId, generation, snapshot.copy(playback = snapshot.playback.filterNot { it.id in deleted }))
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun fullPull(profileId: Int): SimklSnapshot {
        val entries = SimklMediaType.entries.flatMap { type -> fetchItems(profileId, FULL_QUERY, type) }
        return SimklSnapshot(entries = entries, playback = fetchPlayback(profileId))
    }

    private suspend fun delta(
        profileId: Int,
        current: SimklSnapshot,
        saved: SimklActivities,
        activities: SimklActivities
    ): SimklSnapshot {
        val dateFrom = checkNotNull(saved.all)
        val pairs = saved.domains.zip(activities.domains)
        var entries = current.entries
        if (pairs.any { (old, new) -> old.listStamps() != new.listStamps() }) {
            entries = mergeSimklEntries(entries, fetchItems(profileId, FULL_QUERY + ("date_from" to dateFrom)))
        }
        if (pairs.any { (old, new) -> old.removedFromList != new.removedFromList }) {
            val listed = fetchItems(profileId, mapOf("extended" to "simkl_ids_only")).mapNotNullTo(HashSet()) { it.media?.simklId }
            entries = entries.filter { entry -> entry.media?.simklId?.let(listed::contains) ?: true }
        }
        if (pairs.any { (old, new) -> old.ratedAt != new.ratedAt }) {
            entries = applySimklRatings(entries, fetchItems(profileId, mapOf("date_from" to dateFrom), base = "/sync/ratings"))
        }
        val playback = if (pairs.any { (old, new) -> old.playback != new.playback }) fetchPlayback(profileId) else current.playback
        return current.copy(entries = entries, playback = playback)
    }

    /**
     * `/sync/all-items` (all types, or one [type] / [status]). Simkl refuses an answer that is too big (`400
     * max_items`) the same way on every retry, so the request is split: by type, then by status.
     */
    private suspend fun fetchItems(
        profileId: Int,
        query: Map<String, String>,
        type: SimklMediaType? = null,
        status: SimklListStatus? = null,
        base: String = "/sync/all-items"
    ): List<SimklEntry> {
        val path = base + (type?.let { "/${it.apiValue}" } ?: "") + (status?.let { "/${it.apiValue}" } ?: "")
        return try {
            decodeAllItems(call(profileId, path, query))
        } catch (failure: SimklApiException) {
            if (failure.status != 400 || failure.error != SIMKL_ERROR_MAX_ITEMS) throw failure
            when {
                type == null -> SimklMediaType.entries.flatMap { fetchItems(profileId, query, it, base = base) }
                status == null && base == "/sync/all-items" -> statusesOf(type).flatMap { fetchItems(profileId, query, type, it) }
                else -> throw failure
            }
        }
    }

    private suspend fun fetchPlayback(profileId: Int): List<SimklPlayback> {
        val body = call(profileId, "/sync/playback")
        return if (body.isEmptyReply()) emptyList() else decode(ListSerializer(SimklPlayback.serializer()), body)
    }

    private suspend fun call(profileId: Int, path: String, query: Map<String, String> = emptyMap()): String =
        session.call(profileId, SimklRequest(SimklHttpMethod.GET, path, query)).body

    private suspend fun lockFor(profileId: Int): Mutex = locksGuard.withLock { locks.getOrPut(profileId) { Mutex() } }

    companion object {
        const val AUTOMATIC_FLOOR_MS = 15L * 60_000L

        /** Episode rows with their watch times, completed and dropped titles included, anime with its TVDB mapping. */
        private val FULL_QUERY = mapOf(
            "extended" to "full_anime_seasons",
            "episode_watched_at" to "yes",
            "include_all_episodes" to "yes"
        )
    }
}

internal const val SIMKL_ERROR_MAX_ITEMS = "max_items"

private const val TAG = "SimklSync"

/** Films have no watching or hold list at Simkl. */
private fun statusesOf(type: SimklMediaType): List<SimklListStatus> =
    if (type == SimklMediaType.MOVIES) {
        listOf(SimklListStatus.PLAN_TO_WATCH, SimklListStatus.COMPLETED, SimklListStatus.DROPPED)
    } else {
        SimklListStatus.entries
    }

/** An empty library comes back as `{}`, `[]` or `null`. */
internal fun decodeAllItems(body: String): List<SimklEntry> =
    if (body.isEmptyReply()) emptyList() else decode(SimklAllItems.serializer(), body).entries()

private fun String.isEmptyReply(): Boolean = trim().let { it.isEmpty() || it == "null" || it == "[]" }

/**
 * The ratings delta (`/sync/ratings?date_from=`: every title whose rating changed) sets the rating of the stored entry
 * with the same Simkl id; its rows carry no episodes, so they never replace one. A removed rating (`user_rating` null)
 * is not cleared: the decoder cannot tell it from a row missing the field, and a rating kept too long only refuses a
 * Library removal (S3a), while one cleared wrongly would let a removal wipe it. The next full pull settles it.
 */
internal fun applySimklRatings(current: List<SimklEntry>, rated: List<SimklEntry>): List<SimklEntry> {
    val ratings = rated.mapNotNull { row -> row.media?.simklId?.let { id -> row.userRating?.let { id to it } } }.toMap()
    if (ratings.isEmpty()) return current
    return current.map { entry ->
        val id = entry.media?.simklId
        if (id != null && id in ratings) entry.copy(userRating = ratings[id]) else entry
    }
}

/**
 * A delta's entries replace the stored ones with the same Simkl id (whatever type they were filed under before: Simkl
 * may move a title between shows and anime); entries without an id are kept.
 */
internal fun mergeSimklEntries(current: List<SimklEntry>, delta: List<SimklEntry>): List<SimklEntry> {
    val changed = delta.mapNotNullTo(HashSet()) { it.media?.simklId }
    return current.filter { entry -> entry.media?.simklId?.let { it !in changed } ?: true } + delta
}
