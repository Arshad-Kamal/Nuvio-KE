package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.SourcePrefetchPhase
import com.nuvio.tv.core.stream.SourcePrefetchSignal
import com.nuvio.tv.ui.components.marquee.MarqueeSourceBadges
import com.nuvio.tv.ui.components.marquee.MarqueeSpinner
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The detail source line (Marquee spec §Screens Detail "Source line"): the autoplay-prefetch
 * signal restyled. READY = `success` dot + "Ready" + tech badges, or the [accent] dot + "Last
 * played" when the winner is the source played last (P10, data-additions st2); SEARCHING = spinner + "Finding
 * best source"; null (MANUAL, nothing found, caught up) renders nothing. The caller reserves
 * the row's height so the actions below never jump.
 */
@Composable
internal fun DetailSourceLine(signal: SourcePrefetchSignal?, accent: () -> Color, modifier: Modifier = Modifier) {
    if (signal == null || signal.phase == SourcePrefetchPhase.EMPTY) return
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val caption = NuvioTheme.textStyles.caption
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        when (signal.phase) {
            SourcePrefetchPhase.SEARCHING -> {
                MarqueeSpinner()
                Spacer(modifier = Modifier.width(detail.sourceIconGap))
                Text(
                    text = stringResource(R.string.detail_source_searching),
                    style = caption,
                    color = marquee.onImage2,
                    maxLines = 1
                )
            }
            SourcePrefetchPhase.READY -> {
                val lastPlayed = signal.lastPlayed
                ReadyDot(if (lastPlayed) accent else null)
                Spacer(modifier = Modifier.width(detail.sourceIconGap))
                Text(
                    text = stringResource(if (lastPlayed) R.string.stream_last_played else R.string.detail_source_ready),
                    style = caption.copy(fontWeight = FontWeight.SemiBold),
                    color = if (lastPlayed) accent() else marquee.readyText,
                    maxLines = 1
                )
                val facts = signal.facts
                if (facts != null) {
                    Spacer(modifier = Modifier.width(detail.sourceLabelGap))
                    MarqueeSourceBadges(
                        facts = facts,
                        fusionBadges = signal.badges,
                        gap = detail.sourceBadgeGap,
                        modifier = Modifier.weight(1f, fill = false),
                        showSize = true
                    )
                }
            }
            SourcePrefetchPhase.EMPTY -> Unit
        }
    }
}

/** 7 dp dot ([color], else `success`) with a soft glow drawn around it (no layer, no blur). */
@Composable
private fun ReadyDot(color: (() -> Color)?) {
    val detail = NuvioTheme.marquee.detail
    Box(
        modifier = Modifier
            .size(detail.readyDotSize)
            .drawBehind {
                val success = color?.invoke() ?: NuvioPrimitives.success
                val core = size.minDimension / 2f
                val glow = core + detail.readyDotGlow.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to success.copy(alpha = 0.7f),
                        (core / glow) to success.copy(alpha = 0.7f),
                        1f to Color.Transparent,
                        center = center,
                        radius = glow
                    ),
                    radius = glow
                )
                drawCircle(color = success, radius = core)
            }
    )
}
