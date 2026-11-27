package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.isEpisodeVideoId
import com.nuvio.tv.core.tracking.sharesAnyId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.round
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Simkl's scrobble (Spec 106 S2b; api.simkl.org guides/scrobble): one call per player event, never on a timer. Seeks
 * send nothing (the next event carries the position), so the policy stays [NONE][com.nuvio.tv.core.tracking
 * .TrackingSeekScrobblePolicy.NONE], and a pause saves a playback, so leaving while paused needs no stop. `stop` at 80 %
 * or more marks the title watched, below saves a playback; `409` on stop = already watched within the hour (success).
 * Failures are not retried: the next event covers them. While the active profile's daily allowance is spent, start and
 * pause are dropped and a watching stop becomes a pending mark ([SimklPendingMarks]), sent after the reset. A watching
 * stop is a history write too, so it goes through [SimklHistoryWriter]'s order (a kept unmark never undoes it).
 */
class SimklScrobbler(
    private val session: SimklSession,
    private val writer: SimklHistoryWriter,
    private val activeProfileId: StateFlow<Int>
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.SIMKL

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val profileId = activeProfileId.value
        val request = SimklRequest(SimklHttpMethod.POST, "/scrobble/${action.wireValue}", jsonBody = simklScrobbleBody(event))
        val send: suspend () -> Unit = {
            try {
                session.call(profileId, request)
            } catch (failure: SimklApiException) {
                if (action != TrackingScrobbleAction.STOP || failure.status != 409) throw failure
            }
        }
        if (event.media.isEntryNumberedSpecial) return
        val marksWatched = action == TrackingScrobbleAction.STOP && event.progressPercent >= SIMKL_WATCHED_PERCENT &&
            !event.media.isWholeSeries
        if (marksWatched) {
            writer.watchedStop(profileId, event.media, send)
            return
        }
        try {
            send()
        } catch (_: SimklQuotaExceededException) {
            // Dropped: nothing to keep from a start, a pause or an unfinished stop.
        } catch (failure: SimklApiException) {
            if (!failure.isDailyLimit) throw failure
        }
    }
}

/**
 * Simkl's watched marks (Spec 106 S2b): one `/sync/history` (or `/sync/history/remove`) call per router write; the two
 * share Simkl's 20 s per-user write lock, so a `400 rate_limit` waits that out and tries once more. While the profile's
 * allowance is spent the write is kept as pending marks and sent after the reset ([SimklPendingMarks]); it then counts
 * as done. Titles Simkl cannot match come back in `not_found`.
 *
 * Writes run one at a time ([lock]): live ones, replayed ones and watching stops, so a replayed mark never overtakes
 * the user's later live word on the same title. A live write drops the kept marks of the opposite op on its keys
 * before it is sent and the same op's once it went (a failed send leaves an equal kept mark to go later). A replay
 * waits out the write lock with [lock] released, so a live write (bounded by the router's 30 s) never waits behind
 * that sleep.
 */
