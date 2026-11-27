package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/*
 * Spec 105 S6 (§ S6 design, N1-N5): MDBList keeps episodes in TMDb numbering; a `tt` series reaches Nuvio in TVDB aired
 * order (the Stremio `tt:s:e` convention). MDBList's own `GET /shows/{tmdb}/tvdb-map/` says how the two line up. This
 * file holds that map per show, the IMDb -> TMDb show aliases, and the pure rules reads and writes both apply to them;
 * [MdbListEpisodeNumbering] fetches. Nothing here depends on which addon is installed or on what a screen asked for.
 */

/** A TVDB episode linked one-to-one to a TMDb episode; [linkedAt] = when a fetch first held the link (device clock). */
@Serializable
data class MdbListEpisodeLink(
    val tvdbSeason: Int,
    val tvdbEpisode: Int,
    val tmdbSeason: Int,
    val tmdbEpisode: Int,
    val linkedAt: Long
)

/**
 * One show's numbering. [differs] false = TVDB matches TMDb (MDBList answered 404): numbers pass unchanged. Otherwise
 * [links] are the one-to-one links (a TVDB episode spanning several TMDb ones, sitting in another TMDb show, or sharing
 * its TMDb episode with another counts as unlinked), [named] every TMDb episode the raw map names (`"s:e"`),
 * [unlinkedTvdb] the TVDB episodes MDBList cannot hold (`tvdb_only` plus the discarded links). [firstFetchedAt] is the
 * entry's first 200; [first404At] the first 404 since the last 200 (two a day apart turn the entry [differs] false).
 */
@Serializable
data class MdbListShowNumbering(
    val differs: Boolean,
    val fetchedAt: Long,
    val firstFetchedAt: Long = fetchedAt,
    val first404At: Long? = null,
    val links: List<MdbListEpisodeLink> = emptyList(),
    val named: Set<String> = emptySet(),
    val unlinkedTvdb: Set<String> = emptySet()
) {
    val byTvdb: Map<String, MdbListEpisodeLink> by lazy { links.associateBy { episodeKey(it.tvdbSeason, it.tvdbEpisode) } }
    val byTmdb: Map<String, MdbListEpisodeLink> by lazy { links.associateBy { episodeKey(it.tmdbSeason, it.tmdbEpisode) } }

    /** The TMDb episode MDBList keeps for addon (TVDB) episode [season]x[episode]; null = it has none. */
    fun tmdbEpisode(season: Int, episode: Int): Pair<Int, Int>? =
        if (!differs) season to episode else byTvdb[episodeKey(season, episode)]?.let { it.tmdbSeason to it.tmdbEpisode }

    /** True when this map knows the addon episode either way (linked, or known to have no counterpart). */
    fun knowsTvdb(season: Int, episode: Int): Boolean =
        !differs || episodeKey(season, episode).let { it in byTvdb || it in unlinkedTvdb }
}

/** Every show map and alias this TV holds (the same for every MDBList user, so app-wide). */
@Serializable
data class MdbListTvdbMapsState(
    /** IMDb id -> the TMDb show id MDBList keeps it under (the one home for it). */
    val aliases: Map<String, Long> = emptyMap(),
    /** TMDb show id -> its numbering. */
    val shows: Map<Long, MdbListShowNumbering> = emptyMap()
) {
    /** The numbering of the `tt` show [imdb], or null while unknown. */
    fun showFor(imdb: String): MdbListShowNumbering? = aliases[imdb]?.let(shows::get)
}

internal fun episodeKey(season: Int, episode: Int) = "$season:$episode"

/**
 * Where an MDBList episode row of [media] sits in the addon's numbering (N4); null = not shown: a `tt` show still
 * unknown, a row naming another TMDb show than the alias, or a TMDb episode with no one-to-one TVDB link. A title with
 * no IMDb id is named `tmdb:` and keeps MDBList's numbers.
 */
internal fun MdbListTvdbMapsState.addonEpisode(media: MdbListMedia, season: Int, episode: Int): Pair<Int, Int>? {
    val imdb = media.imdb ?: return season to episode
    val tmdb = aliases[imdb] ?: return null
    if (media.tmdb != null && media.tmdb != tmdb) return null
    val show = shows[tmdb] ?: return null
    if (!show.differs) return season to episode
    return show.byTmdb[episodeKey(season, episode)]?.let { it.tvdbSeason to it.tvdbEpisode }
}

/** [this] snapshot with every episode row moved into the addon's numbering, rows that cannot be shown left out (N4). */
internal fun MdbListSnapshot.inAddonNumbering(maps: MdbListTvdbMapsState): MdbListSnapshot = copy(
    marks = marks.mapNotNull { mark ->
        if (!mark.isEpisode) return@mapNotNull mark
        val (season, episode) = maps.addonEpisode(mark.media, mark.season!!, mark.episode!!) ?: return@mapNotNull null
        mark.copy(season = season, episode = episode)
    },
    playback = playback.mapNotNull { playback ->
        if (!playback.isEpisode) return@mapNotNull playback
        val media = playback.media ?: return@mapNotNull null
        val shown = playback.episode ?: return@mapNotNull null
        val (season, episode) = maps.addonEpisode(media, shown.season ?: return@mapNotNull null, shown.number ?: return@mapNotNull null)
            ?: return@mapNotNull null
        playback.copy(episode = shown.copy(season = season, number = episode))
    }
)

