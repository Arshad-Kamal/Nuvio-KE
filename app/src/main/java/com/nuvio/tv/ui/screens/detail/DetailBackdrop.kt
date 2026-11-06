package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTitle
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Backdrop focal point ~60% 35% (Marquee spec §Screens "Detail"); the loading state crops the same. */
internal val DETAIL_FOCAL = BiasAlignment(horizontalBias = 0.2f, verticalBias = -0.3f)

/**
 * Detail background: the full-bleed backdrop under `media.scrimLeftDetail` +
 * `media.scrimBottomDetail`, crossfading to Home's `media.scrimLeft` + `media.scrimBottom` as
 * [scrolled] goes 0 -> 1 (the scrolled state; read at draw time), with the trailer player on
 * top. Once the trailer's first frame renders, the backdrop and scrims fade out (`overlay`) so
 * the video replaces them; they return when it stops. The backdrop never dims otherwise.
 */
@Composable
internal fun DetailBackdrop(
    backdropUrl: String?,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isTrailerPlaying: Boolean,
    isTrailerPaused: Boolean,
    showTrailerControls: Boolean,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    scrolled: () -> Float
) {
    var trailerFirstFrame by remember { mutableStateOf(false) }
    // Each start re-arms the fade: the player sets a fresh media item and renders a new first frame.
    LaunchedEffect(isTrailerPlaying) { trailerFirstFrame = false }
    val videoAlpha by animateFloatAsState(
        targetValue = if (isTrailerPlaying && trailerFirstFrame) 1f else 0f,
        animationSpec = tween(NuvioTheme.motion.durations.overlay),
        label = "detailTrailerAlpha"
    )
    val marquee = NuvioTheme.marquee
    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeBackdropImage(
            url = backdropUrl,
            focal = DETAIL_FOCAL,
            modifier = Modifier.fillMaxSize(),
            alpha = { 1f - videoAlpha }
        )
        TrailerPlayer(
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isPlaying = isTrailerPlaying,
            isPaused = isTrailerPaused,
            onFirstFrameRendered = { trailerFirstFrame = true },
            seekRequestToken = if (showTrailerControls) trailerSeekToken else 0,
            seekDeltaMs = if (showTrailerControls) trailerSeekDeltaMs else 0L,
            onRemoteKey = onTrailerControlKey,
            onProgressChanged = onTrailerProgressChanged,
            onEnded = onTrailerEnded,
            modifier = Modifier.fillMaxSize()
        )
        MarqueeScrims(
            leftStops = marquee.scrimLeftDetailStops,
            bottomStops = marquee.scrimBottomDetailStops,
            drawTopScrim = false,
            alpha = { (1f - videoAlpha) * (1f - scrolled()) }
        )
        MarqueeScrims(
            leftStops = marquee.scrimLeftStops,
            bottomStops = marquee.scrimBottomStops,
            drawTopScrim = false,
            alpha = { (1f - videoAlpha) * scrolled() }
        )
    }
}

/**
 * Trailer auto-preview treatment (sandbox u2-detail-marquee stage 10): a bottom-left corner
 * scrim, the title (logo at `trailerLogoHeight` when it loads) and "BACK Stop trailer", fading
 * (`overlay`) with [visible] while the page content fades the other way. [hideTitle] is the VM's
 * hideLogoDuringTrailer, set for Trailer-button playback (its seek overlay owns the screen).
 */
@Composable
internal fun DetailTrailerOverlay(meta: Meta, visible: Boolean, hideTitle: Boolean) {
    val detail = NuvioTheme.marquee.detail
    val fade = tween<Float>(NuvioTheme.motion.durations.overlay)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedVisibility(visible = visible && !hideTitle, enter = fadeIn(fade), exit = fadeOut(fade)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val brush = cornerScrimBrush(
                        width = size.width,
                        height = size.height,
                        angleDegrees = detail.trailerScrimAngle,
                        stops = detail.trailerScrimStops,
                        mirror = isRtl
                    )
                    onDrawBehind { drawRect(brush) }
                }
        ) {
            MarqueeLogoTitle(
                title = meta.name,
                logo = meta.logo,
                logoHeight = detail.trailerLogoHeight,
                logoMaxWidth = detail.logoMaxWidth,
                textStyle = NuvioTheme.textStyles.heroTitle,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = detail.trailerTitleBottom)
                    .widthIn(max = detail.infoWidth)
            )
            DetailKeyHint(
                key = stringResource(R.string.detail_key_back),
                label = stringResource(R.string.detail_hint_stop_trailer),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = detail.hintBottom)
            )
        }
    }
}

/** Bottom-left key hint over a full-screen detail state ("BACK Close"): glass key pill + label. */
@Composable
internal fun DetailKeyHint(key: String, label: String, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(detail.hintGap)
    ) {
        Box(
            modifier = Modifier
                .height(detail.hintKeyHeight)
                .background(marquee.glassFill, CircleShape)
                .padding(horizontal = detail.hintKeyPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(text = key, style = NuvioTheme.textStyles.chip, color = NuvioPrimitives.white, maxLines = 1)
        }
        Text(text = label, style = NuvioTheme.textStyles.cardLabel, color = marquee.onImage2, maxLines = 1)
    }
}

/**
 * A CSS-style `linear-gradient(<angle>deg, ...)` from the bottom-left corner: the gradient line
 * runs through the centre at [angleDegrees] clockwise from "to top", long enough that its ends
 * touch the far corners. [stops] are (fraction, black alpha). [mirror] flips it for RTL.
 */
private fun cornerScrimBrush(
    width: Float,
    height: Float,
    angleDegrees: Float,
    stops: List<Pair<Float, Float>>,
    mirror: Boolean
): Brush {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val dx = sin(radians).toFloat() * (if (mirror) -1f else 1f)
    val dy = -cos(radians).toFloat()
    val half = (abs(width * dx) + abs(height * dy)) / 2f
    val center = Offset(width / 2f, height / 2f)
    val black = NuvioPrimitives.black
    return Brush.linearGradient(
        *stops.map { (at, alpha) -> at to black.copy(alpha = alpha) }.toTypedArray(),
        start = center - Offset(dx * half, dy * half),
        end = center + Offset(dx * half, dy * half)
    )
}