class SimklHistoryWriter(
    private val session: SimklSession,
    private val pending: SimklPendingMarks,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val logRefused: (Int) -> Unit = { Log.w(TAG, "Simkl refused $it pending marks; dropped") },
    private val logHeld: (Int) -> Unit = { Log.i(TAG, "$it Simkl anime unmarks held (no synced entry holds them)") },
    private val snapshotOf: suspend (profileId: Int) -> SimklSnapshot? = { null }
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.SIMKL

    private val lock = Mutex()

    /** Every send made under [lock] (marks, watching stops, replays, Library writes); read by [listWrite]'s callers. */
    private val sends = AtomicLong()

    /** The send count now: a Library write reads it before its sync and passes it to [listWrite]. */
    internal fun sendCount(): Long = sends.get()

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        if (items.isEmpty()) return TrackingMutationResult(0)
        return liveWrite(profileId, items.map { it.media }, SimklPendingOp.ADD, pend = { pendAdds(profileId, items) }) {
            write(profileId, SimklPendingOp.ADD, items)
        }
    }

    /**
     * An entry-numbered anime episode ([entryEpisode]) is removed only by the Simkl id of the one synced entry that
     * holds that watch ([resolveRemoval], D16); else it is held: never sent, never kept for later (the unmark stays on
     * this TV, Simkl keeps the mark). Either way it drops a kept mark of the same key, so nothing re-adds it. Kept
     * unmarks are kept as made and resolved again when replayed.
     */
    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        if (items.isEmpty()) return TrackingMutationResult(0)
        val resolved = items.map { it to resolveRemoval(profileId, it) }
        val sendable = resolved.filter { it.second != null }
        if (sendable.size < items.size) logHeld(items.size - sendable.size)
        if (sendable.isEmpty()) {
            // Nothing goes, so nothing counts as a send ([listWrite] callers need not decide again).
            lock.withLock { pending.supersede(profileId, items, onlyOp = SimklPendingOp.ADD) }
            return TrackingMutationResult(items.size)
        }
        return liveWrite(profileId, items, SimklPendingOp.REMOVE, pend = { pending.remove(profileId, sendable.map { it.first }) }) {
            write(profileId, SimklPendingOp.REMOVE, sendable.map { TrackingHistoryItem(checkNotNull(it.second)) })
        }
    }

    /**
     * [media] as removed: itself, or for an entry-numbered episode the holding entry's Simkl id; null = held. An episode
     * whose video id is unknown is held too when a synced anime series carries its title, or before the first snapshot
     * ([simklAnimeTitleHeld], D19): its mark may have gone flat to one cour's entry, and a show-wide (IMDb/TVDB seasons)
     * removal could wipe another cour's watch.
     */
    private suspend fun resolveRemoval(profileId: Int, media: TrackingMediaReference): TrackingMediaReference? {
        val entry = media.entryEpisode() ?: return media.takeUnless { simklAnimeTitleHeld(snapshotOf(profileId), it) }
        val simklId = simklEntryHolding(snapshotOf(profileId), entry) ?: return null
        return TrackingMediaReference(
            kind = TrackingMediaKind.ANIME, title = media.title, year = media.year, ids = TrackingExternalIds(simkl = simklId),
            episode = TrackingEpisode(season = null, number = entry.number)
        )
    }

    /** A scrobble stop that marks [media] watched ([SimklScrobbler]): ordered and kept like a mark. */
    internal suspend fun watchedStop(profileId: Int, media: TrackingMediaReference, send: suspend () -> Unit) {
        liveWrite(profileId, listOf(media), SimklPendingOp.ADD, pend = { pendAdds(profileId, listOf(TrackingHistoryItem(media))) }) {
            send()
            TrackingMutationResult(1)
        }
    }

    /**
     * Sends [profileId]'s pending marks, oldest first, one call per run of marks with the same op, Simkl's write lock
     * apart. The waits run with [lock] released; each batch is re-read after one. A `400 rate_limit` is sent once
     * more. A batch Simkl
     * refuses for good ([isRefusedForGood]) is dropped and logged; any other failure keeps it and is thrown.
     */
    suspend fun replayPending(profileId: Int) {
        var retried = false
        while (true) {
            val next = lock.withLock {
                val batch = pending.firstBatch(profileId)
                if (batch.isEmpty()) return
                val op = batch.first().op
                // A kept unmark is resolved now, against the snapshot as it is (held ones are dropped, never sent).
                val unsent = batch.map(SimklPendingMark::toHistoryItem).filterNot { it.media.isEntryNumberedSpecial }
                val items = unsent.mapNotNull { item ->
                    if (op != SimklPendingOp.REMOVE) item else resolveRemoval(profileId, item.media)?.let { item.copy(media = it) }
                }
                if (items.size < unsent.size) logHeld(unsent.size - items.size)
                if (items.isEmpty()) {
                    pending.drop(profileId, batch)
                    return@withLock if (pending.firstBatch(profileId).isEmpty()) return else ReplayStep.SKIPPED
                }
                try {
                    sends.incrementAndGet()
                    post(profileId, op, items)
                } catch (failure: SimklApiException) {
                    if (failure.isWriteLocked && !retried) return@withLock ReplayStep.LOCKED_OUT
                    if (!failure.isRefusedForGood) throw failure
                    logRefused(batch.size)
                }
                pending.drop(profileId, batch)
                if (pending.firstBatch(profileId).isEmpty()) return
                ReplayStep.SENT
            }
            // Either way Simkl's write lock now runs: the next batch would only meet it.
            if (next != ReplayStep.SKIPPED) sleep(WRITE_LOCK_MS)
            retried = next == ReplayStep.LOCKED_OUT
        }
    }

    private enum class ReplayStep { SENT, LOCKED_OUT, SKIPPED }

    private suspend fun pendAdds(profileId: Int, items: Collection<TrackingHistoryItem>) =
        // Sent later: the time of the mark, not of the replay.
        pending.add(profileId, items.map { item -> item.copy(watchedAtEpochMs = item.watchedAtEpochMs ?: nowEpochMs()) })

    private suspend fun liveWrite(
        profileId: Int,
        keys: Collection<TrackingMediaReference>,
        op: SimklPendingOp,
        pend: suspend () -> Unit,
        send: suspend () -> TrackingMutationResult
    ): TrackingMutationResult = lock.withLock {
        pending.supersede(profileId, keys, onlyOp = op.opposite)
        val result = try {
            sends.incrementAndGet()
            send()
        } catch (_: SimklQuotaExceededException) {
            pend()
            return@withLock TrackingMutationResult(keys.size)
        } catch (failure: SimklApiException) {
            if (!failure.isDailyLimit) throw failure
            pend()
            return@withLock TrackingMutationResult(keys.size)
        }
        pending.supersede(profileId, keys)
        result
    }

    /**
     * A Library write (Spec 106 S3: `/sync/add-to-list`, a whole-title `/sync/history/remove`). It meets the same
     * write lock, so it takes its turn with the marks and is sent like a live one. Nothing is kept for later: a spent
     * day fails it (E4: the Library keeps showing Simkl's last state).
     *
     * [decide] runs under [lock] and gives the request (null: nothing to send). Its argument is true when anything was
     * sent since [seenSends] (a mark or stop that went while the caller synced, or another Library write): the decision
     * the caller took from its sync may be stale and must be taken again from a fresh pull, so a removal never wipes a
     * watch that overtook it. No send can start while [lock] is held.
     */
    internal suspend fun listWrite(profileId: Int, seenSends: Long, decide: suspend (stale: Boolean) -> SimklRequest?): SimklResponse? =
        lock.withLock {
            val request = decide(sends.get() != seenSends) ?: return@withLock null
            sends.incrementAndGet()
            sendOnceMore(profileId, request)
        }

    /** A live write: one call, and once more after Simkl's write lock. */
    private suspend fun write(profileId: Int, op: SimklPendingOp, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        // Entry-numbered specials are never sent ([isEntryNumberedSpecial]); nothing else left = no call.
        if (items.all { it.media.isEntryNumberedSpecial }) return TrackingMutationResult(items.size)
        val reply = sendOnceMore(profileId, historyRequest(op, items))
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = simklNotFoundCount(reply.body))
    }

    private suspend fun sendOnceMore(profileId: Int, request: SimklRequest): SimklResponse = try {
        session.call(profileId, request)
    } catch (failure: SimklApiException) {
        if (!failure.isWriteLocked) throw failure
        sleep(WRITE_LOCK_MS)
        session.call(profileId, request)
    }

    private suspend fun post(profileId: Int, op: SimklPendingOp, items: Collection<TrackingHistoryItem>): SimklResponse =
        session.call(profileId, historyRequest(op, items))

    private fun historyRequest(op: SimklPendingOp, items: Collection<TrackingHistoryItem>): SimklRequest {
        val path = if (op == SimklPendingOp.ADD) "/sync/history" else "/sync/history/remove"
        return SimklRequest(SimklHttpMethod.POST, path, jsonBody = simklHistoryBody(items, withWatchedAt = op == SimklPendingOp.ADD))
    }

    private companion object {
        const val TAG = "SimklWrites"

        /** Simkl's per-user write lock, plus a margin. */
        const val WRITE_LOCK_MS = 21_000L
    }
}