/**
 * N4b: true when MDBList could not have received this local entry, so it stays visible: a title MDBList cannot name,
 * or an episode of a show whose numbering differs that has no link, or was played before its link appeared (and not
 * before the show's first fetch, so history from before connecting stays hidden as for any title).
 */
internal fun MdbListTvdbMapsState.retainsLocalEpisode(contentId: String, season: Int?, episode: Int?, lastWatched: Long): Boolean {
    if (isMdbListUnmatchable(contentId)) return true
    if (season == null || episode == null) return false
    val imdb = parseTrackingExternalIds(contentId).imdb ?: return false
    val show = showFor(imdb)?.takeIf { it.differs } ?: return false
    val link = show.byTvdb[episodeKey(season, episode)] ?: return true
    return lastWatched < link.linkedAt && lastWatched >= show.firstFetchedAt
}

/**
 * What [retainsLocalEpisode] reads besides the content id: each `tt` show whose numbering differs, by IMDb id. Equal
 * views give equal answers, so merges re-run only when this changes (a matching or unaliased map changes no answer).
 */
internal fun MdbListTvdbMapsState.retentionView(): Map<String, MdbListShowNumbering> =
    aliases.mapNotNull { (imdb, tmdb) -> shows[tmdb]?.takeIf { it.differs }?.let { imdb to it } }.toMap()

internal fun MdbListTvdbMapsState.withShow(tmdb: Long, show: MdbListShowNumbering): MdbListTvdbMapsState =
    copy(shows = shows + (tmdb to show))

/**
 * A write just sent episode [season]x[episode] (addon numbering) of [tmdb] through its link: MDBList now holds that
 * episode as of [sentAt], so a local entry from then on is not kept twice (round-4 m7).
 */
internal fun MdbListTvdbMapsState.withLinkSentAt(tmdb: Long, season: Int, episode: Int, sentAt: Long): MdbListTvdbMapsState {
    val show = shows[tmdb]?.takeIf { it.differs } ?: return this
    val key = episodeKey(season, episode)
    val link = show.byTvdb[key]?.takeIf { it.linkedAt > sentAt } ?: return this
    return withShow(tmdb, show.copy(links = show.links.map { if (it === link) it.copy(linkedAt = sentAt) else it }))
}

/** A 200 from `tvdb-map`, as stored; links kept from [old] keep their [MdbListEpisodeLink.linkedAt]. */
internal fun MdbListTvdbMapReply.toNumbering(old: MdbListShowNumbering?, now: Long): MdbListShowNumbering {
    val valid = episodes.orEmpty().filter {
        (it.season ?: -1) >= 0 && (it.episode ?: 0) > 0 && (it.tmdbSeason ?: -1) >= 0 && (it.tmdbEpisode ?: 0) > 0
    }
    val ownShow = valid.filter { it.showTmdb == null }
    // How many TVDB episodes name each TMDb episode (a span names each episode it covers): more than one = merged.
    val tmdbUse = ownShow.flatMap(MdbListTvdbMapEpisode::tmdbKeys).groupingBy { it }.eachCount()
    // A TVDB episode listed twice (pointing at two TMDb episodes) is not one-to-one either.
    val tvdbUse = valid.groupingBy { episodeKey(it.season!!, it.episode!!) }.eachCount()
    val oneToOne = ownShow.filter {
        (it.spans ?: 1) <= 1 && tmdbUse[episodeKey(it.tmdbSeason!!, it.tmdbEpisode!!)] == 1 &&
            tvdbUse[episodeKey(it.season!!, it.episode!!)] == 1
    }
    val oldLinks = old?.takeIf { it.differs }?.byTvdb.orEmpty()
    val links = oneToOne.map { row ->
        val key = episodeKey(row.season!!, row.episode!!)
        val kept = oldLinks[key]?.takeIf { it.tmdbSeason == row.tmdbSeason && it.tmdbEpisode == row.tmdbEpisode }
        MdbListEpisodeLink(row.season, row.episode, row.tmdbSeason!!, row.tmdbEpisode!!, kept?.linkedAt ?: now)
    }
    val named = tmdbUse.keys.toSet()
    val linkedTvdb = links.mapTo(HashSet()) { episodeKey(it.tvdbSeason, it.tvdbEpisode) }
    val unlinked = valid.map { episodeKey(it.season!!, it.episode!!) }.filterNot(linkedTvdb::contains) +
        tvdbOnly.orEmpty().mapNotNull { only ->
            val season = only.season?.takeIf { it >= 0 } ?: return@mapNotNull null
            val episode = only.episode?.takeIf { it > 0 } ?: return@mapNotNull null
            episodeKey(season, episode)
        }
    return MdbListShowNumbering(
        differs = true,
        fetchedAt = now,
        firstFetchedAt = old?.takeIf { it.differs }?.firstFetchedAt ?: now,
        links = links,
        named = named,
        unlinkedTvdb = unlinked.toSet()
    )
}

