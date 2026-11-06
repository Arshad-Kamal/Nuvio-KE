package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlayerUiState
import com.nuvio.tv.domain.model.isEpisodeWatched
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeCardChip
import com.nuvio.tv.ui.components.marquee.MarqueeCardProgress
import com.nuvio.tv.ui.components.marquee.MarqueeCardTick
import com.nuvio.tv.ui.components.marquee.MarqueeSpinner
import com.nuvio.tv.ui.screens.detail.SeasonChips
import com.nuvio.tv.ui.screens.detail.formatReleaseDate
import com.nuvio.tv.ui.screens.detail.seasonLabel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Episodes panel (approved sandbox `u6-player-panels-marquee` stage 2): the U5 sources panel's
 * shell, eyebrow "EPISODES · SEASON N", Detail's season chips (select on focus, no long press), a
 * rule, then [PlayerEpisodeRow]s. Opens on the episode whose sources were last opened, else the
 * playing one, else the first ([episodesPanelInitialIndex]); a season change does not move focus.
 * OK opens that episode's sources (the shared sources panel, stage 3, drawn by PlayerScreen).
 * [playingProgress] (played fraction, null when unknown) is read once per open.
 */
@Composable
internal fun EpisodesPanel(
    uiState: PlayerUiState,
    playingProgress: () -> Float?,
    accent: () -> Color,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Video) -> Unit,
    modifier: Modifier = Modifier
) {
    val panel = NuvioTheme.marquee.stream
    val marquee = NuvioTheme.marquee
    val episodes = uiState.episodes
    val selectedSeason = uiState.episodesSelectedSeason
    val title = stringResource(R.string.episodes_panel_title)
    val eyebrow = if (selectedSeason != null) "$title · ${seasonLabel(selectedSeason)}" else title
    val playedFraction = remember { playingProgress() }

    CompactPanel(
        modifier = modifier
            .width(panel.panelWidth)
            .fillMaxHeight(),
        contentPadding = PaddingValues(
            start = panel.panelPaddingHorizontal,
            top = panel.panelPaddingTop,
            end = panel.panelPaddingHorizontal
        )
    ) {
        PanelEyebrowText(text = eyebrow, modifier = Modifier.padding(horizontal = panel.eyebrowInset))
        Spacer(modifier = Modifier.height(panel.panelGap))
        if (uiState.episodesAvailableSeasons.isNotEmpty() && selectedSeason != null) {
            val selectedChipFocusRequester = remember { FocusRequester() }
            SeasonChips(
                seasons = uiState.episodesAvailableSeasons,
                selectedSeason = selectedSeason,
                onSeasonSelected = onSeasonSelected,
                onSeasonLongPress = null,
                onSeasonFocused = {},
                selectedChipFocusRequester = selectedChipFocusRequester,
                contentPadding = PaddingValues()
            )
            Spacer(modifier = Modifier.height(panel.panelGap))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RULE_HEIGHT)
                .background(panel.rule)
        )
        Spacer(modifier = Modifier.height(panel.panelGap))

        when {
            uiState.isLoadingEpisodes -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = panel.messagePaddingVertical),
                contentAlignment = Alignment.Center
            ) {
                MarqueeSpinner()
            }
            uiState.episodesError != null || episodes.isEmpty() -> Text(
                text = uiState.episodesError ?: stringResource(R.string.episodes_panel_no_episodes),
                style = NuvioTheme.textStyles.panelBody,
                color = marquee.onImage2,
                modifier = Modifier.padding(horizontal = panel.eyebrowInset)
            )
            else -> EpisodesList(
                uiState = uiState,
                playedFraction = playedFraction,
                accent = accent,
                onEpisodeSelected = onEpisodeSelected
            )
        }
    }
}

@Composable
private fun EpisodesList(
    uiState: PlayerUiState,
    playedFraction: Float?,
    accent: () -> Color,
    onEpisodeSelected: (Video) -> Unit
) {
    val episodes = uiState.episodes
    val listState = rememberLazyListState()
    val initialFocusRequester = remember { FocusRequester() }
    val lastOpenedIndex = remember(episodes, uiState.episodeStreamsForVideoId) {
        val targetId = uiState.episodeStreamsForVideoId
        if (targetId.isNullOrBlank()) -1 else episodes.indexOfFirst { it.id == targetId }
    }
    val playingIndex = remember(episodes, uiState.currentSeason, uiState.currentEpisode) {
        episodes.indexOfFirst { it.season == uiState.currentSeason && it.episode == uiState.currentEpisode }
    }
    val initialIndex = episodesPanelInitialIndex(lastOpenedIndex, playingIndex)
    // Once per list (it is built each time the panel opens or returns from an episode's
    // sources), not on a season change: a season picked on the chips keeps focus on the chips.
    LaunchedEffect(Unit) {
        listState.scrollToItem(initialIndex)
        initialFocusRequester.requestFocusAfterFrames(frames = 1)
    }
    // A season picked on the chips: the list (new keys, so the state keeps the old index) starts
    // at its playing episode, else the top; focus stays on the chips.
    var shownSeason by remember { mutableStateOf(uiState.episodesSelectedSeason) }
    LaunchedEffect(uiState.episodesSelectedSeason) {
        if (uiState.episodesSelectedSeason == shownSeason) return@LaunchedEffect
        shownSeason = uiState.episodesSelectedSeason
        listState.scrollToItem(playingIndex.coerceAtLeast(0))
    }

    val tokens = NuvioTheme.marquee.stream
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(tokens.rowGap),
        contentPadding = PaddingValues(bottom = tokens.panelPaddingHorizontal)
    ) {
        // Addon ids are untrusted, so the index keeps the key unique within the season.
        itemsIndexed(episodes, key = { index, episode -> "$index:${episode.id}" }) { index, episode ->
            val season = episode.season
            val number = episode.episode
            val isPlaying = index == playingIndex
            val isWatched = season != null && number != null &&
                isEpisodeWatched(uiState.episodeWatchProgressMap, uiState.watchedEpisodeKeys, season, number)
            PlayerEpisodeRow(
                episode = episode,
                isPlaying = isPlaying,
                isWatched = isWatched,
                blurStill = uiState.blurUnwatchedEpisodes && !isWatched,
                progress = playedFraction.takeIf { isPlaying },
                accent = accent,
                onClick = { onEpisodeSelected(episode) },
                modifier = if (index == initialIndex) Modifier.focusRequester(initialFocusRequester) else Modifier
            )
        }
    }
}

