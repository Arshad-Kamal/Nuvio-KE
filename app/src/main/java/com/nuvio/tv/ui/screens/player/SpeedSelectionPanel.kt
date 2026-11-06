package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PLAYBACK_SPEEDS
import com.nuvio.tv.ui.components.PlayerPanelRow
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Speed panel (approved sandbox `u6-player-panels-marquee` stage 10, opened from the More tray's
 * Speed pill): the compact panel, one row per speed, focused on the current one; OK applies and
 * closes. Back (PlayerScreen) reopens the tray on the Speed pill (Spec 25 Q4).
 */
@Composable
internal fun SpeedSelectionPanel(
    visible: Boolean,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val requesters = remember { PLAYBACK_SPEEDS.map { FocusRequester() } }
    val initialIndex = speedPanelInitialIndex(currentSpeed)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        delay(120)
        runCatching { requesters[initialIndex].requestFocus() }
    }
    PlayerCompactPanel(
        visible = visible,
        title = stringResource(R.string.player_speed_title),
        modifier = modifier
    ) {
        // Eight fixed rows: a plain Column (every row composed, so the initial request always lands).
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PLAYBACK_SPEEDS.forEachIndexed { index, speed ->
                PlayerPanelRow(
                    title = if (speed == 1f) stringResource(R.string.player_speed_normal) else "${speed}x",
                    selected = speed == currentSpeed,
                    onClick = { onSpeedSelected(speed) },
                    focusRequester = requesters[index]
                )
            }
        }
    }
}

/** The [PLAYBACK_SPEEDS] row nearest [currentSpeed] (an off-list speed focuses its neighbour). */
internal fun speedPanelInitialIndex(currentSpeed: Float): Int =
    PLAYBACK_SPEEDS.indices.minByOrNull { abs(PLAYBACK_SPEEDS[it] - currentSpeed) } ?: 0
