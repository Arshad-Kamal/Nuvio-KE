package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.isEpisodeVideoId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches MDBList's TVDB<->TMDb episode maps into [MdbListTvdbMapStore] and moves writes into MDBList's numbering
 * (Spec 105 S6, § S6 design N1-N5). Reads use the store through [MdbListSnapshot.inAddonNumbering] and
 * [MdbListTvdbMapsState.retainsLocalEpisode]; this class only decides when to fetch and how a write is numbered.
 *
 * Every fetch runs on [scope] (the app's) and is shared while in flight: one per TMDb show (`show:<id>`) and one per
 * IMDb alias lookup (`alias:<tt>`). A waiter that gives up (the router's 30 s bound) never cancels it for the others;
 * waiters get a result, never an exception. A show or alias is held (in memory) for [HOLD_MS] after a failed fetch,
 * or after a 404 that kept a differing map (D16): a fill does not fetch it then, and a write uses its stored entry; a
 * write with no stored entry still fetches.
 */
class MdbListEpisodeNumbering(
    private val session: MdbListSession,
    private val store: MdbListTvdbMapStore,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    /** How a fetch ended. [STOPPED] = MDBList refused for rate or quota: no more fetches this fill (D5). */
    internal enum class Fetch { DONE, FAILED, STOPPED }

    private val inFlight = HashMap<String, Deferred<Fetch>>()

    /** Show or alias key -> when it was held (see the class note). */
    private val heldAt = ConcurrentHashMap<String, Long>()
    private val fillLock = Mutex()

    /** Profiles with a fill queued and not yet started: a refresh meanwhile adds none (the queued one reads the newest snapshot). */
    private val fillQueued: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Per profile and IMDb id: the last fill's rows of that show, to tell a refresh that changed them (N3). */
    private val seenRows = ConcurrentHashMap<Pair<Int, String>, Int>()

    /** The stored maps as they change (reads translate the snapshot through them, N4). */
    val maps: Flow<MdbListTvdbMapsState> get() = store.maps

    /**
     * N4b for the provider's hook: the maps as loaded (empty before the first load: every show unknown, so hidden).
     * Merges re-run on every change of [maps] (the provider's `localRetentionChanges`), so a decision never goes stale.
     */
    fun retainsLocalEpisode(contentId: String, season: Int?, episode: Int?, lastWatched: Long): Boolean =
        store.loaded.retainsLocalEpisode(contentId, season, episode, lastWatched)

    /**
     * [reference] in MDBList's numbering for [profileId]'s write (N5), or null when it must not be sent: an episode id
     * MDBList does not number ([mdbListEpisodeOrigin]), a `tt` show whose TMDb id or map could not be had, or an
     * episode the map does not link. Films, whole series and `tmdb:` episodes pass unchanged. [itemTime] is the time
     * the write records (a mark's date, a play's now); null for a removal. The caller runs [MdbListNumbered.onSent]
     * once MDBList accepted the write.
     */
    suspend fun toMdbList(profileId: Int, reference: TrackingMediaReference, itemTime: Long?): MdbListNumbered? {
        if (reference.kind == TrackingMediaKind.MOVIE) return MdbListNumbered(reference)
        val episode = reference.episode ?: return MdbListNumbered(reference)
        val season = episode.season ?: return MdbListNumbered(reference)
        val imdb = when (val origin = mdbListEpisodeOrigin(reference)) {
            MdbListEpisodeOrigin.Tmdb -> return MdbListNumbered(reference)
            MdbListEpisodeOrigin.None -> return null
            is MdbListEpisodeOrigin.Tvdb -> origin.imdb
        }
        val tmdb = store.current().aliases[imdb]
            ?: run {
                fetchAlias(profileId, imdb).await()
                store.current().aliases[imdb]
            }
            ?: return null
        var show = store.current().shows[tmdb]
        if (writeNeedsFetch(show, tmdb, season, episode.number)) {
            fetchShow(profileId, tmdb).await()
            // A failed fetch leaves the stored entry, used as reads use it.
            show = store.current().shows[tmdb]
        }
        show ?: return null
        if (!show.differs) return MdbListNumbered(reference.copy(ids = reference.ids.copy(tmdb = tmdb)))
        val link = show.byTvdb[episodeKey(season, episode.number)] ?: return null
        val numbered = reference.copy(
            ids = reference.ids.copy(tmdb = tmdb, tvdb = null),
            episode = episode.copy(season = link.tmdbSeason, number = link.tmdbEpisode, tvdbId = null)
        )
        if (itemTime == null || link.linkedAt <= itemTime) return MdbListNumbered(numbered)
        // Once sent, MDBList holds this episode as of the write: a local entry from then on is not kept beside it (round-4 m7).
        return MdbListNumbered(numbered) { store.update { it.withLinkSentAt(tmdb, season, episode.number, itemTime) } }
    }

    /**
     * The MDBList episode a Continue Watching removal of [contentId]'s addon episode [season]x[episode] clears (D14),
     * or null when nothing may be sent. A removal carries no video id, so a `tt` title is read as N1's `tt` case.
     */
    suspend fun removalEpisode(profileId: Int, contentId: String, season: Int, episode: Int): Pair<Int, Int>? {
        val reference = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            ids = parseTrackingExternalIds(contentId),
            episode = TrackingEpisode(season = season, number = episode),
            catalog = TrackingCatalogReference(contentId, "series")
        )
        val numbered = toMdbList(profileId, reference, itemTime = null)?.reference?.episode ?: return null
        return (numbered.season ?: return null) to numbered.number
    }

    /**
     * Runs [fill] on the app scope after a refresh, one fill at a time, on the snapshot as stored when it starts; at
     * most one waits per profile.
     */
    fun launchFill(profileId: Int, snapshot: suspend () -> MdbListSnapshot?) {
        if (!fillQueued.add(profileId)) return
        scope.launch {
            fillLock.withLock {
                fillQueued.remove(profileId)
                snapshot()?.let { fill(profileId, it) }
            }
        }
    }

    /**
     * N3: brings the maps of [snapshot]'s `tt` shows up to date, newest activity first (shows with playbacks, then by
     * their latest mark), at most [MAX_FILL_FETCHES] requests; stops on a rate or quota refusal. Per show: its alias
     * (seeded from the rows when they all name one TMDb id, replaced when they all name another, else looked up), then
     * a map fetch when it has none, after an alias change, when it is over a day old and this refresh changed the
     * show's rows, or (differing maps) when a row newer than the map names a TMDb episode the map does not, an hour on.
     */
    internal suspend fun fill(profileId: Int, snapshot: MdbListSnapshot) {
        var budget = MAX_FILL_FETCHES
        for (rows in fillShows(snapshot)) {
            val seenKey = profileId to rows.imdb
            val fingerprint = rows.fingerprint()
            val previous = seenRows[seenKey]
            val changed = previous != null && previous != fingerprint

            // The alias: rows naming one TMDb id seed or replace it; else the stored one; else a lookup.
            val alias = store.current().aliases[rows.imdb]
            val fromRows = rows.singleTmdb()
            val replaced = fromRows != null && alias != null && fromRows != alias
            if (fromRows != null && fromRows != alias) store.update { it.copy(aliases = it.aliases + (rows.imdb to fromRows)) }
            val tmdb = fromRows ?: alias ?: run {
                if (held(aliasKey(rows.imdb))) return@run null
                if (budget-- <= 0) return
                if (fetchAlias(profileId, rows.imdb).await() == Fetch.STOPPED) return
                store.current().aliases[rows.imdb]
            }
            // A show passed over unfetched (held, no alias, failed) keeps its old rows on record: its change still counts.
            if (tmdb == null) continue

            val show = store.current().shows[tmdb]
            val now = nowEpochMs()
            val wanted = show == null || replaced ||
                (changed && now - show.fetchedAt >= MDBLIST_MAP_FRESH_MS) ||
                (show.differs && now - show.fetchedAt >= UNKNOWN_EPISODE_REFETCH_MS && rows.hasUnnamedSince(show, tmdb))
            if (!wanted) {
                seenRows[seenKey] = fingerprint
                continue
            }
            if (held(showKey(tmdb))) continue
            if (budget-- <= 0) return
            when (fetchShow(profileId, tmdb).await()) {
                Fetch.STOPPED -> return
                Fetch.DONE -> seenRows[seenKey] = fingerprint
                // Failed: the change stays unrecorded, so the fill after the hold asks again.
                Fetch.FAILED -> Unit
            }
        }
    }

    /** N5: a write's map is fetched first when missing, over a day old, or (differing) silent on its episode an hour on. */
    private fun writeNeedsFetch(show: MdbListShowNumbering?, tmdb: Long, season: Int, episode: Int): Boolean {
        if (show == null) return true
        val age = nowEpochMs() - show.fetchedAt
        val stale = age >= MDBLIST_MAP_FRESH_MS || (age >= UNKNOWN_EPISODE_REFETCH_MS && !show.knowsTvdb(season, episode))
        // While held, the stored entry answers (a fetch then would most likely end the same way).
        return stale && !held(showKey(tmdb))
    }

    private fun fetchShow(profileId: Int, tmdb: Long): Deferred<Fetch> = shared(showKey(tmdb)) {
        when (val reply = get(profileId, "/shows/$tmdb/tvdb-map/")) {
            is Reply.Ok -> {
                val map = decodeOrNull(reply, MdbListTvdbMapReply.serializer()) ?: return@shared failed(showKey(tmdb))
                val now = nowEpochMs()
                store.update { it.withShow(tmdb, map.toNumbering(it.shows[tmdb], now)) }
                heldAt.remove(showKey(tmdb))
                Fetch.DONE
            }
            Reply.NotFound -> {
                val now = nowEpochMs()
                store.update { it.withShow(tmdb, it.shows[tmdb].after404(now)) }
                // A differing map kept through its first 404 is not asked again for an hour (D16).
                if (store.current().shows[tmdb]?.differs == true) heldAt[showKey(tmdb)] = now else heldAt.remove(showKey(tmdb))
                Fetch.DONE
            }
            Reply.Failed -> failed(showKey(tmdb))
            Reply.Stopped -> Fetch.STOPPED
        }
    }

    private fun fetchAlias(profileId: Int, imdb: String): Deferred<Fetch> = shared(aliasKey(imdb)) {
        if (!MDBLIST_IMDB_ID.matches(imdb)) return@shared Fetch.FAILED
        when (val reply = get(profileId, "/imdb/show/$imdb/")) {
            is Reply.Ok -> {
                val tmdb = decodeOrNull(reply, MdbListMediaInfo.serializer())?.let { MdbListMedia(ids = it.ids).tmdb }
                    ?: return@shared failed(aliasKey(imdb))
                store.update { it.copy(aliases = it.aliases + (imdb to tmdb)) }
                heldAt.remove(aliasKey(imdb))
                Fetch.DONE
            }
            Reply.NotFound, Reply.Failed -> failed(aliasKey(imdb))
            Reply.Stopped -> Fetch.STOPPED
        }
    }

    /** The fetch running under [key], or [block] started for it; removed once it ends. */
    private fun shared(key: String, block: suspend () -> Fetch): Deferred<Fetch> = synchronized(inFlight) {
        inFlight[key]?.let { return it }
        // Lazy, so it is registered before it can finish and unregister itself.
        val fetch = scope.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
        inFlight[key] = fetch
        fetch.start()
        fetch
    }

    private fun failed(key: String): Fetch {
        heldAt[key] = nowEpochMs()
        return Fetch.FAILED
    }

    private fun held(key: String): Boolean = heldAt[key]?.let { nowEpochMs() - it < HOLD_MS } == true

    private sealed interface Reply {
        class Ok(val body: String) : Reply
        data object NotFound : Reply
        data object Failed : Reply
        data object Stopped : Reply
    }

    private suspend fun get(profileId: Int, path: String): Reply = try {
        Reply.Ok(session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, path)).body)
    } catch (_: MdbListQuotaExceededException) {
        Reply.Stopped
    } catch (_: MdbListSignedOutException) {
        // No key (disconnected, or MDBList refused it): nothing to learn about any show now.
        Reply.Stopped
    } catch (failure: MdbListApiException) {
        when (failure.status) {
            404 -> Reply.NotFound
            429 -> Reply.Stopped
            else -> Reply.Failed
        }
    } catch (_: IOException) {
        // MdbListException and any other I/O failure (the account store's read included): waiters get a result.
        Reply.Failed
    }

    private fun <T> decodeOrNull(reply: Reply.Ok, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        decodeMdbList(serializer, reply.body)
    } catch (_: MdbListException) {
        null
    }

    private companion object {
        const val HOLD_MS = 60L * 60 * 1_000
        const val UNKNOWN_EPISODE_REFETCH_MS = 60L * 60 * 1_000
        const val MAX_FILL_FETCHES = 100
        fun showKey(tmdb: Long) = "show:$tmdb"
        fun aliasKey(imdb: String) = "alias:$imdb"
    }
}

