package com.nuvio.tv.ui.screens.cast

sealed interface CastDetailUiState {
    /** [name] is the route's, shown in the skeleton before the fetch returns (stage 10). */
    data class Loading(val name: String) : CastDetailUiState
    data class Success(val page: PersonPage) : CastDetailUiState
    data class Error(val message: String) : CastDetailUiState

    /** No TMDB key on this copy (Spec 52 T1): nothing was requested; the page loads once one is saved. */
    data object NeedsTmdbKey : CastDetailUiState
}
