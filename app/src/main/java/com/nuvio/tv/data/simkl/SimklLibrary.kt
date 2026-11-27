package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingLibraryEntry
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRateLimitException
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.sharesAnyId
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.core.tracking.TrackingListStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Simkl as the Library source (Spec 106 S3a). The Library lists every Simkl list (G1: Watching, Plan to watch, On hold,
 * Completed, Dropped), so membership means "on any list". Simkl keeps a title on exactly one list:
 * - an add puts a title on no list onto Plan to watch and never moves one already listed (D2);
 * - a removal is Simkl's whole-title `/sync/history/remove`, which also wipes the title's watch history and rating
 *   (api.simkl.org remove-from-history). One that would wipe something is refused with nothing sent unless the user
 *   agreed to exactly that loss ([SimklRemovalNeedsConfirmException]; upstream NuvioTV's rule; the detail page's list
 *   picker asks first, F1b); one matching more than one Simkl entry (anime seasons sharing a show's id) always is
 *   ([SimklDuplicateEntriesException]);
 * - the detail page's list picker moves a title between lists ([moveToList], F1/F2).
 * All decide from Simkl's rows as of the write: each write first runs a gated sync (one `/sync/activities` call when
 * nothing moved), so a watch this TV sent, or a list change made elsewhere, is seen; no synced library = no write. A
 * mark, stop or other Library write sent between that sync and this write's turn on the writer lock makes it decide
 * again there from a fresh pull ([SimklHistoryWriter.listWrite]).
 *
 * Reads come from S2a's synced snapshot plus this TV's writes the snapshot does not hold yet: such a change stays until
 * a sync that started at least [SETTLE_MS] after it has settled ([SimklSync.settledRunStart]), or the account changes
 * ([forget]). Writes take S2b's writer turn (one Simkl write lock); a spent day fails them, nothing is kept for later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklTrackingLibraryProvider(
    private val store: SimklSyncStore,
    private val sync: SimklSync,
    private val writer: SimklHistoryWriter,
    private val activeProfileId: StateFlow<Int>,
    private val scope: CoroutineScope,
    private val projectionDispatcher: CoroutineDispatcher,
    private val isPaused: (profileId: Int) -> Boolean = { false },
    private val animeSeasons: (profileId: Int) -> Flow<SimklAnimeSeasons> = { flowOf(SimklAnimeSeasons.DEFAULT) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.SIMKL

    /** Per profile: this TV's library changes, oldest first, under an epoch [forget] moves (a grant change). */
    private class ProfileChanges(val epoch: Long, val made: List<SimklLibraryChange>)

    private val changes = MutableStateFlow<Map<Int, ProfileChanges>>(emptyMap())

    private val perProfile = ConcurrentHashMap<Int, Flow<SimklLibraryView>>()

    private fun viewFor(profileId: Int): Flow<SimklLibraryView> = perProfile.computeIfAbsent(profileId) {
        combine(
            combine(store.snapshot(profileId), animeSeasons(profileId).distinctUntilChanged(), ::simklLibraryRows)
                .flowOn(projectionDispatcher),
            changes.map { it[profileId]?.made.orEmpty() }.distinctUntilChanged(),
            sync.settledRunStart(profileId)
        ) { rows, made, settled -> SimklLibraryView(rows, liveChanges(made, settled)) }
            .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS, replayExpirationMillis = 0L), replay = 1)
    }

    private val view: Flow<SimklLibraryView> = activeProfileId.flatMapLatest(::viewFor)

    override val isRefreshing: Flow<Boolean> = activeProfileId.flatMapLatest(sync::isPulling)

    override val items: Flow<List<LibraryEntry>> = view.map(SimklLibraryView::entries).distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        val target = SimklLibraryTarget.of(itemId, itemType)
        return view.map { it.isListed(target) }.distinctUntilChanged()
    }

    /**
     * Decided from Simkl's rows alone (this TV's unsettled changes never override a fresh sync), first from the sync
     * this call runs, then again under the writer lock from a fresh pull if anything was sent meanwhile
     * ([SimklHistoryWriter.listWrite]). A removal that would lose something is refused ([removeFromSimkl]).
     */
    override suspend fun setInLibrary(profileId: Int, item: LibraryEntryInput, inLibrary: Boolean) {
        if (!inLibrary) return removeFromSimkl(profileId, item, agreed = null)
        val target = SimklLibraryTarget.of(item.itemId, item.itemType)
        if (isSimklUnmatchable(item.itemId)) throw SimklUnmatchableException()
        val epoch = epochOf(profileId)
        val matching = decideAndSend(profileId, target) { matching ->
            if (matching.isEmpty()) addToListRequest(listOf(item)) else null
        }
        // Sent, or Simkl already had it so: either way this TV shows it now, on the list Simkl has it on (an add lands
        // on Plan to watch and never moves a listed title).
        val shown = matching.firstOrNull()?.listed ?: item.toListedEntry(nowEpochMs())
        record(profileId, epoch, listOf(SimklLibraryChange(target, shown, nowEpochMs())))
    }

    /** [item] as the detail page's list picker opens on it (F1/F2): this TV's view, no call. */
    suspend fun listing(profileId: Int, item: LibraryEntryInput): SimklListing =
        currentView(profileId).listing(SimklLibraryTarget.of(item.itemId, item.itemType))

    /**
     * Puts [item] on [to] (F1/F2): a title on one Simkl entry moves by that entry's Simkl id, any other by its ids (an
     * add; with several matching entries Simkl picks one). Nothing is sent when it is already there. Returns the list
     * it was on before, null for none. Decided like [setInLibrary].
     */
    suspend fun moveToList(profileId: Int, item: LibraryEntryInput, to: TrackingListStatus): TrackingListStatus? {
        val target = SimklLibraryTarget.of(item.itemId, item.itemType)
        if (isSimklUnmatchable(item.itemId)) throw SimklUnmatchableException()
        val epoch = epochOf(profileId)
        val matching = decideAndSend(profileId, target) { matching ->
            val single = matching.singleOrNull()
            when {
                single == null -> addToListRequest(listOf(item), to)
                single.listed.trackerList == to -> null
                else -> moveRequest(single.entry, to)
            }
        }
        val now = nowEpochMs()
        val shown = (matching.firstOrNull()?.listed ?: item.toListedEntry(now)).copy(trackerList = to)
        record(profileId, epoch, listOf(SimklLibraryChange(target, shown, now)))
        return matching.firstOrNull()?.listed?.trackerList
    }

    /**
     * Takes [item] off every Simkl list (F1b): Simkl's whole-title removal, by the one matching entry's Simkl id. Refused
     * with nothing sent when more than one entry matches ([SimklDuplicateEntriesException]), or when it would lose
     * something other than what the user [agreed] to lose ([SimklRemovalNeedsConfirmException] carries what it would
     * lose now). No match while this TV shows it listed = [SimklUnmatchableException]. Decided like [setInLibrary].
     */
    suspend fun removeFromSimkl(profileId: Int, item: LibraryEntryInput, agreed: SimklListing?) {
        val target = SimklLibraryTarget.of(item.itemId, item.itemType)
        val epoch = epochOf(profileId)
        val shownListed = currentView(profileId).isListed(target)
        decideAndSend(profileId, target) { matching ->
            when {
                matching.isEmpty() -> if (shownListed) throw SimklUnmatchableException() else null
                matching.size > 1 -> throw SimklDuplicateEntriesException()
                else -> {
                    val now = matching.single().entry.listing()
                    if (now.removalNeedsConfirm && !now.losesOnly(agreed)) {
                        throw SimklRemovalNeedsConfirmException(now)
                    }
                    removeRequest(matching.single().entry)
                }
            }
        }
        record(profileId, epoch, listOf(SimklLibraryChange(target, null, nowEpochMs())))
    }

    /**
     * Runs the gated sync, then [decide] under the writer lock on the rows matching [target] (from a fresh pull if
     * anything was sent since the sync), sends its request and returns the rows it decided from. A reply naming the
     * title in `not_found` = [SimklUnmatchableException].
     */
    private suspend fun decideAndSend(
        profileId: Int,
        target: SimklLibraryTarget,
        decide: (matching: List<SimklLibraryRow>) -> SimklRequest?
    ): List<SimklLibraryRow> {
        val seen = writer.sendCount()
        val first = simklLibraryRows(syncedSnapshot(profileId))
        var matching = emptyList<SimklLibraryRow>()
        val reply = writer.listWrite(profileId, seen) { stale ->
            matching = (if (stale) pulledRows(profileId) else first).filter { target.matches(it.ids, it.entry.isMovie) }
            decide(matching)
        }
        if (reply != null && simklNotFoundIds(reply.body).isNotEmpty()) throw SimklUnmatchableException()
        return matching
    }

    /**
     * One `/sync/add-to-list` call for every title on no Simkl list (Library transfer, T7). Titles already listed
     * count as accepted with nothing sent; ones Simkl cannot match (no id it knows, or `not_found`) are left out. A
     * spent day or a `429` stops the transfer ([TrackingRateLimitException]). Decided like [setInLibrary].
     */
    override suspend fun addAll(profileId: Int, items: List<LibraryEntryInput>): Set<String> {
        if (isPaused(profileId)) throw TrackingRateLimitException(sent = emptySet())
        val epoch = epochOf(profileId)
        val seen = writer.sendCount()
        var alreadyThere: Set<String> = emptySet()
        var toSend: List<LibraryEntryInput> = emptyList()
        val reply = try {
            val first = simklLibraryRows(syncedSnapshot(profileId))
            writer.listWrite(profileId, seen) { stale ->
                val rows = if (stale) pulledRows(profileId) else first
                val (listed, unlisted) = items.partition { item ->
                    val target = SimklLibraryTarget.of(item.itemId, item.itemType)
                    rows.any { target.matches(it.ids, it.entry.isMovie) }
                }
                alreadyThere = listed.mapTo(linkedSetOf(), LibraryEntryInput::itemId)
                toSend = unlisted.filterNot { isSimklUnmatchable(it.itemId) }
                toSend.takeIf { it.isNotEmpty() }?.let(::addToListRequest)
            }
        } catch (_: SimklQuotaExceededException) {
            throw TrackingRateLimitException(alreadyThere, sent = alreadyThere)
        } catch (failure: SimklApiException) {
            // Before the send (the sync) nothing went; at the send the whole call did.
            if (failure.status == 429) throw TrackingRateLimitException(alreadyThere, sent = if (toSend.isNotEmpty()) null else alreadyThere)
            throw failure
        } ?: return alreadyThere
        val missed = simklNotFoundIds(reply.body)
        val added = toSend.filter { item -> missed.none { it.sharesAnyId(parseTrackingExternalIds(item.itemId)) } }
        val now = nowEpochMs()
        record(profileId, epoch, added.map { SimklLibraryChange(SimklLibraryTarget.of(it.itemId, it.itemType), it.toListedEntry(now), now) })
        return alreadyThere + added.map(LibraryEntryInput::itemId)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        sync.refresh(activeProfileId.value, intent)
    }

    /** Simkl's library for [profileId] now ([syncedSnapshot]), without this TV's changes. */
    override suspend fun readLibrary(profileId: Int): List<TrackingLibraryEntry> =
        simklLibraryRows(syncedSnapshot(profileId), animeSeasons(profileId).first()).map { row -> TrackingLibraryEntry(row.listed, row.ids.transferIds()) }

    /** The account on [profileId] changed: its changes on this TV were the old account's, and a write in flight too. */
    fun forget(profileId: Int) {
        changes.update { all -> all + (profileId to ProfileChanges((all[profileId]?.epoch ?: 0L) + 1L, emptyList())) }
    }

    /**
     * A sync run the user asked for (one `/sync/activities` call when nothing moved, Simkl's own rule), then the
     * snapshot. A run that does not settle (failed, signed out, the day spent, kept marks unsent) or leaves no synced
     * snapshot throws: nothing is read or written from a stale or missing library.
     */
    private suspend fun syncedSnapshot(profileId: Int): SimklSnapshot {
        if (!sync.refresh(profileId, TrackingRefreshIntent.USER_INITIATED)) throw SimklException("Simkl library not synced")
        return store.get(profileId)?.takeIf { it.activities != null } ?: throw SimklException("Simkl library not synced")
    }

    /** Under the writer lock: a pull only (the replay would wait on that lock), then the rows. */
    private suspend fun pulledRows(profileId: Int): List<SimklLibraryRow> {
        if (!sync.pullNow(profileId)) throw SimklException("Simkl library not synced")
        return simklLibraryRows(store.get(profileId)?.takeIf { it.activities != null } ?: throw SimklException("Simkl library not synced"))
    }

    private suspend fun currentView(profileId: Int): SimklLibraryView = SimklLibraryView(
        simklLibraryRows(store.get(profileId), animeSeasons(profileId).first()),
        liveChanges(changes.value[profileId]?.made.orEmpty(), sync.settledRunStart(profileId).first())
    )

    private fun epochOf(profileId: Int): Long = changes.value[profileId]?.epoch ?: 0L

    /** Kept only while [profileId]'s epoch is the one the write started on; settled changes leave as it goes. */
    private suspend fun record(profileId: Int, epoch: Long, made: List<SimklLibraryChange>) {
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

        /** How long a write gets to reach Simkl before a sync's answer replaces it (S2a's rule). */
        const val SETTLE_MS = 60_000L

        /** The changes a settled run that started at [settledStart] may not hold yet. */
        fun liveChanges(made: List<SimklLibraryChange>, settledStart: Long?): List<SimklLibraryChange> =
            if (settledStart == null) made else made.filter { it.atEpochMs > settledStart - SETTLE_MS }
    }
}

