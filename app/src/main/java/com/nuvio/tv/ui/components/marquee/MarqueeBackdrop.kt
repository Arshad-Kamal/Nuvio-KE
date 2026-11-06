package com.nuvio.tv.ui.components.marquee

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** Backdrop focal point ~70% 30% (Marquee spec §Screens "Home"; Home, Search, Stream, loading). */
internal val BACKDROP_FOCAL = BiasAlignment(horizontalBias = 0.4f, verticalBias = -0.4f)

/**
 * Full-bleed backdrop of [url], decoded at display size and crossfading (`overlay`) on change,
 * cropped around [focal]. [alpha] is read in the draw layer only (e.g. fading out under video).
 */
@Composable
internal fun MarqueeBackdropImage(
    url: String?,
    focal: Alignment,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f }
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val imageModel = remember(context, url, metrics.widthPixels, metrics.heightPixels) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(width = metrics.widthPixels, height = metrics.heightPixels)
                .build()
        }
    }
    Crossfade(
        targetState = imageModel,
        animationSpec = tween(NuvioTheme.motion.durations.overlay),
        modifier = modifier,
        label = "marqueeBackdrop"
    ) { model ->
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                // At most two full-screen layers alive during a crossfade (Shield rules);
                // Auto avoids an offscreen buffer for a single opaque image.
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Auto
                    this.alpha = alpha()
                },
            contentScale = ContentScale.Crop,
            alignment = focal
        )
    }
}

/**
 * Backdrop scrims: horizontal [leftStops] (mirrored in RTL) + vertical [bottomStops], each a
 * (fraction, black alpha) list, plus `media.scrimTop` when [drawTopScrim]. One draw pass;
 * [alpha] is read at draw time (e.g. fading out under a trailer) and costs no layer.
 */
@Composable
internal fun BoxScope.MarqueeScrims(
    leftStops: List<Pair<Float, Float>>,
    bottomStops: List<Pair<Float, Float>>,
    drawTopScrim: Boolean,
    alpha: () -> Float = { 1f }
) {
    val marquee = NuvioTheme.marquee
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawWithCache {
                val black = NuvioPrimitives.black
                val left = Brush.horizontalGradient(
                    *leftStops
                        .map { (at, alpha) -> (if (isRtl) 1f - at else at) to black.copy(alpha = alpha) }
                        .sortedBy { it.first }
                        .toTypedArray()
                )
                val bottom = Brush.verticalGradient(
                    *bottomStops.map { (at, alpha) -> at to black.copy(alpha = alpha) }.toTypedArray()
                )
                val topHeight = marquee.scrimTopHeight.toPx()
                val top = Brush.verticalGradient(
                    listOf(marquee.scrimTopStart, Color.Transparent),
                    endY = topHeight
                )
                onDrawBehind {
                    val a = alpha()
                    if (a <= 0f) return@onDrawBehind
                    drawRect(left, alpha = a)
                    drawRect(bottom, alpha = a)
                    if (drawTopScrim) drawRect(top, size = size.copy(height = topHeight), alpha = a)
                }
            }
    )
}
