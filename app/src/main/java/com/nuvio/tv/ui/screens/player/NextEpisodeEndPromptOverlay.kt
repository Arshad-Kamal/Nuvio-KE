package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.NextEpisodeInfo
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * The "Continue watching?" end prompt (approved sandbox `u6-player-panels-marquee`
 * stage 16): a centred compact panel over the dimmed last frame. Eyebrow = the as-built question,
 * rule, the as-built message, the next-episode row (still + E-chip, "Next Episode", title, S/E),
 * then Yes (focused on entry) and "No, return to details" as small glass buttons. Back returns to
 * details (PlayerScreen.handleBackPress).
 */
@Composable
fun NextEpisodeEndPromptOverlay(
    nextEpisode: NextEpisodeInfo,
    onContinue: () -> Unit,
    onReturnToDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val continueFocusRequester = remember { FocusRequester() }
    val returnFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(nextEpisode.videoId) {
        focusManager.clearFocus(force = true)
        continueFocusRequester.requestFocusAfterFrames(frames = 3)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.endPromptDim)
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        CompactPanel(
            modifier = Modifier
                .width(tokens.endPromptWidth)
                .focusGroup(),
            contentPadding = PaddingValues(
                horizontal = tokens.centredPaddingHorizontal,
                vertical = tokens.centredPaddingVertical
            )
        ) {
            val inset = tokens.contentInset
            PanelEyebrow(text = stringResource(R.string.player_next_episode_prompt_title))
            Text(
                text = stringResource(R.string.player_next_episode_prompt_message),
                style = NuvioTheme.textStyles.bodyHome,
                color = tokens.endPromptBody,
                modifier = Modifier.padding(start = inset, end = inset, bottom = tokens.endPromptBodyBottom)
            )
            NextEpisodeRow(
                nextEpisode = nextEpisode,
                modifier = Modifier.padding(
                    start = tokens.endPromptRowMargin,
                    end = tokens.endPromptRowMargin,
                    bottom = tokens.endPromptRowBottom
                )
            )
            Row(
                modifier = Modifier.padding(horizontal = inset),
                horizontalArrangement = Arrangement.spacedBy(tokens.smallButtonGap)
            ) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.player_next_episode_prompt_yes),
                    onClick = onContinue,
                    height = tokens.smallButtonHeight,
                    horizontalPadding = tokens.smallButtonPadding,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall,
                    modifier = Modifier
                        .focusRequester(continueFocusRequester)
                        .focusProperties { right = returnFocusRequester }
                )
                MarqueeGlassTextButton(
                    label = stringResource(R.string.player_next_episode_prompt_no),
                    onClick = onReturnToDetails,
                    height = tokens.smallButtonHeight,
                    horizontalPadding = tokens.smallButtonPadding,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall,
                    modifier = Modifier
                        .focusRequester(returnFocusRequester)
                        .focusProperties { left = continueFocusRequester }
                )
            }
        }
    }
}

/** The next episode (not focusable): still + E-chip, "Next Episode", title, S/E (other types: title only). */
@Composable
private fun NextEpisodeRow(nextEpisode: NextEpisodeInfo, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.playerPanel
    val styles = NuvioTheme.textStyles
    val episodeCode = if (nextEpisode.isOtherType) {
        null
    } else {
        stringResource(R.string.season_episode_format, nextEpisode.season, nextEpisode.episode)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.episodeRowRadius))
            .background(tokens.episodeRowFill)
            .padding(horizontal = tokens.episodeRowPaddingHorizontal, vertical = tokens.episodeRowPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(tokens.episodeRowGap),
        verticalAlignment = Alignment.Top
    ) {
        EpisodeStill(
            thumbnail = nextEpisode.thumbnail,
            episodeNumber = nextEpisode.episode.takeUnless { nextEpisode.isOtherType }
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(tokens.episodeTextGap)
        ) {
            Text(
                text = stringResource(R.string.next_episode_label).uppercase(),
                style = styles.lastPlayed,
                color = marquee.onImage3,
                maxLines = 1
            )
            Text(
                text = nextEpisode.title,
                style = styles.rowTitle,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (episodeCode != null) {
                Text(text = episodeCode, style = styles.caption, color = marquee.onImage3, maxLines = 1)
            }
        }
    }
}