/** Removing this title at Simkl would wipe what [listing] names: nothing was sent (the caller must ask first, F1b). */
class SimklRemovalNeedsConfirmException(val listing: SimklListing) : SimklException("Simkl removal would clear watch history or a rating")

/** More than one Simkl entry matches the title's ids (anime seasons sharing a show's id): nothing was removed. */
class SimklDuplicateEntriesException : SimklException("Simkl lists the title more than once")

/**
 * One title at Simkl as the detail page's list picker shows it (F1/F2): its [list] (null = on none) and what a removal
 * would wipe with it: [watched] episodes (a film: 1 once watched) and the user's [rating] (1-10). [duplicated] = more
 * than one Simkl entry matches, so a removal is refused.
 */
data class SimklListing(
    val list: TrackingListStatus?,
    val watched: Int = 0,
    val rating: Int? = null,
    val duplicated: Boolean = false
) {
    /** Removing it loses something (any list but Plan to watch, a watch, a rating): the picker asks first (F1b). */
    val removalNeedsConfirm: Boolean
        get() = list != null && (list != TrackingListStatus.PLAN_TO_WATCH || watched > 0 || rating != null)

    /** Consent: removing it now loses exactly what the user [agreed] to in the confirm (same list, watches, rating). */
    fun losesOnly(agreed: SimklListing?): Boolean =
        agreed != null && agreed.list == list && agreed.watched == watched && agreed.rating == rating
}

