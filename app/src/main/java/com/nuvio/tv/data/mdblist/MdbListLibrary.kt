package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingLibraryEntry
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRateLimitException
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.sharesAnyId
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MDBList as the Library source (Spec 105 S3a, D4): the Library is the watchlist. MDBList's watchlist writes are
 * idempotent (an add of a listed title answers `existing`, a removal of an unlisted one `not_found`), so unlike Simkl's
 * nothing is decided from a fresh sync first: a write goes once, unless this TV's view already shows the wanted state.
 *
 * Reads come from the synced snapshot's watchlist ([MdbListSync] pulls it when `watchlisted_at` moves) plus this TV's
 * writes the snapshot does not hold yet: such a change stays until a sync that started at least [SETTLE_MS] after it has
 * settled ([MdbListSync.settledRunStart]), or the key changes ([forget]). While the day's allowance is spent writes
 * fail (D5) and nothing is kept for later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MdbListTrackingLibraryProvider(
    private val store: MdbListSyncStore,
    private val sync: MdbListSync,
    private val session: MdbListSession,
    private val activeProfileId: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val projectionDispatcher: CoroutineDispatcher,
    /** The progress provider's refresh: the same sync, plus its TVDB-map fill and overlay settling (S6). */
    private val refreshSync: suspend (TrackingRefreshIntent) -> Unit,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.MDBLIST

    /** Per profile: this TV's watchlist changes, oldest first, under an epoch [forget] moves (a key change). */
    private class ProfileChanges(val epoch: Long, val made: List<MdbListLibraryChange>)

    private val changes = MutableStateFlow<Map<Int, ProfileChanges>>(emptyMap())

    private val perProfile = ConcurrentHashMap<Int, Flow<MdbListLibraryView>>()

    private val writeLock = Mutex()

    private fun viewFor(profileId: Int): Flow<MdbListLibraryView> = perProfile.computeIfAbsent(profileId) {
        combine(
            store.snapshot(profileId).map { mdbListLibraryRows(it?.watchlist) }.flowOn(projectionDispatcher),
            changes.map { it[profileId]?.made.orEmpty() }.distinctUntilChanged(),
            sync.settledRunStart(profileId)
        ) { rows, made, settled -> MdbListLibraryView(rows, liveChanges(made, settled)) }
            .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS, replayExpirationMillis = 0L), replay = 1)
    }

    private val view: Flow<MdbListLibraryView> = activeProfileId.flatMapLatest(::viewFor)

    override val isRefreshing: Flow<Boolean> = activeProfileId.flatMapLatest(sync::isPulling)

    override val items: Flow<List<LibraryEntry>> = view.map(MdbListLibraryView::entries).distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        val target = MdbListLibraryTarget.of(itemId, itemType)
        return view.map { it.isListed(target) }.distinctUntilChanged()
    }

    /**
     * One `/watchlist/items/add` or `/remove` for [item], unless this TV already shows it so. An add MDBList cannot
     * place (`not_found`) throws [MdbListUnmatchableException] with nothing recorded; a removal of a title MDBList does
     * not list is the wanted state, so it counts as done. Writes take one lock, so a second toggle decides from the
     * first one's result.
     */
    override suspend fun setInLibrary(profileId: Int, item: LibraryEntryInput, inLibrary: Boolean) = writeLock.withLock {
        val target = MdbListLibraryTarget.of(item.itemId, item.itemType)
        if (currentView(profileId).isListed(target) == inLibrary) return@withLock
        val epoch = epochOf(profileId)
        if (inLibrary && send(profileId, listOf(target), add = true) > 0) throw MdbListUnmatchableException()
        if (!inLibrary) send(profileId, listOf(target), add = false)
        val now = nowEpochMs()
        record(profileId, epoch, listOf(MdbListLibraryChange(target, if (inLibrary) item.toListedEntry(now) else null, now)))
    }

    /**
     * Library transfer: `/watchlist/items/add` in batches of [BATCH] (the transfer itself asks 10 at a time). A batch
     * MDBList fully placed is accepted whole; one with any `not_found` is sent again one title at a time (an add is
     * idempotent) to learn which. Titles without an IMDb or TMDB id count as tried and not accepted. The daily pause or
     * a `429` stops it ([TrackingRateLimitException]); a batch the pause kept from going is not counted as sent.
     */
    override suspend fun addAll(profileId: Int, items: List<LibraryEntryInput>): Set<String> = writeLock.withLock {
        val epoch = epochOf(profileId)
        val accepted = linkedSetOf<String>()
        val (placeable, unplaceable) = items.partition { watchlistBody(listOf(MdbListLibraryTarget.of(it.itemId, it.itemType))) != null }
        val sent = unplaceable.mapTo(linkedSetOf(), LibraryEntryInput::itemId)
        suspend fun recordAccepted() {
            val now = nowEpochMs()
            record(profileId, epoch, items.filter { it.itemId in accepted }.map {
                MdbListLibraryChange(MdbListLibraryTarget.of(it.itemId, it.itemType), it.toListedEntry(now), now)
            })
        }
        try {
            for (batch in placeable.chunked(BATCH)) {
                val targets = batch.map { MdbListLibraryTarget.of(it.itemId, it.itemType) }
                val notFound = try {
                    send(profileId, targets, add = true)
                } catch (paused: MdbListQuotaExceededException) {
                    throw paused // the pause stopped it before the request: not sent
                } catch (failure: MdbListException) {
                    sent += batch.map(LibraryEntryInput::itemId) // any other failure may have reached MDBList
                    throw failure
                }
                sent += batch.map(LibraryEntryInput::itemId)
                if (notFound == 0) {
                    accepted += batch.map(LibraryEntryInput::itemId)
                } else {
                    batch.zip(targets).forEach { (item, target) ->
                        if (send(profileId, listOf(target), add = true) == 0) accepted += item.itemId
                    }
                }
            }
        } catch (failure: MdbListException) {
            recordAccepted()
            val rateLimited = failure is MdbListQuotaExceededException || (failure as? MdbListApiException)?.status == 429
            if (rateLimited) throw TrackingRateLimitException(accepted.toSet(), sent.toSet())
            throw failure
        }
        recordAccepted()
        accepted
    }

    /**
     * The progress provider's refresh (the same sync), skipped when a run settled under [RECENT_SYNC_MS] ago: the
     * scheduler and Settings › Trackers › Sync now refresh progress and library back to back, and each run costs a
     * `/sync/last_activities` request against the day's allowance.
     */
    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val settledAt = sync.settledRunEnd(activeProfileId.value)
        if (settledAt != null && nowEpochMs() - settledAt in 0 until RECENT_SYNC_MS) return
        refreshSync(intent)
    }

    /** MDBList's watchlist for [profileId] now: a sync, then the snapshot's watchlist, without this TV's changes. */
    override suspend fun readLibrary(profileId: Int): List<TrackingLibraryEntry> {
        if (!sync.refresh(profileId, TrackingRefreshIntent.USER_INITIATED)) throw MdbListException("MDBList library not synced")
        val watchlist = store.get(profileId)?.watchlist ?: throw MdbListException("MDBList library not synced")
        return mdbListLibraryRows(watchlist).map { row -> TrackingLibraryEntry(row.listed, row.ids.transferIds()) }
    }

    /** The key on [profileId] changed: its changes on this TV were the old account's, and a write in flight too. */
    fun forget(profileId: Int) {
        changes.update { all -> all + (profileId to ProfileChanges((all[profileId]?.epoch ?: 0L) + 1L, emptyList())) }
    }

    /**
     * One add or removal call for [targets]; returns the reply's `not_found` count. Titles without an IMDb or TMDB id
     * are refused. The sync is told first, so the watchlist is pulled again until MDBList had time to show the write.
     */
    private suspend fun send(profileId: Int, targets: List<MdbListLibraryTarget>, add: Boolean): Int {
        val body = watchlistBody(targets) ?: throw MdbListUnmatchableException()
        val path = if (add) ADD_PATH else REMOVE_PATH
        // Before (a sync starting meanwhile must re-pull) and after (the call may have queued behind a long sync).
        sync.watchlistWritten(profileId, nowEpochMs())
        try {
            return mdbListMissingCount(session.call(profileId, MdbListRequest(MdbListHttpMethod.POST, path, jsonBody = body)).body)
        } finally {
            sync.watchlistWritten(profileId, nowEpochMs())
        }
    }

    private suspend fun currentView(profileId: Int): MdbListLibraryView = MdbListLibraryView(
        mdbListLibraryRows(store.get(profileId)?.watchlist),
        liveChanges(changes.value[profileId]?.made.orEmpty(), sync.settledRunStart(profileId).first())
    )

    private fun epochOf(profileId: Int): Long = changes.value[profileId]?.epoch ?: 0L

    /** Kept only while [profileId]'s epoch is the one the write started on; settled changes leave as it goes. */
    private suspend fun record(profileId: Int, epoch: Long, made: List<MdbListLibraryChange>) {
        if (made.isEmpty()) return
        val settled = sync.settledRunStart(profileId).first()
        changes.update { all ->
            val current = all[profileId]
            if ((current?.epoch ?: 0L) != epoch) all
            else all + (profileId to ProfileChanges(epoch, liveChanges(current?.made.orEmpty() + made, settled)))
        }
    }

    internal companion object {
        const val SHARE_TIMEOUT_MS = 5_000L

        /** How long a write gets to reach MDBList before a sync's answer replaces it (the sync's re-pull rule). */
        const val SETTLE_MS = WATCHLIST_SETTLE_MS

        /** A settled run this recent makes a library refresh a no-op (the paired progress refresh just ran). */
        const val RECENT_SYNC_MS = 30_000L

        const val BATCH = 100
        const val ADD_PATH = "/watchlist/items/add"
        const val REMOVE_PATH = "/watchlist/items/remove"

        /** The changes a settled run that started at [settledStart] may not hold yet. */
        fun liveChanges(made: List<MdbListLibraryChange>, settledStart: Long?): List<MdbListLibraryChange> =
            if (settledStart == null) made else made.filter { it.atEpochMs > settledStart - SETTLE_MS }
    }
}

