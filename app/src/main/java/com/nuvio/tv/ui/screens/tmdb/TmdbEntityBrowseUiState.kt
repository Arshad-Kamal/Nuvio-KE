package com.nuvio.tv.ui.screens.tmdb

import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.ui.screens.home.HomeUiState

sealed interface TmdbEntityBrowseUiState {
    data object Loading : TmdbEntityBrowseUiState
    data class Error(val message: String) : TmdbEntityBrowseUiState

    /** No TMDB key on this copy (Spec 52 T1): nothing was requested; the page loads once one is saved. */
    data object NeedsTmdbKey : TmdbEntityBrowseUiState

    /**
     * [subline] = [entityHeaderSubline]. [homeState] = the rails as Home rows for MarqueeHomeContent,
     * rebuilt off the main thread after each rail change; null until the first build publishes.
     * It can trail [data] by one build (a rail change lands before its rebuild publishes).
     */
    data class Success(
        val data: TmdbEntityBrowseData,
        val subline: String?,
        val homeState: HomeUiState? = null
    ) : TmdbEntityBrowseUiState
}