/** At or above this, a stop marks the title watched at Simkl (and a missed one becomes a pending mark). */
internal const val SIMKL_WATCHED_PERCENT = 80.0

internal val SimklApiException.isDailyLimit: Boolean get() = status == 429 && error == SIMKL_ERROR_USER_LIMIT

/** Simkl's 20 s per-user write lock (another write went within it). */
internal val SimklApiException.isWriteLocked: Boolean get() = status == 400 && error == SIMKL_ERROR_RATE_LIMIT

/**
 * A write Simkl will refuse the same way every time (a bad body): a 4xx that is not about the account (401, 403), a
 * timeout (408), the allowance (429) or the write lock (`400 rate_limit`). A 400 without Simkl's error code (an edge
 * page) is not Simkl's word on the body either.
 */
internal val SimklApiException.isRefusedForGood: Boolean
    get() = status in 400..499 && status !in setOf(401, 403, 408, 429) && error != SIMKL_ERROR_RATE_LIMIT &&
        (status != 400 || error != null)

/** An anime episode as its own entry numbers it (Spec 106 D17): the entry's id ([key] kitsu/mal/anilist/anidb) and number. */
data class SimklEntryEpisode(val key: String, val id: Long, val number: Int)

private val ENTRY_ID_KEYS = listOf("kitsu", "mal", "anilist", "anidb")