/** MDBList could not place the title (no IMDb or TMDB id, or the add came back `not_found`). */
class MdbListUnmatchableException : MdbListException("MDBList could not match the title")

/** A title as the Library names it: its ids (from the content id) and whether it is a film. */
internal data class MdbListLibraryTarget(val itemId: String, val ids: TrackingExternalIds, val isMovie: Boolean) {
    companion object {
        fun of(itemId: String, itemType: String) =
            MdbListLibraryTarget(itemId, parseTrackingExternalIds(itemId), itemType.trim().lowercase() in MOVIE_TYPES)
    }

    fun matches(ids: TrackingExternalIds, isMovie: Boolean): Boolean = this.isMovie == isMovie && this.ids.sharesAnyId(ids)
}

/** A change made on this TV: [listed] = added (as the Library shows it), null = removed. */
internal data class MdbListLibraryChange(val target: MdbListLibraryTarget, val listed: LibraryEntry?, val atEpochMs: Long)

/** One synced watchlist title as the Library shows it. */
internal data class MdbListLibraryRow(val listed: LibraryEntry, val ids: TrackingExternalIds, val isMovie: Boolean)

/** The snapshot's watchlist with this TV's newer changes on top. */
internal class MdbListLibraryView(private val rows: List<MdbListLibraryRow>, private val changes: List<MdbListLibraryChange>) {
    fun isListed(target: MdbListLibraryTarget): Boolean =
        changes.lastOrNull { target.matches(it.target.ids, it.target.isMovie) }?.let { it.listed != null }
            ?: rows.any { target.matches(it.ids, it.isMovie) }

