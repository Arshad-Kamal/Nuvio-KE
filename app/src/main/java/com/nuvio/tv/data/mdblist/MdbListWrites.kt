package com.nuvio.tv.data.mdblist

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
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * MDBList writes (Spec 105 S2b; api.mdblist.com/schema: scrobble start/pause/stop, `POST /sync/watched`,
 * `/sync/watched/remove`, read 2026-11-17). Writes are sent once (the client never resends a write) and only for the
 * profile's own key; while the day's allowance is spent nothing is sent (D5). The snapshot is never edited here: it
 * holds only what MDBList answered, the provider's optimistic overlay shows this TV's plays and marks meanwhile, and
 * the next sync brings MDBList's state (its activity stamps moved). MDBList reads episode numbers as TMDb's: each write
 * first passes its reference through [MdbListNumbering] ([MdbListEpisodeNumbering.toMdbList], S6 N5), which moves a
 * `tt` series episode out of TVDB aired order through MDBList's map, or answers null for one that must not be sent.
 */

/** A reference in MDBList's numbering for (profile, reference, the write's item time), or null = not sent. */
typealias MdbListNumbering = suspend (profileId: Int, reference: TrackingMediaReference, itemTime: Long?) -> MdbListNumbered?

/** A write's [reference] in MDBList's numbering; [onSent] runs once MDBList accepted the write (never on a failure). */
class MdbListNumbered(val reference: TrackingMediaReference, val onSent: suspend () -> Unit = {})

/** What the scrobble and Continue Watching calls name: a film, or one episode of a show. */
internal sealed interface MdbListTarget {
    val ids: JsonObject

    data class Movie(override val ids: JsonObject) : MdbListTarget

    data class Episode(override val ids: JsonObject, val season: Int, val number: Int) : MdbListTarget
}

/** The `movie` / `show` part of a scrobble or `/scrobble/clear` body (the show's ids with flat season/episode). */
internal fun MdbListTarget.scrobbleBody(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    when (val target = this@scrobbleBody) {
        is MdbListTarget.Movie -> put("movie", buildJsonObject { put("ids", target.ids) })
        is MdbListTarget.Episode -> put("show", buildJsonObject {
            put("ids", target.ids)
            put("season", target.season)
            put("episode", target.number)
        })
    }
    extra()
}

/** MDBList's id keys; `imdb` and `mdblist` are text, the others integers (schema). */
private val NUMERIC_IDS = setOf("tmdb", "trakt", "tvdb")

/** [raw] (key -> id text) as MDBList's `ids`: numbers where the schema has integers; null when nothing usable is left. */
internal fun mdbListIds(raw: Map<String, String?>): JsonObject? {
    val ids = buildJsonObject {
        raw.forEach { (key, value) ->
            val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
            if (key in NUMERIC_IDS) {
                text.toLongOrNull()?.takeIf { it > 0 }?.let { put(key, it) }
            } else {
                put(key, text)
            }
        }
    }
    return ids.takeIf { it.isNotEmpty() }
}

/** A reference's ids MDBList can match (IMDb, TMDB, TVDB, MDBList); null when it has none. */
internal fun TrackingExternalIds.toMdbListIds(): JsonObject? = mdbListIds(
    mapOf(
        "imdb" to imdb?.takeIf { it.startsWith("tt") },
        "tmdb" to tmdb?.toString(),
        "tvdb" to tvdb,
        "mdblist" to mdblist
    )
)

/**
 * The film or episode [this] names, or null when MDBList could not take it: no id it matches, a whole series (it would
 * mark every episode, upstream dc3776af9), or an episode without a season (an absolute number names no TMDb episode).
 */
internal fun TrackingMediaReference.toMdbListTarget(): MdbListTarget? {
    val ids = ids.toMdbListIds() ?: return null
    if (kind == TrackingMediaKind.MOVIE) return MdbListTarget.Movie(ids)
    val episode = episode ?: return null
    val season = episode.season?.takeIf { it >= 0 } ?: return null
    val number = episode.number.takeIf { it > 0 } ?: return null
    return MdbListTarget.Episode(ids, season, number)
}

/**
 * Plays to MDBList (`/scrobble/start|pause|stop`) for the active profile. A start replaces the session (no
 * stop/restart on seek). Leaving while paused sends a stop: the position may have moved since the pause (a seek while
 * paused sends nothing), and a stop under 80% saves it as the paused session. A pause or stop at 80% or more marks
 * the item watched there.
 */
class MdbListScrobbler(
    private val session: MdbListSession,
    private val configuration: MdbListApiConfiguration,
    private val activeProfileId: StateFlow<Int>,
    private val numbering: MdbListNumbering,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MDBLIST
    override val stopsWhenLeftPaused = true

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!event.progressPercent.isFinite()) return
        val profileId = activeProfileId.value
        val numbered = numbering(profileId, event.media, nowEpochMs()) ?: return
        val target = numbered.reference.toMdbListTarget() ?: return
        val body = target.scrobbleBody {
            put("progress", event.progressPercent.coerceIn(0.0, 100.0))
            put("app_name", MDBLIST_APP_LABEL)
            put("app_version", configuration.appVersion)
        }
        session.call(
            profileId,
            MdbListRequest(MdbListHttpMethod.POST, "/scrobble/${action.wireValue}", jsonBody = body.toString())
        )
        numbered.onSent()
    }
}