private fun TrackingExternalIds.entryId(key: String): Long? = when (key) {
    "kitsu" -> kitsu
    "mal" -> mal
    "anilist" -> anilist
    "anidb" -> anidb
    else -> null
}

private val TrackingMediaReference.hasShowWideId: Boolean
    get() = !ids.imdb.isNullOrBlank() || ids.tmdb != null || !ids.tvdb.isNullOrBlank()

/**
 * The episode as its anime entry numbers it, or null (Spec 106 D17, upstream `fc5ea34c5`/`13605e076`): decided from the
 * episode's own video id when it is a per-entry one (`mal:42203:7`, `kitsu:8671:5`: that id and the last number, even
 * under an IMDb parent), else from a reference named only by per-entry ids (its own episode number, Kitsu's season 1).
 * Its number is the entry's: Simkl's default flat numbering (api.simkl.org guides/anime "Path B": anime-native ids are
 * 1:1 with Simkl's anime records), never TVDB's (`use_tvdb_anime_seasons` would read season 1 as the show's TVDB
 * season 1, another entry; red team 3bc97935). Season 0 of the second kind is [isEntryNumberedSpecial], not this.
 */
internal fun TrackingMediaReference.entryEpisode(): SimklEntryEpisode? {
    val episode = episode ?: return null
    catalog?.videoId?.split(':')?.takeIf { it.size >= 3 }?.let { parts ->
        val key = parts[0].trim().lowercase()
        val id = parts[1].trim().toLongOrNull()
        val number = parts.last().trim().toIntOrNull()
        if (key in ENTRY_ID_KEYS && id != null && number != null) return SimklEntryEpisode(key, id, number)
    }
    if (kind != TrackingMediaKind.ANIME || hasShowWideId || episode.season == 0) return null
    val key = ENTRY_ID_KEYS.firstOrNull { ids.entryId(it) != null } ?: return null
    return SimklEntryEpisode(key, checkNotNull(ids.entryId(key)), episode.number)
}

/**
 * Season 0 of an anime named only by per-entry ids: flat numbering has no specials, so it would land on a regular
 * episode. Never sent (as a whole series is not).
 */
internal val TrackingMediaReference.isEntryNumberedSpecial: Boolean
    get() = kind == TrackingMediaKind.ANIME && !hasShowWideId && episode?.season == 0 &&
        ENTRY_ID_KEYS.any { ids.entryId(it) != null } && entryEpisode() == null

/**
 * The reference as sent, one id family per request (Spec 106 D17; Simkl walks ids `simkl` first, then imdb, tmdb,
 * tvdb, mal..., and a mix can bind TVDB coordinates to one cour's entry): an [entryEpisode] goes flat with only its
 * entry's id; another anime episode with a season goes TVDB-style with only its show-wide ids (upstream `4425ff8af`).
 * Films, shows and anime without episode coordinates are unchanged.
 */