    /** Newest first, one per (type, id). */
    fun entries(): List<LibraryEntry> {
        val latest = mutableListOf<MdbListLibraryChange>()
        changes.asReversed().forEach { change ->
            if (latest.none { change.target.matches(it.target.ids, it.target.isMovie) }) latest += change
        }
        val synced = rows.filter { row -> latest.none { it.target.matches(row.ids, row.isMovie) } }.map { it.listed }
        return (synced + latest.mapNotNull { it.listed })
            .sortedByDescending(LibraryEntry::listedAt)
            .distinctBy { it.type to it.id }
    }
}

/** Every watchlist title Nuvio can name (an IMDb or TMDB id) as the Library shows it. */
internal fun mdbListLibraryRows(watchlist: List<MdbListWatchlistItem>?): List<MdbListLibraryRow> =
    watchlist.orEmpty().mapNotNull { item ->
        val contentId = item.media.contentId ?: return@mapNotNull null
        val listed = LibraryEntry(
            id = contentId,
            type = if (item.isMovie) "movie" else "series",
            name = item.media.title?.trim()?.takeIf(String::isNotEmpty) ?: contentId,
            poster = mdbListImageUrl(item.poster, "w500"),
            background = null,
            logo = null,
            description = null,
            releaseInfo = item.media.year?.toString(),
            imdbRating = null,
            genres = item.genres,
            addonBaseUrl = null,
            listedAt = parseMdbListListedAt(item.listedAt) ?: 0L
        )
        MdbListLibraryRow(listed, item.media.trackingIds(), item.isMovie)
    }