/** An IMDb id fit for an MDBList request path (ids come from addon content: untrusted). */
internal val MDBLIST_IMDB_ID = Regex("tt\\d{1,12}")

/** N1: which numbering an episode reference is in. */
internal sealed interface MdbListEpisodeOrigin {
    /** A `tt` series: TVDB aired order (the Stremio `tt:s:e` convention), moved through [imdb]'s map. */
    data class Tvdb(val imdb: String) : MdbListEpisodeOrigin

    /** A `tmdb:` series: already MDBList's numbering. */
    data object Tmdb : MdbListEpisodeOrigin

    /** Any other episode id (`tvdb:`, `kitsu:`, `mal:`...): never sent (reads keep such titles local). */
    data object None : MdbListEpisodeOrigin
}

/**
 * N1 by id: an episode video id (`id:s:e`) names its numbering by its own prefix, even under a `tt` parent; a bare or
 * missing video id goes by the title's ids (IMDb, else TMDB).
 */
internal fun mdbListEpisodeOrigin(reference: TrackingMediaReference): MdbListEpisodeOrigin {
    val videoId = reference.catalog?.videoId?.takeIf(::isEpisodeVideoId)
    if (videoId != null) {
        val prefix = videoId.substringBefore(':')
        return when {
            prefix.startsWith("tt") -> MdbListEpisodeOrigin.Tvdb(prefix)
            prefix.equals("tmdb", ignoreCase = true) -> MdbListEpisodeOrigin.Tmdb
            else -> MdbListEpisodeOrigin.None
        }
    }
    val imdb = reference.ids.imdb?.takeIf { it.startsWith("tt") }
    return when {
        imdb != null -> MdbListEpisodeOrigin.Tvdb(imdb)
        reference.ids.tmdb != null -> MdbListEpisodeOrigin.Tmdb
        else -> MdbListEpisodeOrigin.None
    }
}

