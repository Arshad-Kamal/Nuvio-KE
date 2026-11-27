package com.nuvio.tv.data.mdblist

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * MDBList sync replies and the stored snapshot (Spec 105 S2a; field names per api.mdblist.com/schema, read 2026-11-17,
 * and upstream NuvioTV's MdbListSyncDecoders). Stored classes are read back from disk, so a field added later must
 * have a default. Lists in replies are nullable: a JSON `null` must not fail the whole reply.
 */

/** A film or show stub. `ids` values may come as strings or numbers, so they are kept raw and read through [id]. */
@Serializable
data class MdbListMedia(
    val title: String? = null,
    val year: Int? = null,
    val ids: Map<String, JsonElement> = emptyMap()
) {
    /** The id under [key] as text; a JSON null or blank is no id (kotlinx's `JsonNull.content` is "null"). */
    fun id(key: String): String? = (ids[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.content?.trim()?.takeIf(String::isNotBlank)

    val imdb: String? get() = id("imdb")?.takeIf { it.startsWith("tt") }
    val tmdb: Long? get() = id("tmdb")?.toLongOrNull()?.takeIf { it > 0 }

    /** The id Nuvio names this title by (IMDb, else TMDB), or null when MDBList gave neither. */
    val contentId: String? get() = imdb ?: tmdb?.let { "tmdb:$it" }

    /** Every id form two replies may share, for matching one title across them. */
    fun aliases(): List<String> = listOfNotNull(
        tmdb?.let { "tmdb:$it" },
        imdb,
        id("mdblist")?.let { "mdblist:$it" },
        id("tvdb")?.let { "tvdb:$it" },
        id("trakt")?.let { "trakt:$it" }
    )
}

/**
 * One watched mark: a film ([season]/[episode] null) or an episode of the show [media]. Whole-show and season rows are
 * never kept (upstream `dc3776af9`: a mark without episode numbers would mark the whole series).
 */
@Serializable
data class MdbListWatchedMark(
    val media: MdbListMedia,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAt: String? = null
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/**
 * `/sync/last_activities`: stamps per bucket. [serverTime] is MDBList's clock when it answered. [watchlistedAt] moves on
 * every watchlist add or removal (schema: "Successful writes update the user's watchlist activity timestamp").
 */
@Serializable
data class MdbListActivities(
    @SerialName("watchlisted_at") val watchlistedAt: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("season_watched_at") val seasonWatchedAt: String? = null,
    @SerialName("episode_watched_at") val episodeWatchedAt: String? = null,
    @SerialName("journal_at") val journalAt: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("episode_paused_at") val episodePausedAt: String? = null,
    @SerialName("server_time") val serverTime: String? = null
) {
    /** The buckets `/sync/journal` answers for. */
    fun watchedStamps(): List<String?> = listOf(watchedAt, seasonWatchedAt, episodeWatchedAt, journalAt)

    fun pausedStamps(): List<String?> = listOf(pausedAt, episodePausedAt)
}

@Serializable
data class MdbListPlaybackEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val title: String? = null,
    val show: MdbListMedia? = null
)

/** A saved pause point from `/sync/playback`; [progress] is 0-100. Stored as is in the snapshot. */
@Serializable
data class MdbListPlayback(
    val id: Long? = null,
    val progress: Double? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val type: String? = null,
    val movie: MdbListMedia? = null,
    val show: MdbListMedia? = null,
    val episode: MdbListPlaybackEpisode? = null
) {
    val isEpisode: Boolean get() = type == "episode" || (type == null && movie == null && episode != null)

    /** The film, or the episode's show. */
    val media: MdbListMedia? get() = if (isEpisode) show ?: episode?.show else movie
}

/**
 * One profile's synced MDBList state. [activities] is the `/sync/last_activities` reply the data is current to and
 * [journalSince] the `since` for the next `/sync/journal` (that reply's `server_time`): either null = the next sync is
 * a full pull. [watchlist] is the watchlist as of [activities] (S3a); null = never pulled (a snapshot from before S3a).
 */
@Serializable
data class MdbListSnapshot(
    val activities: MdbListActivities? = null,
    val journalSince: String? = null,
    val marks: List<MdbListWatchedMark> = emptyList(),
    val playback: List<MdbListPlayback> = emptyList(),
    val watchlist: List<MdbListWatchlistItem>? = null
)

/**
 * One watchlist title as stored (S3a): [poster] is MDBList's TMDb image path, [listedAt] its added date as MDBList
 * wrote it (format not documented; read by [parseMdbListListedAt]).
 */
@Serializable
data class MdbListWatchlistItem(
    val media: MdbListMedia,
    val isMovie: Boolean,
    val poster: String? = null,
    val genres: List<String> = emptyList(),
    val listedAt: String? = null
)

/**
 * A `/watchlist/items` row. The schema documents `id` (TMDb), `imdb_id`, `tvdb_id`, `ids` (films only in its example),
 * `title` and `release_year`; poster, genres and the added date are not documented, so they are read as raw JSON and
 * never fail the page.
 */
@Serializable
internal data class MdbListWatchlistRow(
    val id: JsonElement? = null,
    val title: String? = null,
    @SerialName("imdb_id") val imdbId: JsonElement? = null,
    @SerialName("tvdb_id") val tvdbId: JsonElement? = null,
    val ids: Map<String, JsonElement>? = null,
    @SerialName("release_year") val releaseYear: JsonElement? = null,
    val poster: JsonElement? = null,
    val genres: JsonElement? = null,
    @SerialName("watchlist_at") val watchlistAt: JsonElement? = null,
    @SerialName("listed_at") val listedAt: JsonElement? = null,
    @SerialName("added_at") val addedAt: JsonElement? = null,
    /** A list's own order (list pages, Spec 105 S5a); the watchlist ignores it. */
    val rank: JsonElement? = null
)

/** A `/watchlist/items` page: films and shows in their own arrays (seasons and episodes are not Library titles). */
@Serializable
internal data class MdbListWatchlistPage(
    val movies: List<MdbListWatchlistRow>? = null,
    val shows: List<MdbListWatchlistRow>? = null,
    val pagination: MdbListPagination? = null
)

@Serializable
internal data class MdbListPagination(
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean? = null
)

@Serializable
internal data class MdbListWatchedMovieRow(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val movie: MdbListMedia? = null
)

@Serializable
internal data class MdbListWatchedEpisodeRow(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val episode: MdbListPlaybackEpisode? = null
)

/** A `/sync/watched?mediatype=` page: only the asked type's list is filled. */
@Serializable
internal data class MdbListWatchedPage(
    val movies: List<MdbListWatchedMovieRow>? = null,
    val episodes: List<MdbListWatchedEpisodeRow>? = null,
    val pagination: MdbListPagination? = null
)

@Serializable
internal data class MdbListJournalRow(
    val category: String? = null,
    @SerialName("item_type") val itemType: String? = null,
    /** The film's ids, or the parent show's for season and episode rows. */
    val ids: Map<String, JsonElement> = emptyMap(),
    val status: String? = null,
    @SerialName("action_at") val actionAt: String? = null,
    @SerialName("value_at") val valueAt: String? = null,
    val season: Int? = null,
    val episode: Int? = null
)

@Serializable
internal data class MdbListJournalPage(
    val journal: List<MdbListJournalRow>? = null,
    @SerialName("requires_full_sync") val requiresFullSync: Boolean? = null,
    val pagination: MdbListPagination? = null
)

/** MDBList's ISO-8601 stamps ("2026-05-15T00:35:15Z", maybe with fractions or an offset); unreadable = null. */
internal fun parseMdbListTime(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    // DateTimeException covers a parse failure and a stamp out of range; ArithmeticException an epoch-ms overflow.
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeException) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    } catch (_: ArithmeticException) {
        null
    }
}