/**
 * A `/watchlist/items` row as stored, or null when it names no title Nuvio uses (no IMDb or TMDB id). Ids from `id`
 * (TMDb, as upstream NuvioTV reads it; the schema's film example has `id` = `ids.tmdb`), `imdb_id`, `tvdb_id`, then the
 * nested `ids` on top.
 */
internal fun MdbListWatchlistRow.toWatchlistItem(isMovie: Boolean): MdbListWatchlistItem? {
    val raw = LinkedHashMap<String, JsonElement>()
    id.positiveLong()?.let { raw["tmdb"] = JsonPrimitive(it) }
    imdbId.text()?.let { raw["imdb"] = JsonPrimitive(it) }
    tvdbId.positiveLong()?.let { raw["tvdb"] = JsonPrimitive(it) }
    ids.orEmpty().forEach { (key, value) -> if (value !is JsonNull) raw[key] = value }
    val media = MdbListMedia(title = title, year = releaseYear.positiveLong()?.toInt(), ids = raw)
    if (media.contentId == null) return null
    return MdbListWatchlistItem(
        media = media,
        isMovie = isMovie,
        poster = poster.text(),
        genres = genres.genreNames(),
        listedAt = watchlistAt.text() ?: listedAt.text() ?: addedAt.text()
    )
}

/**
 * An added date as MDBList may write it: ISO-8601, `2025-02-02 17:28:22.000000` (UTC; the form upstream's tests
 * carry), or a bare date. Unreadable = null.
 */
internal fun parseMdbListListedAt(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    parseMdbListTime(text)?.let { return it }
    return try {
        LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeException) {
        try {
            LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }
}

/** A TMDb image path (`/abc.jpg`) as a URL at [size]; a full https URL as is; anything else null (upstream's rule). */
internal fun mdbListImageUrl(value: String?, size: String): String? = when {
    value == null -> null
    value.startsWith("https://") -> value
    value.startsWith('/') && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
    else -> null
}

/** `{movies: [{ids}], shows: [{ids}]}` for [targets]; null when one has no IMDb or TMDB id. */
internal fun watchlistBody(targets: List<MdbListLibraryTarget>): String? {
    val entries = targets.map { target ->
        if (isMdbListUnmatchable(target.itemId)) return null
        target to (target.ids.toMdbListIds() ?: return null)
    }
    return buildJsonObject {
        val (movies, shows) = entries.partition { it.first.isMovie }
        if (movies.isNotEmpty()) put("movies", JsonArray(movies.map { (_, ids) -> buildJsonObject { put("ids", ids) } }))
        if (shows.isNotEmpty()) put("shows", JsonArray(shows.map { (_, ids) -> buildJsonObject { put("ids", ids) } }))
    }.toString()
}

private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonElement?.positiveLong(): Long? = text()?.toLongOrNull()?.takeIf { it > 0 }

/** Genres as names: an array of names or of `{name|title|slug}` objects, or one comma-separated text. */
private fun JsonElement?.genreNames(): List<String> {
    val names = when (this) {
        is JsonArray -> mapNotNull { genre ->
            genre.text() ?: (genre as? JsonObject)?.let { it["name"].text() ?: it["title"].text() ?: it["slug"].text() }
        }
        is JsonPrimitive -> text()?.split(',').orEmpty()
        else -> emptyList()
    }
    return names.map(String::trim).filter(String::isNotEmpty).distinct()
}

/** The ids MDBList gave, as Nuvio's id model (IMDb, TMDB, TVDB, MDBList). */
private fun MdbListMedia.trackingIds(): TrackingExternalIds =
    TrackingExternalIds(imdb = imdb, tmdb = tmdb, tvdb = id("tvdb"), mdblist = id("mdblist"))

/** The ids a Library transfer matches by (`tt...`, `tmdb:N`). */
private fun TrackingExternalIds.transferIds(): Set<String> = setOfNotNull(imdb, tmdb?.let { "tmdb:$it" })

private fun LibraryEntryInput.toListedEntry(nowEpochMs: Long) = LibraryEntry(
    id = itemId,
    type = if (itemType.trim().lowercase() in MOVIE_TYPES) "movie" else "series",
    name = title,
    poster = poster,
    posterShape = posterShape,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    listedAt = nowEpochMs
)

private val MOVIE_TYPES = setOf("movie", "movies", "film")
