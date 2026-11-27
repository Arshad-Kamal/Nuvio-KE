package com.nuvio.tv.livetv.player

import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether something plays that a background scan must not compete with (Spec 73 S3a, Spec 56: no
 * scan beside 4K playback): the main player while it is open, paused or not
 * ([PlayerSessionTracker]), or a [LivePlayer] tuning or playing a channel. Trailers do not count.
 */
@Singleton
class PlaybackActivity internal constructor(sessionOpen: Flow<Boolean>) {

    @Inject
    constructor() : this(PlayerSessionTracker.isSessionOpen)

    private val livePlayers = MutableStateFlow<Set<Any>>(emptySet())

    /**
     * Spec 147 D1 (R4-5): emits when the main player opens (once per closed-to-open change, and at once
     * if it is open when collected). A [LivePlayer] frees its idle ExoPlayer then.
     */
    val mainPlayerOpened: Flow<Unit> = sessionOpen.distinctUntilChanged().filter { it }.map { }

    val playing: Flow<Boolean> =
        combine(sessionOpen, livePlayers) { open, live -> open || live.isNotEmpty() }.distinctUntilChanged()

    /** [player] (compared by identity) is tuning or playing, or no longer; its owner clears it on release. */
    fun setLive(player: Any, active: Boolean) = livePlayers.update { if (active) it + player else it - player }
}