/** Simkl does not know the title (no id it matches, or the write came back in `not_found`). */
class SimklUnmatchableException : SimklException("Simkl could not match the title")

/** A title as the Library names it: its ids (from the content id) and whether it is a film. */
internal data class SimklLibraryTarget(val ids: TrackingExternalIds, val isMovie: Boolean) {
    companion object {
        fun of(itemId: String, itemType: String) =
            SimklLibraryTarget(parseTrackingExternalIds(itemId), itemType.trim().lowercase() in MOVIE_TYPES)
    }

    fun matches(ids: TrackingExternalIds, isMovie: Boolean): Boolean = this.isMovie == isMovie && this.ids.sharesAnyId(ids)
}

/** A change made on this TV: [listed] = added (as the Library shows it), null = removed. */
internal data class SimklLibraryChange(val target: SimklLibraryTarget, val listed: LibraryEntry?, val atEpochMs: Long)

/** One synced entry as the Library shows it. */
internal data class SimklLibraryRow(val entry: SimklEntry, val listed: LibraryEntry, val ids: TrackingExternalIds)

/** The snapshot's rows with this TV's newer changes on top. */
internal class SimklLibraryView(private val rows: List<SimklLibraryRow>, private val changes: List<SimklLibraryChange>) {
    /** The latest change for [target], if any. */
    private fun changeFor(target: SimklLibraryTarget): SimklLibraryChange? =
        changes.lastOrNull { target.matches(it.target.ids, it.target.isMovie) }