/**
 * Watched marks to MDBList: `POST /sync/watched` and `/sync/watched/remove`, in batches of [BATCH] (the schema caps
 * shows at 200 per request). A mark always carries its date (the item's, else now): MDBList skips an already-watched
 * item sent without one, so a rewatch would be lost. An episode with its TMDB episode id goes by that id (no number
 * matching, so it skips [numbering]); otherwise under its show by season and number, once numbered. Items MDBList
 * cannot take (an episode with no MDBList counterpart included), and the ones it reports as not found, count as not
 * found; a batch that fails throws after the earlier batches were sent.
 */
class MdbListHistoryWriter(
    private val session: MdbListSession,
    private val numbering: MdbListNumbering,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        change(profileId, items.toList(), remove = false)

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        change(profileId, items.map { TrackingHistoryItem(it) }, remove = true)

    private suspend fun change(profileId: Int, items: List<TrackingHistoryItem>, remove: Boolean): TrackingMutationResult {
        // Numbered in parallel: a show not mapped yet waits on its lookup, and the router bounds a write's time.
        val entries = coroutineScope {
            items.map { item ->
                async {
                    val itemTime = if (remove) null else item.watchedAtEpochMs ?: nowEpochMs()
                    val numbered = if (item.media.hasEpisodeTmdbId) MdbListNumbered(item.media) else numbering(profileId, item.media, itemTime)
                    numbered?.reference?.toHistoryEntry(itemTime, remove)?.also { it.onSent = numbered.onSent }
                }
            }.awaitAll().filterNotNull()
        }
        var notFound = items.size - entries.size
        entries.chunked(BATCH).forEach { batch ->
            val body = buildJsonObject {
                batch.groupBy(HistoryEntry::group).forEach { (group, rows) -> put(group, JsonArray(rows.map(HistoryEntry::json))) }
            }
            val path = if (remove) "/sync/watched/remove" else "/sync/watched"
            val reply = session.call(profileId, MdbListRequest(MdbListHttpMethod.POST, path, jsonBody = body.toString())).body
            val missing = mdbListMissingCount(reply, remove)
            notFound += missing
            // Which items MDBList did not place is not known per item: a batch with any is not counted as received.
            if (missing == 0) batch.forEach { it.onSent() }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound.coerceAtMost(items.size))
    }

    /** A removal carries no date; a mark its item's, else now. */
    private fun TrackingMediaReference.toHistoryEntry(watchedAtEpochMs: Long?, remove: Boolean): HistoryEntry? {
        val watchedAt = if (remove) null else Instant.ofEpochMilli(watchedAtEpochMs ?: nowEpochMs()).toString()
        fun JsonObjectBuilder.date() {
            watchedAt?.let { put("watched_at", it) }
        }
        // An episode's own TMDB id needs neither show ids nor numbers (the schema's way for other numberings).
        val episodeTmdb = episode?.tmdbId?.takeIf { hasEpisodeTmdbId }
        if (episodeTmdb != null) {
            return HistoryEntry("episodes", buildJsonObject { put("ids", buildJsonObject { put("tmdb", episodeTmdb) }); date() })
        }
        return when (val target = toMdbListTarget() ?: return null) {
            is MdbListTarget.Movie -> HistoryEntry("movies", buildJsonObject { put("ids", target.ids); date() })
            is MdbListTarget.Episode -> HistoryEntry("shows", buildJsonObject {
                put("ids", target.ids)
                put("seasons", buildJsonArray {
                    add(buildJsonObject {
                        put("number", target.season)
                        put("episodes", buildJsonArray { add(buildJsonObject { put("number", target.number); date() }) })
                    })
                })
            })
        }
    }

    private val TrackingMediaReference.hasEpisodeTmdbId: Boolean
        get() = (episode?.tmdbId ?: 0L) > 0 && kind != TrackingMediaKind.MOVIE

    private class HistoryEntry(val group: String, val json: JsonObject) {
        var onSent: suspend () -> Unit = {}
    }

    private companion object {
        const val BATCH = 100
    }
}

/**
 * How many items MDBList's write [reply] lists as unmatched: `not_found` as a count, a list, or one count or list per
 * bucket (history and watchlist writes), plus `errors` on a history removal (the schema lists unmatched episode ids
 * there). An unreadable reply counts nothing: the write was made.
 */
internal fun mdbListMissingCount(reply: String, remove: Boolean = false): Int {
    val json = try {
        mdbListJson.parseToJsonElement(reply) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return 0
    val notFound = when (val value = json["not_found"]) {
        is JsonObject -> value.values.sumOf(JsonElement::itemCount)
        null -> 0
        else -> value.itemCount()
    }
    val errors = if (remove) (json["errors"] as? JsonArray)?.size ?: 0 else 0
    return notFound + errors
}

private fun JsonElement.itemCount(): Int = when (this) {
    is JsonArray -> size
    is JsonPrimitive -> content.toIntOrNull()?.coerceAtLeast(0) ?: 0
    else -> 0
}

/** Shown on MDBList as "Scrobbling from …". */
internal const val MDBLIST_APP_LABEL = "Nuvio KE"
