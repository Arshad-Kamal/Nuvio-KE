package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Key of a progress entry: "<contentId>_s<season>e<episode>" for an episode, else the bare
 * contentId. It is both the local store's map key and Supabase's progress_key, so the format
 * is persisted and synced: never change it (pinned by WatchProgressKeyTest).
 */
fun watchProgressKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "${contentId}_s${season}e${episode}" else contentId

/** [watchProgressKey] of this entry. */
val WatchProgress.progressKey: String
    get() = watchProgressKey(contentId, season, episode)

/**
 * Represents the watch progress for a content item (movie or episode).
 */
@Immutable
@Serializable
data class WatchProgress(
    val contentId: String,           // IMDB ID of the movie/series
    val contentType: String,         // "movie" or "series"
    val name: String,                // Movie or series name
    val poster: String?,             // Poster URL
    val backdrop: String?,           // Backdrop URL
    val logo: String?,               // Logo URL
    val videoId: String,             // Specific video/episode ID being watched
    val season: Int?,                // Season number (null for movies)
    val episode: Int?,               // Episode number (null for movies)
    val episodeTitle: String?,       // Episode title (null for movies)
    val position: Long,              // Current playback position in ms
    val duration: Long,              // Total duration in ms
    val lastWatched: Long,           // Timestamp when last watched
    val addonBaseUrl: String? = null, // Addon that was used to play
    val progressPercent: Float? = null, // 0..100; when set, overrides position/duration
    val source: String = SOURCE_LOCAL,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val simklPlaybackId: Long? = null,
    /** MDBList paused-session id, used to clear the session server-side. */
    val mdbListPlaybackId: Long? = null,
    val trackingProviderId: String? = null,
    val trackingProviderItemId: String? = null,
    val trackingSourceUrl: String? = null
) {
    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_TRAKT_PLAYBACK = "trakt_playback"
        const val SOURCE_SIMKL_PLAYBACK = "simkl_playback"
        const val STARTED_THRESHOLD = 0.02f
        const val COMPLETED_THRESHOLD = 0.90f
        const val SIMKL_COMPLETED_THRESHOLD = 0.80f
    }

    /**
     * Progress percentage (0.0 to 1.0)
     */
    val progressPercentage: Float
        get() {
            progressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        }

    /**
     * Returns true if the content has been watched past the threshold (default 90%)
     */
    fun isCompleted(threshold: Float = completionThreshold()): Boolean = progressPercentage >= threshold

    /**
     * Returns true if the content has been started but not completed
     */
    fun isInProgress(startThreshold: Float = STARTED_THRESHOLD, endThreshold: Float = completionThreshold()): Boolean =
        progressPercentage >= startThreshold && progressPercentage < endThreshold

    private fun completionThreshold(): Float =
        if (source == SOURCE_SIMKL_PLAYBACK) SIMKL_COMPLETED_THRESHOLD else COMPLETED_THRESHOLD

    fun resolveResumePosition(actualDuration: Long): Long {
        if (actualDuration <= 0) return position.coerceAtLeast(0L)
        // Position is the most precise resume indicator. Prefer it over
        // progressPercent even when saved duration is 0 (e.g. progress was
        // saved while paused and getEffectiveDuration returned 0).
        if (position > 0) {
            return position.coerceIn(0L, actualDuration)
        }
        progressPercent?.let { explicitPercent ->
            val fraction = (explicitPercent / 100f).coerceIn(0f, 1f)
            return (actualDuration * fraction).toLong()
        }
        return position.coerceAtLeast(0L)
    }
}

/**
 * Represents the next item to watch for a series or a movie to resume.
 */
@Immutable
data class NextToWatch(
    val watchProgress: WatchProgress?,  // Null if nothing has been watched yet
    val isResume: Boolean,              // True if resuming current item, false if next episode
    val nextVideoId: String?,           // Video ID to play next
    val nextSeason: Int?,               // Next season number
    val nextEpisode: Int?,              // Next episode number
    val displayText: String,             // Text to show on button (e.g., "Resume S1E2", "Play S1E3")
    /**
     * Fork (caught-up fix): true when every available episode of a series is
     * watched and none is resumable - e.g. the newest aired episode was just
     * finished and the next is unreleased. The Play target is then the LAST
     * episode (an honest replay) and the details-page hero source line
     * suppresses itself (no preview, no prefetch). Default false keeps every
     * other construction site unchanged.
     */
    val isCaughtUp: Boolean = false
)

/**
 * Watched = completed progress, or in the watched set (Detail: the observed set of local marks +
 * completed progress; the player's Episodes panel: its own copies of both).
 */
internal fun isEpisodeWatched(
    progressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    season: Int,
    episode: Int
): Boolean =
    progressMap[season to episode]?.isCompleted() == true || (season to episode) in watchedEpisodes