/**
 * A 404 ("not found or matches TMDb"): a show not known to differ is stored as matching. A stored differing map is kept
 * on the first 404 and turns matching only on a second one at least [MDBLIST_MAP_FRESH_MS] after it (D16): one 404 may
 * be a show MDBList briefly lost, two a day apart a real TMDb restructure.
 */
internal fun MdbListShowNumbering?.after404(now: Long): MdbListShowNumbering {
    if (this == null || !differs) return MdbListShowNumbering(differs = false, fetchedAt = now)
    val first = first404At ?: return copy(first404At = now)
    return if (now - first >= MDBLIST_MAP_FRESH_MS) MdbListShowNumbering(differs = false, fetchedAt = now) else this
}

/** How long a stored map counts as current (N3, N5). */
internal const val MDBLIST_MAP_FRESH_MS = 24L * 60 * 60 * 1_000

/** `GET /shows/{tmdb}/tvdb-map/` (schema read 2026-11-16; replies checked live 2026-11-20). */
@Serializable
internal data class MdbListTvdbMapReply(
    @SerialName("order_kind") val orderKind: String? = null,
    val episodes: List<MdbListTvdbMapEpisode>? = null,
    @SerialName("tvdb_only") val tvdbOnly: List<MdbListTvdbOnlyEpisode>? = null
)

@Serializable
internal data class MdbListTvdbMapEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("tmdb_season") val tmdbSeason: Int? = null,
    @SerialName("tmdb_episode") val tmdbEpisode: Int? = null,
    @SerialName("show_tmdb") val showTmdb: Long? = null,
    val spans: Int? = null
) {
    /** The TMDb episodes this row names (a span counted from its first). */
    fun tmdbKeys(): List<String> =
        (0 until (spans ?: 1).coerceIn(1, MAX_SPAN)).map { episodeKey(tmdbSeason!!, tmdbEpisode!! + it) }

    private companion object {
        /** A reply's span is MDBList's data: bounded so a wrong value cannot allocate without end. */
        const val MAX_SPAN = 50
    }
}

@Serializable
internal data class MdbListTvdbOnlyEpisode(val season: Int? = null, val episode: Int? = null)

/** `GET /imdb/show/{tt}/` (media info): only its ids are read. */
@Serializable
internal data class MdbListMediaInfo(val ids: Map<String, JsonElement> = emptyMap())

/**
 * [MdbListTvdbMapsState] on disk (one JSON file; a large anime map is ~100 KB) plus the copy every read uses. Each
 * change is applied in memory at once (readers and writers see it immediately); the file is written at most once per
 * [SAVE_DELAY_MS], with the newest state, so a fill's burst of fetches writes it a few times, not once per fetch (an
 * app killed in between fetches those maps again). A temp file then rename keeps the previous file whole if the app
 * dies mid-write; an unreadable file is an empty store (maps are fetched again).
 */
class MdbListTvdbMapStore(
    private val file: File,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope
) {
    private val loadLock = Mutex()
    private val state = MutableStateFlow<MdbListTvdbMapsState?>(null)
    private val saves = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in saves) {
                delay(SAVE_DELAY_MS)
                state.value?.let { withContext(io) { write(it) } }
            }
        }
    }

    /** The maps as they change; the first value is the stored one, read from disk once. */
    val maps: Flow<MdbListTvdbMapsState> = flow {
        load()
        emitAll(state.filterNotNull())
    }

    suspend fun current(): MdbListTvdbMapsState {
        load()
        return state.value ?: MdbListTvdbMapsState()
    }

    /** For synchronous checks; empty until the first load (every show unknown). */
    val loaded: MdbListTvdbMapsState get() = state.value ?: MdbListTvdbMapsState()

    suspend fun update(transform: (MdbListTvdbMapsState) -> MdbListTvdbMapsState) {
        load()
        state.update { transform(it ?: MdbListTvdbMapsState()) }
        saves.trySend(Unit)
    }

    private suspend fun load() {
        if (state.value != null) return
        loadLock.withLock {
            if (state.value != null) return@withLock
            val stored = withContext(io) { read() } ?: MdbListTvdbMapsState()
            state.update { it ?: stored }
        }
    }

    private fun read(): MdbListTvdbMapsState? {
        if (!file.isFile) return null
        return try {
            mdbListJson.decodeFromString(MdbListTvdbMapsState.serializer(), file.readText())
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun write(maps: MdbListTvdbMapsState) {
        val text = try {
            mdbListJson.encodeToString(MdbListTvdbMapsState.serializer(), maps)
        } catch (_: OutOfMemoryError) {
            return
        }
        try {
            val directory = file.parentFile
            if (directory != null && !directory.isDirectory && !directory.mkdirs()) return
            val temp = File(file.path + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: IOException) {
            // The copy in memory stays right; the next save tries again.
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 10_000L
    }
}
