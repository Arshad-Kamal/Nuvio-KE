package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.core.player.PlayerUiState

/**
 * Whether Back treats an in-place episode switch as automatic (no Stream screen was visited for
 * it). MANUAL counts as auto-play whenever the runtime auto-selects the next episode's stream
 * (`shouldAutoSelectInManualMode`): with "prefer binge group" (NM `4ccd8b299`) or "auto-play next
 * episode" (upstream-sync T30, Arshad's pick; upstream leaves this one out).
 */
internal fun playerBackTreatsSwitchAsAutoPlay(
    streamAutoPlayMode: StreamAutoPlayMode,
    preferBingeGroupForNextEpisode: Boolean,
    autoPlayNextEpisodeEnabled: Boolean
): Boolean = streamAutoPlayMode != StreamAutoPlayMode.MANUAL ||
    preferBingeGroupForNextEpisode ||
    autoPlayNextEpisodeEnabled

internal val PlayerUiState.backTreatsSwitchAsAutoPlay: Boolean
    get() = playerBackTreatsSwitchAsAutoPlay(
        streamAutoPlayMode,
        streamAutoPlayPreferBingeGroupForNextEpisode,
        streamAutoPlayNextEpisodeEnabled
    )

internal enum class PlayerBackRoute {
    /** The title's Details page (focus on the episode). */
    DETAIL,
    /** Auto-played into another episode: Details when the route asked for it, else pop. */
    DETAIL_OR_POP,
    /** Manually switched episode: the Stream screen of the episode now playing. */
    CURRENT_EPISODE_STREAMS,
    /** The screen the player was opened from (its Stream screen when on the back stack). */
    PREVIOUS_SCREEN
}

/**
 * Where Back from the player goes. A finished title goes to Details before the episode-switch
 * branches are considered (NM `2f9d86a7e`): a completed episode never reopens a source list.
 */
internal fun playerBackRoute(
    episodeChangedInPlace: Boolean,
    autoPlayEnabled: Boolean,
    playbackCompleted: Boolean,
    hasContentId: Boolean
): PlayerBackRoute = when {
    playbackCompleted && hasContentId -> PlayerBackRoute.DETAIL
    episodeChangedInPlace && autoPlayEnabled -> PlayerBackRoute.DETAIL_OR_POP
    episodeChangedInPlace -> PlayerBackRoute.CURRENT_EPISODE_STREAMS
    else -> PlayerBackRoute.PREVIOUS_SCREEN
}
