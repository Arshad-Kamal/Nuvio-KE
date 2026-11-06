package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing watch progress data.
 */
interface WatchProgressRepository {
    
    /**
     * Get all watch progress items sorted by last watched (most recent first)
     */
    val allProgress: Flow<List<WatchProgress>>
    
    val watchedItems: Flow<List<WatchedItem>>
    
    /**
     * Get watch progress for a specific content item (movie or series)
     */
    fun getProgress(contentId: String): Flow<WatchProgress?>
    
    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?>
    
    /**
     * Get all episode progress for a series as a map of (season, episode) to progress
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>

    /**
     * Get completed series episode seeds suitable for building a lightweight "Next Up".
     */
    fun observeNextUpSeeds(): Flow<List<WatchProgress>>

    /**
     * Returns whether the item is marked as watched/completed.
     * For series episodes pass both [season] and [episode].
     */
    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean>
    
    fun observeWatchedMovieIds(): Flow<Set<String>>

    /**
     * Apply an optimistic addition or removal to the watched movie IDs set.
     * Used for immediate badge feedback before the backend confirms the change.
     * Pass [add] = true to mark as watched, false to unmark.
     * The [ids] should include all ID variants for the content (e.g. "tmdb:123", "tt1234567").
     */
    fun applyOptimisticWatchedMovie(ids: Set<String>, add: Boolean)

    /**
     * Revert a previous optimistic update (e.g. on failure).
     */
    fun revertOptimisticWatchedMovie(ids: Set<String>, add: Boolean)

    /**
     * Returns per-show watched episodes from the local watched items of the active profile.
     * Empty map when no data is available.
     */
    suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean = true)

    /**
     * Persists Continue Watching display metadata (name, artwork, episode title) from
     * [enriched] onto the stored entries, local only. Playback fields stay as stored and
     * entries that no longer exist are skipped. Writes to [profileId] (the profile the CW
     * pipeline ran for), never re-reading the active profile.
     */
    suspend fun mergeContinueWatchingMetadata(enriched: List<WatchProgress>, profileId: Int)
    
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null)

    suspend fun removeFromHistory(contentId: String, season: Int? = null, episode: Int? = null)

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress)

    /**
     * Mark multiple episodes as completed in a single batch operation.
     * More efficient than calling [markAsCompleted] in a loop.
     */
    suspend fun markAsCompletedBatch(progressList: List<WatchProgress>)

    /**
     * Remove multiple episodes from history in a single batch operation.
     */
    suspend fun removeFromHistoryBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>
    )
}
