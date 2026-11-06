package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    isMovie: Boolean,
    isSeries: Boolean = false,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    // Library's History cards (u7 stage 7): one more row.
    onRemoveFromHistory: (() -> Unit)? = null
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        PanelActionRow(
            label = stringResource(R.string.cw_action_go_to_details),
            onClick = onDetails,
            focusRequester = primaryFocusRequester
        )

        PanelActionRow(
            label = if (isInLibrary) {
                stringResource(R.string.hero_remove_from_library)
            } else {
                stringResource(R.string.hero_add_to_library)
            },
            onClick = onToggleLibrary,
            enabled = !isLibraryPending
        )

        if (isMovie || isSeries) {
            PanelActionRow(
                label = if (isWatched) {
                    stringResource(R.string.hero_mark_unwatched)
                } else {
                    stringResource(R.string.hero_mark_watched)
                },
                onClick = onToggleWatched,
                enabled = !isWatchedPending
            )
        }

        if (onRemoveFromHistory != null) {
            PanelActionRow(
                label = stringResource(R.string.library_history_remove),
                onClick = onRemoveFromHistory
            )
        }
    }
}

@Composable
fun PosterOptionsHost(
    state: PosterOptionsState,
    controller: PosterOptionsController,
    onNavigateToDetail: (id: String, type: String, addonBaseUrl: String) -> Unit,
    /** Adds Remove from history; called with the card's own title id (before canonicalization). */
    onRemoveFromHistory: ((String) -> Unit)? = null
) {
    val target = state.target
    if (target != null) {
        val isMovie = target.apiType.equals("movie", ignoreCase = true)
        val isSeries = target.apiType.equals("series", ignoreCase = true) ||
            target.apiType.equals("tv", ignoreCase = true) ||
            target.apiType.equals("anime", ignoreCase = true)
        PosterOptionsDialog(
            title = target.name,
            isInLibrary = state.isInLibrary,
            isLibraryPending = state.isLibraryPending,
            isMovie = isMovie,
            isSeries = isSeries,
            isWatched = state.isWatched,
            isWatchedPending = state.isWatchedPending,
            onDismiss = { controller.dismiss() },
            onDetails = {
                onNavigateToDetail(target.id, target.apiType, state.addonBaseUrl)
                controller.dismiss()
            },
            onToggleLibrary = {
                controller.toggleLibrary()
                controller.dismiss()
            },
            onToggleWatched = {
                if (isMovie) {
                    controller.toggleMovieWatched()
                } else {
                    controller.toggleSeriesWatched()
                }
                controller.dismiss()
            },
            onRemoveFromHistory = onRemoveFromHistory?.let { remove ->
                {
                    remove(state.originalItemId ?: target.id)
                    controller.dismiss()
                }
            }
        )
    }
}
