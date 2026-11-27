package com.nuvio.tv.data.simkl

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * Simkl sync replies (Spec 106 S2a; field names per api.simkl.org get-all-items, get-activities, playback, checked
 * 2026-11-17, and upstream NuvioTV's SimklSyncModels). The same classes are the stored snapshot, so a field added
 * later must have a default.
 */

/** The three top-level keys of `/sync/all-items`; the key an item comes under is Simkl's classification of it. */
enum class SimklMediaType(val apiValue: String) {
    SHOWS("shows"),
    MOVIES("movies"),
    ANIME("anime")
}

@Serializable
enum class SimklListStatus(val apiValue: String) {
    @SerialName("watching") WATCHING("watching"),
    @SerialName("plantowatch") PLAN_TO_WATCH("plantowatch"),
    @SerialName("hold") ON_HOLD("hold"),
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("dropped") DROPPED("dropped")
}

/** A title stub: `ids` values come as strings or numbers, so they are kept raw and read through [id]. */
@Serializable
data class SimklMedia(
    val title: String? = null,
    val year: Int? = null,
    val ids: Map<String, JsonElement> = emptyMap(),
    /** An image path ("74/74415673dcdc9cdd"), not a URL: [simklPosterUrl] builds one (S3). */
    val poster: String? = null
) {
    /** The id under [key] as text; a JSON null or blank is no id (kotlinx's `JsonNull.content` is "null"). */
    fun id(key: String): String? = (ids[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.content?.trim()?.takeIf(String::isNotBlank)

    val simklId: Long? get() = id("simkl")?.toLongOrNull()
}

@Serializable
data class SimklEpisodeMapping(val season: Int? = null, val episode: Int? = null)

@Serializable
data class SimklEpisode(
    val number: Int? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    /** Anime only (`extended=full_anime_seasons`): the TVDB season and episode this anime episode maps to. */
    val tvdb: SimklEpisodeMapping? = null
)

@Serializable
data class SimklSeason(val number: Int? = null, val episodes: List<SimklEpisode> = emptyList())

@Serializable
data class SimklEntry(
    /** Not in Simkl's item: set from the key the item came under. */
    val type: SimklMediaType = SimklMediaType.SHOWS,
    val status: SimklListStatus? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("next_to_watch") val nextToWatch: String? = null,
    @SerialName("anime_type") val animeType: String? = null,
    val show: SimklMedia? = null,
    val movie: SimklMedia? = null,
    val seasons: List<SimklSeason> = emptyList(),
    /** S3: the Library's "Added" order, and what a removal would wipe ([listing]). */
    @SerialName("added_to_watchlist_at") val addedToWatchlistAt: String? = null,
    /** Simkl types it as a number; kept as one so a decimal never fails the whole sync. Refreshed by its own delta. */
    @SerialName("user_rating") val userRating: Double? = null,
    @SerialName("watched_episodes_count") val watchedEpisodesCount: Int? = null
) {
    val media: SimklMedia? get() = movie ?: show

    /** A film, anime films included (Simkl files them under anime with `anime_type` movie). */
    val isMovie: Boolean get() = type == SimklMediaType.MOVIES || (type == SimklMediaType.ANIME && animeType == "movie")
}

@Serializable
data class SimklAllItems(
    val shows: List<SimklEntry>? = null,
    val movies: List<SimklEntry>? = null,
    val anime: List<SimklEntry>? = null
) {
    /** Every entry, typed by the key it came under. */
    fun entries(): List<SimklEntry> =
        shows.orEmpty().map { it.copy(type = SimklMediaType.SHOWS) } +
            movies.orEmpty().map { it.copy(type = SimklMediaType.MOVIES) } +
            anime.orEmpty().map { it.copy(type = SimklMediaType.ANIME) }
}

@Serializable
data class SimklActivityDomain(
    val all: String? = null,
    val playback: String? = null,
    val plantowatch: String? = null,
    val watching: String? = null,
    val completed: String? = null,
    val hold: String? = null,
    val dropped: String? = null,
    @SerialName("removed_from_list") val removedFromList: String? = null,
    /** A rating change moves only this: it never puts the title into an all-items delta (S3a reads ratings). */
    @SerialName("rated_at") val ratedAt: String? = null
) {
    /** The buckets a `/sync/all-items?date_from=` delta answers (ratings are not shown in Nuvio). */
    fun listStamps(): List<String?> = listOf(plantowatch, watching, completed, hold, dropped)
}

@Serializable
data class SimklActivities(
    val all: String? = null,
    @SerialName("tv_shows") val tvShows: SimklActivityDomain = SimklActivityDomain(),
    val movies: SimklActivityDomain = SimklActivityDomain(),
    val anime: SimklActivityDomain = SimklActivityDomain()
) {
    val domains: List<SimklActivityDomain> get() = listOf(tvShows, movies, anime)
}

@Serializable
data class SimklPlaybackEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    @SerialName("tvdb_season") val tvdbSeason: Int? = null,
    @SerialName("tvdb_number") val tvdbNumber: Int? = null
)

/** A saved pause point (one per title at Simkl). */
@Serializable
data class SimklPlayback(
    val id: Long? = null,
    val progress: Double = 0.0,
    @SerialName("paused_at") val pausedAt: String? = null,
    val episode: SimklPlaybackEpisode? = null,
    val show: SimklMedia? = null,
    val anime: SimklMedia? = null,
    val movie: SimklMedia? = null
) {
    val media: SimklMedia? get() = movie ?: anime ?: show
}

/**
 * One profile's synced Simkl state. [activities] is the `/sync/activities` reply the data is current to: null = never
 * fully synced (the next sync is a full pull).
 */
@Serializable
data class SimklSnapshot(
    val activities: SimklActivities? = null,
    val entries: List<SimklEntry> = emptyList(),
    val playback: List<SimklPlayback> = emptyList()
)

/** Simkl's ISO-8601 UTC stamps ("2026-05-15T00:35:15Z", maybe with fractions or an offset); unreadable = null. */
internal fun parseSimklTime(value: String?): Long? {
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
