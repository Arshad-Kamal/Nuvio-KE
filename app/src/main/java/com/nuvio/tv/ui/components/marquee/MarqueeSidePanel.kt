package com.nuvio.tv.ui.components.marquee

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.holdReleaseGuard
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * The Marquee side panel (u8 collection editor stages 12-14 `.l8p`, u10 profile options): drawn in
 * the tree over the still-composed screen, which the
 * [panelVeil][com.nuvio.tv.ui.theme.NuvioMarqueeCollectionsTokens.panelVeil] dims; a compact panel
 * [width] wide at the end, full height with [fillHeight], else as tall as its content and centred.
 * The D-pad stays inside; Back calls [onDismiss] (composed after the screen, so it wins).
 * [initialFocus] takes focus on open and whenever it changes (a page switch). [openedByHold]: the
 * panel was opened by a long press, so that press's release must not fire the first row
 * ([holdReleaseGuard]).
 */
@Composable
fun MarqueeSidePanel(
    onDismiss: () -> Unit,
    initialFocus: FocusRequester,
    fillHeight: Boolean = false,
    openedByHold: Boolean = false,
    width: Dp = NuvioTheme.marquee.collections.sidePanelWidth,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    BackHandler(onBack = onDismiss)
    LaunchedEffect(initialFocus) {
        initialFocus.requestFocusAfterFrames()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .holdReleaseGuard(enabled = openedByHold)
            .background(tokens.panelVeil)
            .padding(tokens.sidePanelInset),
        contentAlignment = Alignment.CenterEnd
    ) {
        CompactPanel(
            modifier = Modifier
                .width(width)
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            content = content
        )
    }
}

/**
 * [MarqueeSidePanel]'s shape in its own Dialog window ([MarqueeVeilDialog]), for screens where an
 * in-tree panel would be clipped or leave the top bar lit and reachable (Settings, u11 stages 12
 * and 18): [width] wide, `sidePanelInset` from the end, [insetVertical] from the top and bottom,
 * over the same veil. Back calls [onDismissRequest]; [initialFocus] takes focus on open and
 * whenever it changes (a page switch), and the window hands focus back to the launching row when
 * it closes. [fillHeight] false: as tall as its content (at most [insetVertical] from the edges)
 * and placed by [alignment] (centred by default, as [MarqueeSidePanel]'s; the TMDB key panel sits
 * at the top, clear of the keyboard). [openedByHold]: see [MarqueeSidePanel].
 */
@Composable
internal fun MarqueeSidePanelDialog(
    onDismissRequest: () -> Unit,
    width: Dp,
    insetVertical: Dp,
    initialFocus: FocusRequester? = null,
    fillHeight: Boolean = true,
    openedByHold: Boolean = false,
    alignment: Alignment = Alignment.CenterEnd,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    MarqueeVeilDialog(onDismissRequest = onDismissRequest, veil = tokens.panelVeil, initialFocus = initialFocus) {
        CompactPanel(
            modifier = Modifier
                .align(alignment)
                .holdReleaseGuard(enabled = openedByHold)
                .padding(end = tokens.sidePanelInset, top = insetVertical, bottom = insetVertical)
                .width(width)
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
            content = content
        )
    }
}
