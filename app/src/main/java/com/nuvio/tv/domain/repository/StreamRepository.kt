package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
    /**
     * Fetches streams from all installed addons for a given video ID
     * @param type The content type (movie, series, etc.)
     * @param videoId The video ID (for movies: IMDB ID, for series: IMDB_ID:season:episode)
     * @param season Optional season number for TV shows (part of the search-session key)
     * @param episode Optional episode number for TV shows (part of the search-session key)
     * @return Flow of AddonStreams grouped by addon
     */
    fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false
    ): Flow<NetworkResult<List<AddonStreams>>>
}