    fun isListed(target: SimklLibraryTarget): Boolean =
        changeFor(target)?.let { it.listed != null } ?: (syncedRow(target) != null)

    /** [target]'s list as shown (this TV's latest change first), with what the synced entries say a removal would wipe. */
    fun listing(target: SimklLibraryTarget): SimklListing {
        val matching = rows.filter { target.matches(it.ids, it.entry.isMovie) }
        val change = changeFor(target)
        val list = if (change != null) change.listed?.let { it.trackerList ?: TrackingListStatus.PLAN_TO_WATCH }
        else matching.firstOrNull()?.listed?.trackerList
        if (list == null) return SimklListing(null)
        val synced = matching.firstOrNull()?.entry?.listing() ?: SimklListing(list)
        return synced.copy(list = list, duplicated = matching.size > 1)
    }

    private fun syncedRow(target: SimklLibraryTarget): SimklLibraryRow? = rows.firstOrNull { target.matches(it.ids, it.entry.isMovie) }

    /** Newest first, one per (type, id). */
    fun entries(): List<LibraryEntry> {
        val latest = mutableListOf<SimklLibraryChange>()
        changes.asReversed().forEach { change ->
            if (latest.none { change.target.matches(it.target.ids, it.target.isMovie) }) latest += change
        }
        val synced = rows.filter { row -> latest.none { it.target.matches(row.ids, row.entry.isMovie) } }.map { it.listed }
        return (synced + latest.mapNotNull { it.listed })
            .sortedByDescending(LibraryEntry::listedAt)
            .distinctBy { it.type to it.id }
    }
}