/** One `tt` show's episode rows in a snapshot, as the fill reads them. */
internal class MdbListShowRows(val imdb: String) {
    class Row(val tmdb: Long?, val season: Int, val episode: Int, val at: Long?)

    val rows = mutableListOf<Row>()
    var newestPlayback: Long? = null
    var newestMark: Long? = null
    val hasPlayback: Boolean get() = newestPlayback != null

    /** The one TMDb id every row names, or null (a row naming none, or several ids). */
    fun singleTmdb(): Long? = rows.map { it.tmdb ?: return null }.distinct().singleOrNull()

    fun fingerprint(): Int = rows.map { "${it.tmdb}|${it.season}|${it.episode}|${it.at}" }.sorted().hashCode()

    /** A row newer than [show]'s fetch naming a TMDb episode of [tmdb] the map does not name. */
    fun hasUnnamedSince(show: MdbListShowNumbering, tmdb: Long): Boolean = rows.any { row ->
        (row.tmdb == null || row.tmdb == tmdb) && (row.at ?: Long.MIN_VALUE) > show.fetchedAt &&
            episodeKey(row.season, row.episode) !in show.named
    }
}

/** [snapshot]'s `tt` shows with episode rows, newest activity first: shows with playbacks, then by latest mark. */
internal fun fillShows(snapshot: MdbListSnapshot): List<MdbListShowRows> {
    val shows = LinkedHashMap<String, MdbListShowRows>()
    snapshot.playback.forEach { playback ->
        if (!playback.isEpisode) return@forEach
        val media = playback.media ?: return@forEach
        val imdb = media.imdb ?: return@forEach
        val episode = playback.episode ?: return@forEach
        val season = episode.season ?: return@forEach
        val number = episode.number ?: return@forEach
        val at = parseMdbListTime(playback.pausedAt) ?: parseMdbListTime(playback.updatedAt)
        val show = shows.getOrPut(imdb) { MdbListShowRows(imdb) }
        show.rows += MdbListShowRows.Row(media.tmdb, season, number, at)
        show.newestPlayback = maxOf(show.newestPlayback ?: Long.MIN_VALUE, at ?: Long.MIN_VALUE)
    }
    snapshot.marks.forEach { mark ->
        if (!mark.isEpisode) return@forEach
        val imdb = mark.media.imdb ?: return@forEach
        val at = parseMdbListTime(mark.watchedAt)
        val show = shows.getOrPut(imdb) { MdbListShowRows(imdb) }
        show.rows += MdbListShowRows.Row(mark.media.tmdb, mark.season!!, mark.episode!!, at)
        show.newestMark = maxOf(show.newestMark ?: Long.MIN_VALUE, at ?: Long.MIN_VALUE)
    }
    return shows.values.sortedWith(
        compareByDescending<MdbListShowRows> { it.hasPlayback }
            .thenByDescending { it.newestPlayback ?: Long.MIN_VALUE }
            .thenByDescending { it.newestMark ?: Long.MIN_VALUE }
    )
}