internal fun TrackingMediaReference.simklWriteForm(): TrackingMediaReference {
    entryEpisode()?.let { entry ->
        return copy(
            ids = TrackingExternalIds(
                kitsu = entry.id.takeIf { entry.key == "kitsu" },
                mal = entry.id.takeIf { entry.key == "mal" },
                anilist = entry.id.takeIf { entry.key == "anilist" },
                anidb = entry.id.takeIf { entry.key == "anidb" }
            ),
            episode = TrackingEpisode(season = null, number = entry.number, title = episode?.title)
        )
    }
    if (kind == TrackingMediaKind.ANIME && episode?.season != null && hasShowWideId) {
        return copy(ids = ids.copy(simkl = null, mal = null, anidb = null, anilist = null, kitsu = null))
    }
    return this
}

/**
 * True for an episode removal whose video id is unknown (not an [entryEpisode]; neither its stored progress nor its
 * caller knew it, so its mark may have gone flat to one cour's entry) of a title a synced anime series entry carries by
 * any shared id, or of any title while the profile has no synced snapshot yet (D19 guard, narrowed by D20). A known video
 * id (`tt4940456:2:19`) routed the mark the same way, so its removal goes.
 */
internal fun simklAnimeTitleHeld(snapshot: SimklSnapshot?, media: TrackingMediaReference): Boolean {
    if (media.episode == null || media.kind == TrackingMediaKind.MOVIE || isEpisodeVideoId(media.catalog?.videoId)) return false
    if (snapshot == null) return true
    return snapshot.entries.any { entry ->
        entry.type == SimklMediaType.ANIME && !entry.isMovie && entry.media?.externalIds()?.sharesAnyId(media.ids) == true
    }
}

/**
 * The single synced anime entry that carries [episode]'s entry id and lists that episode as watched in season 1: its
 * Simkl id, else null. Season 1 only: a flat `episodes[]` removal is read as season 1 at Simkl (api.simkl.org
 * remove-from-history), so an episode listed only under another season is held, never removed there. A removal by it can only touch the entry that already holds the watch
 * (Spec 106 D16; MAL-Sync and CrossWatch remove the same way).
 */
internal fun simklEntryHolding(snapshot: SimklSnapshot?, episode: SimklEntryEpisode): Long? =
    snapshot?.entries.orEmpty()
        .filter { it.type == SimklMediaType.ANIME && !it.isMovie && it.media?.id(episode.key)?.toLongOrNull() == episode.id }
        .singleOrNull()
        ?.takeIf { entry -> entry.seasons.any { season -> season.number == 1 && season.episodes.any { it.number == episode.number } } }
        ?.media?.simklId

/**
 * `/scrobble/{start,pause,stop}` body: `progress` (two decimals), the title as `movie`, `show` or `anime`, and the
 * `episode`, in its [simklWriteForm]. An anime episode with a TVDB-style season goes as a show (Simkl maps it through
 * TVDB); one without, or numbered within its entry, as `anime` with its own number (upstream NuvioTV's rule).
 */
internal fun simklScrobbleBody(event: TrackingScrobbleEvent): String {
    val media = event.media.simklWriteForm()
    val animeAsShow = media.kind == TrackingMediaKind.ANIME && media.episode?.season != null
    val key = when {
        media.kind == TrackingMediaKind.MOVIE -> "movie"
        media.kind == TrackingMediaKind.SHOW || animeAsShow -> "show"
        else -> "anime"
    }
    return buildJsonObject {
        put("progress", round(event.progressPercent.coerceIn(0.0, 100.0) * 100.0) / 100.0)
        put(key, media.titleObject())
        media.episode?.let { episode ->
            put("episode", buildJsonObject {
                episode.season?.let { put("season", it) }
                put("number", episode.number)
            })
        }
    }.toString()
}

/**
 * `/sync/history` (and `/remove`) body (upstream NuvioTV's shape): films under `movies`; every show and anime episode
 * under `shows`, one object per title with `seasons[].episodes[]` (anime ones flagged `use_tvdb_anime_seasons`) or
 * flat `episodes[]` for anime numbered without a season or within its entry, each in its [simklWriteForm]. [withWatchedAt] adds each item's `watched_at`. Whole-series
 * references never reach here (the router drops them).
 */
