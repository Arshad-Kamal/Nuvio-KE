package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.RuntimeMismatchPolicy
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatRuntimeMinutes
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Spec 35 runtime mismatch warning (approved sandbox `spec-35-runtime-warning`, shape A, Arshad
 * 2026-10-18): over the paused first frame, the pause overlay's scrims and a bottom-start block:
 * eyebrow ([title] · S/E · "Check this file"), headline, the two lengths, [sourceLine] +
 * [filename], then Pick another stream (primary, focused on entry) and Play anyway (glass).
 * Back = Pick another stream (PlayerScreen.handleBackPress).
 */
@Composable
internal fun RuntimeMismatchOverlay(
    mismatch: RuntimeMismatchPolicy.Mismatch,
    title: String,
    season: Int?,
    episode: Int?,
    sourceLine: String?,
    filename: String?,
    accent: () -> Color,
    onPickAnother: () -> Unit,
    onPlayAnyway: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val message = marquee.message
    val panel = marquee.playerPanel
    val styles = NuvioTheme.textStyles
    val pickFocus = remember { FocusRequester() }
    LaunchedEffect(mismatch) { pickFocus.requestFocusAfterFrames() }

    val episodeCode = if (season != null && episode != null) {
        stringResource(R.string.season_episode_format, season, episode)
    } else {
        null
    }
    val fileLength = formatRuntimeMinutes(roundedMinutes(mismatch.durationMs)).orEmpty()
    val expectedLength = formatRuntimeMinutes(roundedMinutes(mismatch.expectedRuntimeMs)).orEmpty()
    val eyebrow = listOfNotNull(title.takeIf { it.isNotBlank() }, episodeCode, stringResource(R.string.runtime_mismatch_eyebrow))
        .joinToString(" \u00b7 ")
    val headline = stringResource(
        if (episodeCode != null) R.string.runtime_mismatch_title_episode else R.string.runtime_mismatch_title_movie
    )
    val body = if (episodeCode != null) {
        stringResource(R.string.runtime_mismatch_body_episode, fileLength, episodeCode, expectedLength)
    } else {
        stringResource(R.string.runtime_mismatch_body_movie, fileLength, expectedLength)
    }
    val source = listOfNotNull(sourceLine, filename?.takeIf { it.isNotBlank() }).joinToString(" \u00b7 ")

    Box(modifier = modifier.fillMaxSize()) {
        MarqueeScrims(
            leftStops = panel.pauseScrimStartStops,
            bottomStops = panel.pauseScrimBottomStops,
            drawTopScrim = false
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = panel.pauseBlockBottom)
                .width(message.width)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(message.gap)
        ) {
            MarqueeEyebrow(text = eyebrow, accent = accent)
            Text(text = headline, style = styles.stateTitle, color = NuvioTheme.colors.TextPrimary)
            Text(
                text = body,
                style = styles.bodyHome,
                color = marquee.onImageBody,
                modifier = Modifier.widthIn(max = message.bodyWidth)
            )
            if (source.isNotBlank()) {
                Text(
                    text = source,
                    style = styles.caption,
                    color = marquee.onImage3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.padding(top = message.actionTop),
                horizontalArrangement = Arrangement.spacedBy(marquee.detail.actionGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarqueePrimaryButton(
                    label = stringResource(R.string.runtime_mismatch_pick_another),
                    icon = null,
                    onClick = onPickAnother,
                    focusRequester = pickFocus,
                    glowColor = accent
                )
                MarqueeGlassTextButton(
                    label = stringResource(R.string.runtime_mismatch_play_anyway),
                    onClick = onPlayAnyway
                )
            }
        }
    }
}

/** Whole minutes, rounded, at least 1 (the formatter shows nothing for 0). */
private fun roundedMinutes(ms: Long): Int = ((ms + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
