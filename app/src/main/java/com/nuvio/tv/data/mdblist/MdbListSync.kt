package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Brings a profile's [MdbListSnapshot] up to date (Spec 105 S2a; api.mdblist.com/schema, sync endpoints, read 2026-11-17).
 * `/sync/last_activities` comes first on every run; when no watched or paused stamp moved, that one call is all. The
 * first sync, a `since` older than the journal keeps (30 days) and a journal that answers `requires_full_sync` (409, or
 * the flag in a 200) pull `/sync/watched` in cursor pages plus `/sync/playback`. Otherwise moved watched stamps read
 * `/sync/journal?since=` (removals included) and moved paused stamps re-read `/sync/playback`. The activities reply is
 * read before the fetches it anchors and saved with them only when every fetch succeeded, so a failed run asks again
 * from the same `since` next time; its `server_time` (MDBList's clock, never the TV's) is the next `since`. A quiet run
 * saves only to move a `since` older than a day on, so a long quiet spell never forces a full pull.
 *
 * The watchlist (S3a, the Library) rides the same gate: it is pulled whole (`/watchlist/items`, no journal covers it)
 * when it was never pulled or `watchlisted_at` moved, and saved in the same write as the rest.
 *
 * Nothing is sent while the profile's daily allowance is spent (D5). No automatic floor: the tracking scheduler already
 * refreshes at most every 5 minutes on resume, and a quiet run costs one request.
 */
