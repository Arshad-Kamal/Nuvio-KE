package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.tv.core.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

/**
 * Subtitle delay strip (approved sandbox `u6-player-panels-marquee` stage 8): a centred compact
 * shell with the eyebrow and value, the slider in the OSD seek-bar language and the Reset / Sync
 * Line glass pills. PlayerScreen owns the keys and the focus target (slider, Reset, Sync Line), so
 * the `is*Focused` flags drive the look; the pills keep their focus requesters for Skip Intro's Up.
 */
@Composable
internal fun SubtitleDelayStrip(
    subtitleDelayMs: Int,
    isResetButtonFocused: Boolean,
    isSyncLineButtonFocused: Boolean,
    isSliderFocused: Boolean,
    glow: () -> Color,
    onResetDelay: () -> Unit,
    onOpenSyncByLine: () -> Unit,
    resetFocusRequester: FocusRequester,
    syncLineFocusRequester: FocusRequester,
    onResetFocused: () -> Unit,
    onSyncLineFocused: () -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    CompactPanel(
        modifier = Modifier.width(tokens.stripWidth),
        contentPadding = PaddingValues(
            horizontal = tokens.centredPaddingHorizontal,
            vertical = tokens.centredPaddingVertical
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tokens.contentInset, bottom = tokens.contentInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelEyebrowText(text = stringResource(R.string.player_subtitle_delay))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = formatSubtitleDelay(subtitleDelayMs),
                    style = NuvioTheme.textStyles.panelValueLarge,
                    color = NuvioPrimitives.white
                )
            }
        }
        DelaySlider(
            fraction = subtitleDelayFraction(subtitleDelayMs),
            focused = isSliderFocused,
            glow = glow
        )
        Row(
            modifier = Modifier.padding(horizontal = tokens.contentInset),
            horizontalArrangement = Arrangement.spacedBy(tokens.smallButtonGap)
        ) {
            MarqueeGlassTextButton(
                label = stringResource(R.string.subtitle_delay_reset),
                onClick = onResetDelay,
                height = tokens.smallButtonHeight,
                horizontalPadding = tokens.smallButtonPadding,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
                highlighted = isResetButtonFocused,
                modifier = Modifier
                    .focusRequester(resetFocusRequester)
                    .onFocusChanged { if (it.isFocused) onResetFocused() }
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.player_sync_line),
                onClick = onOpenSyncByLine,
                height = tokens.smallButtonHeight,
                horizontalPadding = tokens.smallButtonPadding,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
                highlighted = isSyncLineButtonFocused,
                modifier = Modifier
                    .focusRequester(syncLineFocusRequester)
                    .onFocusChanged { if (it.isFocused) onSyncLineFocused() }
            )
        }
    }
}

/** Track, zero mark (at 0 ms) and the ringed knob; the knob glows while focused. Both mirror in RTL. */
@Composable
private fun DelaySlider(fraction: Float, focused: Boolean, glow: () -> Color) {
    val tokens = NuvioTheme.marquee.playerPanel
    val osd = NuvioTheme.marquee.osd
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = tokens.contentInset,
                top = tokens.sliderTop,
                end = tokens.contentInset,
                bottom = tokens.sliderBottom
            )
            .height(tokens.sliderHeight)
    ) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val trackHeight = tokens.sliderTrackHeight.toPx()
        drawRoundRect(
            color = tokens.sliderTrack,
            topLeft = Offset(0f, (size.height - trackHeight) / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )
        fun xOf(f: Float) = size.width * (if (rtl) 1f - f else f)
        val markWidth = tokens.zeroMarkWidth.toPx()
        val markHeight = tokens.zeroMarkHeight.toPx()
        drawRoundRect(
            color = tokens.zeroMark,
            topLeft = Offset(xOf(subtitleDelayFraction(0)) - markWidth / 2f, (size.height - markHeight) / 2f),
            size = Size(markWidth, markHeight),
            cornerRadius = CornerRadius(markWidth / 2f)
        )
        val center = Offset(xOf(fraction), size.height / 2f)
        val headRadius = osd.seekThumbSize.toPx() / 2f
        val ringed = headRadius + osd.seekThumbRing.toPx()
        if (focused) {
            val glowColor = glow()
            for ((spread, share) in THUMB_GLOW_LAYERS) {
                drawCircle(glowColor.copy(alpha = osd.seekThumbGlowAlpha * share), ringed + spread.toPx(), center)
            }
        }
        drawCircle(NuvioPrimitives.black, ringed, center)
        drawCircle(NuvioPrimitives.white, headRadius, center)
    }
}

/** Where [delayMs] sits on the slider, 0 (min) to 1 (max), clamped. */
internal fun subtitleDelayFraction(delayMs: Int): Float =
    ((delayMs - SUBTITLE_DELAY_MIN_MS).toFloat() / (SUBTITLE_DELAY_MAX_MS - SUBTITLE_DELAY_MIN_MS).toFloat())
        .coerceIn(0f, 1f)

/** Subtitle delay as "+0.250s" (Spec 25 Q2): the delay strip and the subtitles panel's Delay row. */
internal fun formatSubtitleDelay(delayMs: Int): String =
    String.format(Locale.US, "%+.3fs", delayMs / 1000f)