/**
 * Every titled, listed entry of [snapshot] as the Library shows it (G1 lists all five statuses, each row naming its list),
 * anime named by [anime] (S5; one per show folds a show's seasons into one row, which keeps the newest entry's list).
 * Writes decide by ids, never by the row's id.
 */
internal fun simklLibraryRows(
    snapshot: SimklSnapshot?,
    anime: SimklAnimeSeasons = SimklAnimeSeasons.DEFAULT
): List<SimklLibraryRow> = snapshot?.entries.orEmpty().mapNotNull { entry ->
    val media = entry.media ?: return@mapNotNull null
    if (entry.status == null) return@mapNotNull null
    val contentId = media.contentId(entry.animeMode(anime)) ?: return@mapNotNull null
    val listed = LibraryEntry(
        id = contentId,
        type = if (entry.isMovie) "movie" else "series",
        name = media.title?.trim()?.takeIf(String::isNotEmpty) ?: contentId,
        poster = simklPosterUrl(media.poster),
        background = null,
        logo = null,
        description = null,
        releaseInfo = media.year?.toString(),
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listedAt = parseSimklTime(entry.addedToWatchlistAt) ?: parseSimklTime(entry.lastWatchedAt) ?: 0L,
        trackerList = entry.status.libraryList
    )
    SimklLibraryRow(entry, listed, media.externalIds())
}

/**
 * The entry as the list picker shows it ([SimklListing]; its [SimklListing.removalNeedsConfirm] is upstream NuvioTV's
 * `destructiveRemovalImpacts` rule: a plan-to-watch title can carry progress). Watched = the most of Simkl's count and
 * the watched episodes it lists, at least 1 with a last watch (a film counts once); the rating rounded to a whole 1-10.
 */
internal fun SimklEntry.listing(): SimklListing {
    val counted = maxOf(watchedEpisodesCount ?: 0, seasons.sumOf { season -> season.episodes.count { it.watchedAt != null } })
    val watched = if (counted == 0 && lastWatchedAt != null) 1 else counted
    return SimklListing(status?.libraryList, if (isMovie) minOf(watched, 1) else watched, userRating?.roundToInt())
}

/** A Simkl poster, `_m` (340 px wide portrait). Null for a missing or odd path. */
internal fun simklPosterUrl(path: String?): String? = simklImageUrl("posters", path, "_m")

/**
 * Simkl's advice (conventions/images): its images through the wsrv.nl proxy at `q=90`. [category] `posters` or `fanart`,
 * [size] a `.webp` suffix (`_m`, `_w`). Null for a missing or odd path.
 */
internal fun simklImageUrl(category: String, path: String?, size: String): String? {
    val clean = path?.trim()?.takeIf { SIMKL_IMAGE_PATH.matches(it) } ?: return null
    return "https://wsrv.nl/?url=https://simkl.in/$category/$clean$size.webp&q=90"
}