/**
 * One episode (stage 2, `.er`): the still (E-chip, watched tick, accent progress while playing),
 * title, meta ("Playing" tag, runtime, date) and a 2-line overview. Focus = glass fill + inset
 * ring, no scale, as the source rows.
 */
@Composable
private fun PlayerEpisodeRow(
    episode: Video,
    isPlaying: Boolean,
    isWatched: Boolean,
    blurStill: Boolean,
    progress: Float?,
    accent: () -> Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.playerPanel
    val styles = NuvioTheme.textStyles
    val context = LocalContext.current
    val title = remember(episode.title, context) {
        episode.title.localizeEpisodeTitle(context).ifBlank { context.getString(R.string.episodes_episode) }
    }
    val meta = remember(episode.runtime, episode.released) {
        listOfNotNull(
            formatHeroRuntime(episode.runtime?.toString()),
            episode.released?.let(::formatReleaseDate)?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }
    val shape = RoundedCornerShape(tokens.episodeRowRadius)
    val ring = Border(BorderStroke(marquee.ringWidth, marquee.ring), inset = -(marquee.ringWidth / 2), shape = shape)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = tokens.episodeRowFill,
            focusedContainerColor = marquee.glassFill,
            pressedContainerColor = marquee.glassFill
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = ring, pressedBorder = ring),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = tokens.episodeRowPaddingHorizontal,
                vertical = tokens.episodeRowPaddingVertical
            ),
            horizontalArrangement = Arrangement.spacedBy(tokens.episodeRowGap),
            verticalAlignment = Alignment.Top
        ) {
            EpisodeStill(
                thumbnail = episode.thumbnail,
                episodeNumber = episode.episode,
                isWatched = isWatched,
                blur = blurStill,
                progress = progress,
                accent = accent
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(tokens.episodeTextGap)
            ) {
                Text(
                    text = title,
                    style = styles.rowTitle,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPlaying || meta.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(tokens.episodeMetaGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPlaying) {
                            Text(
                                text = stringResource(R.string.player_now_playing).uppercase(),
                                style = styles.lastPlayed,
                                color = accent(),
                                maxLines = 1
                            )
                        }
                        if (meta.isNotEmpty()) {
                            Text(text = meta, style = styles.caption, color = marquee.onImage3, maxLines = 1)
                        }
                    }
                }
                episode.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = styles.caption.copy(fontWeight = FontWeight.Normal),
                        color = marquee.onImage2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The 128x72 episode still (`.er .th`): [thumbnail] (blurred when [blur]), the E[episodeNumber]
 * chip, the watched tick and the accent [progress] bar. Also the end prompt's next-episode row.
 */
@Composable
internal fun EpisodeStill(
    thumbnail: String?,
    episodeNumber: Int?,
    isWatched: Boolean = false,
    blur: Boolean = false,
    progress: Float? = null,
    accent: () -> Color = { Color.White }
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(tokens.episodeStillWidth, tokens.episodeStillHeight)
            .clip(RoundedCornerShape(tokens.episodeStillRadius))
            .background(tokens.episodeStillFill)
    ) {
        AsyncImage(
            // Remembered: the panel recomposes through playback state; an unremembered request
            // rebuilt a fresh BlurTransformation per pass.
            model = remember(thumbnail, blur) {
                ImageRequest.Builder(context)
                    .data(thumbnail)
                    .crossfade(true)
                    .apply { if (blur) transformations(BlurTransformation()) }
                    .build()
            },
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        episodeNumber?.let {
            MarqueeCardChip(text = stringResource(R.string.ratings_episode_label, it), inset = tokens.episodeStillInset)
        }
        if (isWatched) {
            MarqueeCardTick(
                contentDescription = stringResource(R.string.episodes_cd_watched),
                inset = tokens.episodeStillInset,
                size = tokens.episodeTickSize,
                iconSize = tokens.episodeTickIconSize
            )
        }
        if (progress != null) {
            MarqueeCardProgress(
                fraction = progress,
                fill = accent(),
                inset = tokens.episodeStillInset,
                bottom = tokens.episodeProgressBottom
            )
        }
    }
}

/** The row the panel opens on: the episode whose sources were last opened, else the playing one, else the first. */
internal fun episodesPanelInitialIndex(lastOpenedIndex: Int, playingIndex: Int): Int = when {
    lastOpenedIndex >= 0 -> lastOpenedIndex
    playingIndex >= 0 -> playingIndex
    else -> 0
}

private val RULE_HEIGHT = 1.dp
