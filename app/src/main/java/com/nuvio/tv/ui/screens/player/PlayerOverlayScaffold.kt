package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Fades a player overlay in over clear video (Spec 25 Q1: no scrim; a caller that wants one
 * paints it, as the Sync line does), [content] inside [contentPadding].
 */
@Composable
internal fun PlayerOverlayScaffold(
    visible: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable BoxScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Compact player panel (approved sandbox `u6-player-panels-marquee` stages 4-7 and 10): the
 * [CompactPanel] bottom-end over clear video (Spec 25 Q1, no scrim), [title] as its eyebrow, then
 * [content]. Audio, Subtitles, Speed and Chapters sit on it.
 */
@Composable
internal fun PlayerCompactPanel(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    PlayerOverlayScaffold(
        visible = visible,
        modifier = modifier,
        contentPadding = PaddingValues(
            end = tokens.compactEndInset,
            top = tokens.compactVerticalInset,
            bottom = tokens.compactVerticalInset
        )
    ) {
        CompactPanel(
            modifier = Modifier
                .width(tokens.compactWidth)
                .align(Alignment.BottomEnd)
        ) {
            PanelEyebrow(text = title)
            content()
        }
    }
}