class MdbListSync(
    private val session: MdbListSession,
    private val store: MdbListSyncStore,
    private val accounts: TrackingAccountStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val locksGuard = Mutex()
    private val locks = mutableMapOf<Int, Mutex>()

    /** Per profile: when the last run that settled ([refresh] true) started. */
    private val settledStarts = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** Per profile: when the last settled run ended (the library's refresh skip). */
    private val settledEnds = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** Profiles with a run in progress. */
    private val pulling = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Per profile: the latest watchlist write this TV made. Until a run that started [WATCHLIST_SETTLE_MS] after it
     * has pulled the watchlist, every run pulls it again whatever `watchlisted_at` says: a pull right after a write may
     * still show the old list under the new stamp, and that stamp would then keep it.
     */
    private val watchlistWrites = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /**
     * When [profileId]'s last settled run started (null: none since the app started). A change made on this TV before
     * that, with time to reach MDBList, is in the snapshot now (the library's changes leave by it, Simkl's rule).
     */
    fun settledRunStart(profileId: Int): Flow<Long?> = settledStarts.map { it[profileId] }.distinctUntilChanged()

    fun isPulling(profileId: Int): Flow<Boolean> = pulling.map { profileId in it }.distinctUntilChanged()

    /** When [profileId]'s last settled run ended, on this TV's clock; null: none since the app started. */
    fun settledRunEnd(profileId: Int): Long? = settledEnds.value[profileId]

    /** This TV changed [profileId]'s watchlist at [atEpochMs] (see [watchlistWrites]). */
    fun watchlistWritten(profileId: Int, atEpochMs: Long) {
        watchlistWrites.update { it + (profileId to maxOf(it[profileId] ?: atEpochMs, atEpochMs)) }
    }

    /**
     * True when the snapshot now holds MDBList's state as of this call (synced, or nothing had moved); false when it
     * was skipped (no key, allowance spent) or the key changed meanwhile. MDBList failures throw. [intent] does not
     * change what is sent: every run is gated by the activities call alone.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun refresh(profileId: Int, intent: TrackingRefreshIntent): Boolean {
        val startedAt = nowEpochMs()
        val settled = lockFor(profileId).withLock {
            pulling.update { it + profileId }
            try {
                run(profileId, startedAt)
            } finally {
                pulling.update { it - profileId }
            }
        }
        // Never backwards: runs overlap (progress and library refreshes, a transfer read) and may end out of order.
        if (settled) {
            settledStarts.update { it + (profileId to maxOf(it[profileId] ?: startedAt, startedAt)) }
            settledEnds.update { it + (profileId to nowEpochMs()) }
        }
        return settled
    }

    private suspend fun run(profileId: Int, startedAt: Long): Boolean {
        val account = accounts.get(profileId, TrackingProviderId.MDBLIST)?.takeIf { !it.revoked } ?: return false
        if (session.quota.state(profileId).isPaused(nowEpochMs())) return false
        val generation = store.generation(profileId)
        val current = store.get(profileId)

        val activities = decodeMdbList(MdbListActivities.serializer(), get(profileId, ACTIVITIES_PATH).body)
        val saved = current?.activities
        val since = current?.journalSince
        val serverNow = parseMdbListTime(activities.serverTime) ?: nowEpochMs()
        val next = when {
            current == null || saved == null || since == null -> fullPull(profileId)
            else -> {
                val watchedMoved = saved.watchedStamps() != activities.watchedStamps()
                val pausedMoved = saved.pausedStamps() != activities.pausedStamps()
                val sinceAge = parseMdbListTime(since)?.let { serverNow - it }
                when {
                    // A quiet run moves `since` on once a day, so a long quiet spell never lapses the journal window.
                    !watchedMoved && !pausedMoved ->
                        current.takeIf { sinceAge != null && sinceAge > SINCE_REFRESH_AGE_MS && activities.serverTime != null }
                    sinceAge == null || sinceAge > JOURNAL_SAFE_AGE_MS -> fullPull(profileId)
                    else -> {
                        val marks = if (watchedMoved) journalDelta(profileId, current.marks, since) else current.marks
                        if (marks == null) {
                            fullPull(profileId)
                        } else {
                            current.copy(marks = marks, playback = if (pausedMoved) fetchPlayback(profileId) else current.playback)
                        }
                    }
                }
            }
        }
        // The watchlist: kept while its stamp stands and no write of this TV may be missing from it, else pulled whole
        // (no journal covers it).
        val writtenAt = watchlistWrites.value[profileId]
        val keptWatchlist = current?.watchlist
            ?.takeIf { writtenAt == null && saved != null && saved.watchlistedAt == activities.watchlistedAt }
        val watchlist = keptWatchlist ?: fetchWatchlist(profileId)
        // What to save: the progress fetches' result; else, for a pulled watchlist alone, the current snapshot; else
        // nothing (a quiet run).
        val changed = next ?: current?.takeIf { keptWatchlist == null }
        // A list pulled inside a write's settle time may predate it: saved without the stamp, so the next run (after a
        // restart too, when the write marker is gone) pulls it again.
        val settledWrite = writtenAt == null || startedAt >= writtenAt + WATCHLIST_SETTLE_MS
        val savedActivities = if (settledWrite) activities else activities.copy(watchlistedAt = null)
        // No server_time: the old since stays (journal rows are last-state upserts, so asking again is safe).
        val written = changed == null || store.saveIf(
            profileId, generation,
            changed.copy(activities = savedActivities, journalSince = activities.serverTime ?: since, watchlist = watchlist)
        )
        if (!written) return false
        if (writtenAt != null && settledWrite) {
            // Removed only if no newer write came meanwhile.
            watchlistWrites.update { all -> if (all[profileId] == writtenAt) all - profileId else all }
        }
        // Only for the key this run started on.
        accounts.update(profileId, TrackingProviderId.MDBLIST) { stored ->
            stored?.takeIf { it.accessToken == account.accessToken && !it.revoked }?.copy(lastSyncedAtEpochMs = nowEpochMs()) ?: stored
        }
        return true
    }

    /**
     * Clears the saved playbacks of [contentId] (only the episode's when [season] and [episode] are given) at MDBList
     * (`/scrobble/clear`, its Continue Watching removal), then from the snapshot. The targets come from MDBList's current
     * list, not the snapshot: a pause made since the last sync must go too. The calls run outside the sync lock (a long
     * full pull must not hold a removal past the router's time bound); only the snapshot edit takes it. A playback
     * already gone (404) counts as cleared; any other failure is thrown after the others were tried.
     */
    suspend fun removePlayback(profileId: Int, contentId: String, season: Int?, episode: Int?) {
        val generation = store.generation(profileId)
        val targets = fetchPlayback(profileId).filter { it.matches(contentId, season, episode) }
        var firstFailure: MdbListException? = null
        val cleared = targets.filter { target ->
            // The key changed meanwhile: these are the old account's playbacks, never cleared on the new one.
            if (store.generation(profileId) != generation) return@filter false
            val body = target.clearTarget()?.scrobbleBody() ?: return@filter false
            try {
                session.call(profileId, MdbListRequest(MdbListHttpMethod.POST, CLEAR_PATH, jsonBody = body.toString()))
                true
            } catch (failure: MdbListException) {
                val gone = failure is MdbListApiException && failure.status == 404
                if (!gone && firstFailure == null) firstFailure = failure
                gone
            }
        }.mapNotNullTo(HashSet()) { it.titleKey() }
        if (cleared.isNotEmpty()) {
            lockFor(profileId).withLock {
                val snapshot = store.get(profileId) ?: return@withLock
                store.saveIf(profileId, generation, snapshot.copy(playback = snapshot.playback.filterNot { it.titleKey() in cleared }))
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun fullPull(profileId: Int): MdbListSnapshot {
        val movies = fetchWatched(profileId, "movie").flatMap { it.movies.orEmpty() }.mapNotNull { row ->
            val movie = row.movie ?: return@mapNotNull null
            MdbListWatchedMark(movie, watchedAt = row.lastWatchedAt ?: row.watchedAt)
        }
        val episodes = fetchWatched(profileId, "episode").flatMap { it.episodes.orEmpty() }.mapNotNull { row ->
            val episode = row.episode ?: return@mapNotNull null
            val show = episode.show ?: return@mapNotNull null
            // A row without both numbers would mark the whole series (upstream dc3776af9): dropped.
            val season = episode.season?.takeIf { it >= 0 } ?: return@mapNotNull null
            val number = episode.number?.takeIf { it > 0 } ?: return@mapNotNull null
            MdbListWatchedMark(show, season, number, row.lastWatchedAt ?: row.watchedAt)
        }
        return MdbListSnapshot(marks = movies + episodes, playback = fetchPlayback(profileId))
    }

    /** Every `/sync/watched` page of one [mediatype], following `next_cursor` (body, else `X-Next-Cursor`). */
    private suspend fun fetchWatched(profileId: Int, mediatype: String): List<MdbListWatchedPage> =
        pages(profileId, WATCHED_PATH, mapOf("mediatype" to mediatype), MdbListWatchedPage.serializer()) { page, response ->
            page.pagination?.nextCursor ?: response.headers["x-next-cursor"]
        }

    /**
     * Every watchlist page (films and shows, with poster and genres). No cursor while `X-Has-More` or the page's
     * `has_more` says more = a fault, never a short list saved as the whole.
     */
    private suspend fun fetchWatchlist(profileId: Int): List<MdbListWatchlistItem> =
        pages(profileId, WATCHLIST_PATH, mapOf("append_to_response" to "poster,genres"), MdbListWatchlistPage.serializer()) { page, response ->
            val cursor = page.pagination?.nextCursor ?: response.headers["x-next-cursor"]
            val more = response.headers["x-has-more"].equals("true", ignoreCase = true) || page.pagination?.hasMore == true
            if (cursor.isNullOrBlank() && more) {
                throw MdbListException("MDBList paging did not end")
            }
            cursor
        }.flatMap { page ->
            page.movies.orEmpty().mapNotNull { it.toWatchlistItem(isMovie = true) } +
                page.shows.orEmpty().mapNotNull { it.toWatchlistItem(isMovie = false) }
        }

    private suspend fun fetchPlayback(profileId: Int): List<MdbListPlayback> {
        val body = get(profileId, PLAYBACK_PATH).body
        return if (body.isEmptyReply()) emptyList() else decodeMdbList(ListSerializer(MdbListPlayback.serializer()), body)
    }

    /**
     * [marks] with the journal's rows since [since] applied, or null when MDBList wants a full sync. Only film and
     * episode rows of the watched category count: season and show rows would be whole-series marks, and each episode
     * carries its own row anyway (schema: "Episode rows always carry each episode's own state").
     */
    private suspend fun journalDelta(profileId: Int, marks: List<MdbListWatchedMark>, since: String): List<MdbListWatchedMark>? {
        val rows = mutableListOf<MdbListJournalRow>()
        try {
            pages(profileId, JOURNAL_PATH, mapOf("since" to since), MdbListJournalPage.serializer(), cursorReplacesQuery = true) { page, _ ->
                if (page.requiresFullSync == true) throw FullSyncRequired()
                // A 200 without the list is not an empty journal: saving it would skip changes for good.
                rows += page.journal ?: throw MdbListException("MDBList reply not understood")
                page.pagination?.nextCursor
            }
        } catch (_: FullSyncRequired) {
            return null
        } catch (failure: MdbListApiException) {
            if (failure.status == 409) return null
            throw failure
        }
        return applyMdbListJournal(marks, rows)
    }

    /**
     * Reads [path] page by page (`limit` 1000) until [nextCursor] returns none. The cursor goes with the first query,
     * or instead of it when [cursorReplacesQuery] (the journal rejects `since` with `cursor`). A cursor seen twice or
     * more than [MAX_PAGES] pages is a fault, not an endless loop.
     */
    private suspend fun <T> pages(
        profileId: Int,
        path: String,
        firstQuery: Map<String, String>,
        serializer: KSerializer<T>,
        cursorReplacesQuery: Boolean = false,
        nextCursor: (T, MdbListResponse) -> String?
    ): List<T> {
        val pages = mutableListOf<T>()
        val seen = HashSet<String>()
        var query = firstQuery + (LIMIT to PAGE_SIZE)
        while (true) {
            val response = get(profileId, path, query)
            val page = decodeMdbList(serializer, response.body)
            pages += page
            val cursor = nextCursor(page, response)?.takeIf(String::isNotBlank) ?: return pages
            if (!seen.add(cursor) || pages.size >= MAX_PAGES) throw MdbListException("MDBList paging did not end")
            query = (if (cursorReplacesQuery) emptyMap() else firstQuery) + mapOf(LIMIT to PAGE_SIZE, "cursor" to cursor)
        }
    }

    private suspend fun get(profileId: Int, path: String, query: Map<String, String> = emptyMap()): MdbListResponse =
        session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, path, query))

    private suspend fun lockFor(profileId: Int): Mutex = locksGuard.withLock { locks.getOrPut(profileId) { Mutex() } }

    private class FullSyncRequired : RuntimeException()

    private companion object {
        const val ACTIVITIES_PATH = "/sync/last_activities"
        const val WATCHED_PATH = "/sync/watched"
        const val PLAYBACK_PATH = "/sync/playback"
        const val JOURNAL_PATH = "/sync/journal"
        const val CLEAR_PATH = "/scrobble/clear"
        const val WATCHLIST_PATH = "/watchlist/items"
        const val LIMIT = "limit"
        const val PAGE_SIZE = "1000"

        /** 100,000 rows of one kind: far past any real history, short of a runaway loop. */
        const val MAX_PAGES = 100

        /** The journal keeps 30 days; a day's margin. Ages are on MDBList's clock (`server_time`) when it gives one. */
        const val JOURNAL_SAFE_AGE_MS = 29L * 24 * 60 * 60 * 1_000

        const val SINCE_REFRESH_AGE_MS = 24L * 60 * 60 * 1_000
    }
}

/** How long a watchlist write gets to show in MDBList's list (the library overlay's settle time too). */
internal const val WATCHLIST_SETTLE_MS = 60_000L

private fun String.isEmptyReply(): Boolean = trim().let { it.isEmpty() || it == "null" || it == "{}" }

/** A playback's title and episode, as Continue Watching names them; null when it names no title Nuvio uses. */
internal fun MdbListPlayback.titleKey(): String? {
    val contentId = media?.contentId ?: return null
    return if (isEpisode) "$contentId:${episode?.season}:${episode?.number}" else contentId
}

private fun MdbListPlayback.matches(contentId: String, season: Int?, episode: Int?): Boolean {
    if (media?.contentId != contentId) return false
    if (season == null || episode == null) return true
    return isEpisode && this.episode?.let { it.season == season && it.number == episode } == true
}

/** The `/scrobble/clear` target: the film's ids, or the show's with the episode's season and number. */
private fun MdbListPlayback.clearTarget(): MdbListTarget? {
    val media = media ?: return null
    val ids = mdbListIds(SCROBBLE_ID_KEYS.associateWith(media::id)) ?: return null
    if (!isEpisode) return MdbListTarget.Movie(ids)
    val season = episode?.season?.takeIf { it >= 0 } ?: return null
    val number = episode.number?.takeIf { it > 0 } ?: return null
    return MdbListTarget.Episode(ids, season, number)
}

/** MDBList's scrobble ids ("at least one of: imdb, tmdb, trakt, tvdb, mdblist"). */
private val SCROBBLE_ID_KEYS = listOf("imdb", "tmdb", "trakt", "tvdb", "mdblist")

/**
 * [marks] with the journal's watched film and episode rows applied as MDBList's current truth: `added` sets the mark
 * (watch date `value_at`, else `action_at`), `removed` drops it; other statuses and categories are ignored. A row meets
 * its stored mark through any shared id form, so a title stored by TMDB id still matches a row naming its IMDb id.
 */
internal fun applyMdbListJournal(marks: List<MdbListWatchedMark>, rows: List<MdbListJournalRow>): List<MdbListWatchedMark> {
    val canonical = HashMap<String, String>()
    val mediaByCanonical = HashMap<String, MdbListMedia>()

    fun canonicalOf(isEpisode: Boolean, media: MdbListMedia): String? {
        val prefix = if (isEpisode) "show|" else "movie|"
        val aliases = media.aliases().map { prefix + it }
        if (aliases.isEmpty()) return null
        val key = aliases.firstNotNullOfOrNull(canonical::get) ?: aliases.first()
        aliases.forEach { canonical.putIfAbsent(it, key) }
        mediaByCanonical.putIfAbsent(key, media)
        return key
    }

    val byKey = LinkedHashMap<String, MdbListWatchedMark>()
    marks.forEach { mark ->
        val key = canonicalOf(mark.isEpisode, mark.media) ?: return@forEach
        byKey["$key:${mark.season}:${mark.episode}"] = mark
    }
    rows.forEach { row ->
        if (row.category != "watched") return@forEach
        val isEpisode = when (row.itemType) {
            "movie" -> false
            "episode" -> true
            else -> return@forEach
        }
        if (isEpisode && (row.season ?: -1) < 0) return@forEach
        if (isEpisode && (row.episode ?: 0) < 1) return@forEach
        val season = if (isEpisode) row.season else null
        val number = if (isEpisode) row.episode else null
        val key = canonicalOf(isEpisode, MdbListMedia(ids = row.ids)) ?: return@forEach
        val markKey = "$key:$season:$number"
        when (row.status) {
            "removed" -> byKey.remove(markKey)
            "added" -> byKey[markKey] = MdbListWatchedMark(
                media = mediaByCanonical[key] ?: MdbListMedia(ids = row.ids),
                season = season,
                episode = number,
                watchedAt = row.valueAt ?: row.actionAt
            )
        }
    }
    return byKey.values.toList()
}