internal fun SimklMedia.externalIds(): TrackingExternalIds = TrackingExternalIds(
    imdb = id("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) },
    tmdb = id("tmdb")?.toLongOrNull(),
    tvdb = id("tvdb"),
    simkl = simklId,
    mal = id("mal")?.toLongOrNull(),
    anidb = id("anidb")?.toLongOrNull(),
    anilist = id("anilist")?.toLongOrNull(),
    kitsu = id("kitsu")?.toLongOrNull()
)

/** The ids a Library transfer matches by (`tt...`, `tmdb:N`). */
private fun TrackingExternalIds.transferIds(): Set<String> = setOfNotNull(imdb, tmdb?.let { "tmdb:$it" })

/**
 * `/sync/add-to-list` onto [to] (Plan to watch by default): films under `movies`, shows under `shows`, anime (an anime
 * id) under `anime`; `to` on each item (a top-level one alone is refused, `400 empty_field`).
 */
internal fun addToListRequest(
    items: List<LibraryEntryInput>,
    to: TrackingListStatus = TrackingListStatus.PLAN_TO_WATCH
): SimklRequest {
    val references = items.map { buildTrackingMediaReference(it.itemType, it.itemId, title = it.title, releaseInfo = it.releaseInfo) }
    val body = buildJsonObject {
        TrackingMediaKind.entries.forEach { kind ->
            val ofKind = references.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            putJsonArray(kind.simklKey()) {
                ofKind.forEach { reference ->
                    add(buildJsonObject {
                        reference.titleObject().forEach { (key, value) -> put(key, value) }
                        put("to", to.wireValue)
                    })
                }
            }
        }
    }
    return SimklRequest(SimklHttpMethod.POST, "/sync/add-to-list", jsonBody = body.toString())
}

/**
 * A whole-title `/sync/history/remove` (no seasons: off every list) by the synced [entry]'s own ids ([ownIds]).
 */
internal fun removeRequest(entry: SimklEntry): SimklRequest = entryRequest("/sync/history/remove", entry)

/** `/sync/add-to-list` moving the synced [entry] (by its own ids, [ownIds]) onto [to]. */
internal fun moveRequest(entry: SimklEntry, to: TrackingListStatus): SimklRequest =
    entryRequest("/sync/add-to-list", entry) { put("to", to.wireValue) }

private fun entryRequest(path: String, entry: SimklEntry, extra: JsonObjectBuilder.() -> Unit = {}): SimklRequest {
    val body = buildJsonObject {
        putJsonArray(entry.type.apiValue) { add(buildJsonObject { put("ids", entry.ownIds()); extra() }) }
    }
    return SimklRequest(SimklHttpMethod.POST, path, jsonBody = body.toString())
}

/**
 * The synced entry's own Simkl id (which "always wins" at Simkl), else its other ids, sent under the key Simkl filed it
 * under: never Nuvio's ids, and never a shared id Simkl could resolve to another entry when its own id is known.
 */
private fun SimklEntry.ownIds(): JsonObject {
    val media = checkNotNull(media)
    return media.simklId?.let { buildJsonObject { put("simkl", it) } } ?: JsonObject(media.ids)
}

private fun TrackingMediaKind.simklKey(): String = when (this) {
    TrackingMediaKind.MOVIE -> "movies"
    TrackingMediaKind.SHOW -> "shows"
    TrackingMediaKind.ANIME -> "anime"
}

/** The ids of every title in a write reply's `not_found` (each list in it); empty when none or unreadable. */
internal fun simklNotFoundIds(body: String): List<TrackingExternalIds> {
    val notFound = try {
        decode(SimklNotFoundReply.serializer(), body).notFound
    } catch (_: SimklException) {
        return emptyList()
    }
    return (notFound as? JsonObject)?.values.orEmpty().flatMap { list ->
        (list as? JsonArray).orEmpty().mapNotNull { item ->
            val ids = ((item as? JsonObject)?.get("ids") as? JsonObject) ?: return@mapNotNull null
            SimklMedia(ids = ids).externalIds()
        }
    }
}

@kotlinx.serialization.Serializable
private data class SimklNotFoundReply(@kotlinx.serialization.SerialName("not_found") val notFound: JsonElement? = null)

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
    listedAt = nowEpochMs,
    // An add lands on Plan to watch (it never moves a listed title).
    trackerList = TrackingListStatus.PLAN_TO_WATCH
)

private val SimklListStatus.libraryList: TrackingListStatus
    get() = when (this) {
        SimklListStatus.WATCHING -> TrackingListStatus.WATCHING
        SimklListStatus.PLAN_TO_WATCH -> TrackingListStatus.PLAN_TO_WATCH
        SimklListStatus.ON_HOLD -> TrackingListStatus.ON_HOLD
        SimklListStatus.COMPLETED -> TrackingListStatus.COMPLETED
        SimklListStatus.DROPPED -> TrackingListStatus.DROPPED
    }

private val MOVIE_TYPES = setOf("movie", "movies", "film")
private val SIMKL_IMAGE_PATH = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*")
