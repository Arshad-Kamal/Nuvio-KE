package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Continue-watching display model shared by the home screens, the CW row/grid
 * components, navigation and the recommendations manager.
 *
 * G6 layering fix (2026-08-31): moved from ui/screens/home/HomeUiState.kt so
 * that ui/components and core/recommendations stop importing ui.screens (the
 * screens layer sits above components; core sits above both).
 */
@Immutable
sealed class ContinueWatchingItem {
    @Immutable
    data class InProgress(
        val progress: WatchProgress,
        val episodeDescription: String? = null,
        val episodeThumbnail: String? = null,
        val episodeImdbRating: Float? = null,
        val genres: List<String> = emptyList(),
        val releaseInfo: String? = null,
        val contentLanguage: String? = null
    ) : ContinueWatchingItem()

    @Immutable
    data class NextUp(val info: NextUpInfo) : ContinueWatchingItem()
}

@Immutable
data class NextUpInfo(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)
