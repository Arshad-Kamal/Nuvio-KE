package com.nuvio.tv.ui.components

import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.unit.dp

/**
 * Loads a raw SVG resource as a [Painter] at an explicit target size.
 *
 * G6 consolidation home (2026-08-30): previously six private/near-identical
 * copies (SettingsDesignSystem — this body — MainActivity,
 * ModernSidebarBlurPanel, HeroSection, PlayerScreen, LicensesAttributionsScreen, the last
 * deleted in Spec 31 S4)
 * with per-site drift: two copies rebuilt the ImageRequest every recomposition
 * (no `remember`), sizes varied by accident of copy (xl / xxxl / 24.dp default),
 * and crossfade(false) was set in some copies only — equivalent to the default,
 * so unifying loses nothing. Folded sites gained the `remember` wrapper:
 * same painter, stable request.
 */
@Composable
internal fun rememberRawSvgPainter(
    @RawRes rawIconRes: Int,
    targetSize: Dp = 24.dp
): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { targetSize.roundToPx() }
    val request = remember(rawIconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}