internal fun simklHistoryBody(sentItems: Collection<TrackingHistoryItem>, withWatchedAt: Boolean): String {
    val items = sentItems.filterNot { it.media.isEntryNumberedSpecial }.map { it.copy(media = it.media.simklWriteForm()) }
    val movies = items.filter { it.media.kind == TrackingMediaKind.MOVIE }
    val shows = items.filter { it.media.kind != TrackingMediaKind.MOVIE && it.media.episode != null }
        .groupBy { it.media.stableKey }.values
    return buildJsonObject {
        putJsonArray("movies") {
            movies.forEach { item ->
                add(item.media.titleObject(item.watchedAtEpochMs.takeIf { withWatchedAt }))
            }
        }
        putJsonArray("shows") {
            shows.forEach { group -> add(showObject(group, withWatchedAt)) }
        }
    }.toString()
}

private fun showObject(group: List<TrackingHistoryItem>, withWatchedAt: Boolean): JsonObject {
    val first = group.first().media
    val episodes = group.map { it to checkNotNull(it.media.episode) }
    val flat = episodes.filter { (_, episode) -> episode.season == null }.distinctBy { (_, episode) -> episode.number }
    val seasons = episodes.filter { (_, episode) -> episode.season != null }
        .groupBy { (_, episode) -> episode.season!! }.toSortedMap()
    val base = first.titleObject()
    return buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        if (flat.isNotEmpty()) {
            putJsonArray("episodes") { flat.forEach { (item, episode) -> add(episodeObject(episode, item, withWatchedAt)) } }
        }
        if (seasons.isNotEmpty()) {
            putJsonArray("seasons") {
                seasons.forEach { (season, inSeason) ->
                    addJsonObject {
                        put("number", season)
                        putJsonArray("episodes") {
                            inSeason.distinctBy { (_, episode) -> episode.number }
                                .forEach { (item, episode) -> add(episodeObject(episode, item, withWatchedAt)) }
                        }
                    }
                }
            }
            if (first.kind == TrackingMediaKind.ANIME) put("use_tvdb_anime_seasons", true)
        }
    }
}

private fun episodeObject(episode: TrackingEpisode, item: TrackingHistoryItem, withWatchedAt: Boolean): JsonObject =
    buildJsonObject {
        put("number", episode.number)
        item.watchedAtEpochMs.takeIf { withWatchedAt }?.let(::simklUtcTime)?.let { put("watched_at", it) }
    }

internal fun TrackingMediaReference.titleObject(watchedAtEpochMs: Long? = null): JsonObject = buildJsonObject {
    title?.trim()?.takeIf(String::isNotEmpty)?.let { put("title", it) }
    year?.let { put("year", it) }
    ids.toSimklIds()?.let { put("ids", it) }
    watchedAtEpochMs?.let(::simklUtcTime)?.let { put("watched_at", it) }
}

/** The ids Simkl matches by; null when there are none (Simkl then matches by title and year). */
internal fun TrackingExternalIds.toSimklIds(): JsonObject? = buildJsonObject {
    simkl?.let { put("simkl", it) }
    imdb?.trim()?.takeIf(String::isNotEmpty)?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
    tvdb?.trim()?.takeIf(String::isNotEmpty)?.let { value -> value.toLongOrNull()?.let { put("tvdb", it) } ?: put("tvdb", value) }
    mal?.let { put("mal", it) }
    anidb?.let { put("anidb", it) }
    anilist?.let { put("anilist", it) }
    kitsu?.let { put("kitsu", it) }
}.takeIf { it.isNotEmpty() }

/** "2026-11-17T05:21:03Z"; null before 2001 (a missing or bogus time: Simkl then uses its own now). */
internal fun simklUtcTime(epochMs: Long): String? {
    if (epochMs < MIN_WATCHED_AT_MS) return null
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(epochMs))
}

private const val MIN_WATCHED_AT_MS = 978_307_200_000L

/** The number of titles and episodes in a write reply's `not_found` (every list in it); 0 when it has none. */
internal fun simklNotFoundCount(body: String): Int {
    val reply = try {
        decode(SimklWriteReply.serializer(), body)
    } catch (_: SimklException) {
        return 0
    }
    return (reply.notFound as? JsonObject)?.values?.sumOf { (it as? JsonArray)?.size ?: 0 } ?: 0
}

@Serializable
private data class SimklWriteReply(@kotlinx.serialization.SerialName("not_found") val notFound: JsonElement? = null)
