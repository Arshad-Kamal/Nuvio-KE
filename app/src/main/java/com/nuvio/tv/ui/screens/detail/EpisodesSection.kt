package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.data.local.EpisodeOptionsStyle
import com.nuvio.tv.data.local.EpisodeOptionsStylePreference
import com.nuvio.tv.domain.model.isEpisodeWatched
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.marquee.MarqueeCard
import com.nuvio.tv.ui.components.marquee.MarqueeCardChip
import com.nuvio.tv.ui.components.marquee.MarqueeCardProgress
import com.nuvio.tv.ui.components.marquee.MarqueeCardTick
import com.nuvio.tv.ui.components.marquee.MarqueeCardTitle
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import java.util.Locale
import kotlinx.coroutines.delay

private const val EPISODE_CARD_CONTENT_TYPE = "episode_card"
private const val EPISODE_SCROLL_REPEAT_THROTTLE_MS = 80L
private const val EPISODE_RESTORE_FALLBACK_MS = 250L
private const val SEASON_SELECT_ON_FOCUS_MS = 150L
private val CARD_BACKING_PAINTER = ColorPainter(NuvioPrimitives.neutral900)

/**
 * First visible card of a (re)built episode row: the restore target, else the scroll target,
 * else the season's last focused card (P48: so Down from the season chips can reach it). A
 * restored or anchored card lands one card in; the first non-null id decides, and an id not in
 * the season starts at 0 (no fall-through to the next source).
 */
internal fun episodeRowInitialIndex(
    episodeIds: List<String>,
    restoreEpisodeId: String?,
    scrollToEpisodeId: String?,
    anchorEpisodeId: String?
): Int = when {
    restoreEpisodeId != null -> episodeIds.indexOf(restoreEpisodeId) - 1
    scrollToEpisodeId != null -> episodeIds.indexOf(scrollToEpisodeId)
    anchorEpisodeId != null -> episodeIds.indexOf(anchorEpisodeId) - 1
    else -> 0
}.coerceAtLeast(0)

/** "Season 2", or "Specials" for season 0. */
@Composable
internal fun seasonLabel(season: Int): String =
    if (season == 0) stringResource(R.string.episodes_specials) else stringResource(R.string.episodes_season, season)

/**
 * Season chips (Marquee spec §Screens "Detail as decided at the U2 slice"): `chipHeight` pills in
 * `cardLabel`, `onImage3` text; selected = `glassFill` + white; focused = white fill + black
 * text, no scale. Focusing a chip selects its season after 150 ms; a long press (held OK or
 * MENU) opens the season options when [onSeasonLongPress] is set (the player's Episodes panel
 * has none). Specials (season 0) sort last. [downFocusRequester] is resolved when focus moves
 * (it follows the last focused card without recomposing anything). [contentPadding] defaults to
 * the TV safe margin (Detail's full-width band).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SeasonChips(
    seasons: List<Int>,
    selectedSeason: Int,
    onSeasonSelected: (Int) -> Unit,
    onSeasonLongPress: ((Int) -> Unit)?,
    onSeasonFocused: (Int) -> Unit,
    selectedChipFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: () -> FocusRequester? = { null },
    isFocusEnabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = NuvioTheme.layout.tvSafeHorizontal)
) {
    val detail = NuvioTheme.marquee.detail
    val sortedSeasons = remember(seasons) {
        seasons.filter { it > 0 }.sorted() + seasons.filter { it == 0 }
    }
    val initialSeasonIndex = remember(sortedSeasons, selectedSeason) {
        sortedSeasons.indexOf(selectedSeason).coerceAtLeast(0)
    }
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialSeasonIndex)

    var suppressFocusSwitch by remember { mutableStateOf(false) }
    var pendingSeason by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingSeason) {
        val target = pendingSeason ?: return@LaunchedEffect
        delay(SEASON_SELECT_ON_FOCUS_MS)
        onSeasonSelected(target)
        pendingSeason = null
    }

    LaunchedEffect(sortedSeasons, selectedSeason) {
        val selectedIndex = sortedSeasons.indexOf(selectedSeason)
        if (selectedIndex < 0) return@LaunchedEffect
        if (lazyListState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }) return@LaunchedEffect
        suppressFocusSwitch = true
        lazyListState.scrollToItem(selectedIndex)
        suppressFocusSwitch = false
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer(
                // W5.1: the selected chip is a lazy item; only resolve while composed.
                if (lazyListState.layoutInfo.visibleItemsInfo.any { it.key == selectedSeason }) {
                    selectedChipFocusRequester
                } else {
                    FocusRequester.Default
                }
            )
            .focusGroup(),
        state = lazyListState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(detail.chipGap)
    ) {
        items(sortedSeasons, key = { it }) { season ->
            val isSelected = season == selectedSeason
            SeasonChip(
                label = seasonLabel(season),
                isSelected = isSelected,
                onClick = { onSeasonSelected(season) },
                onLongPress = onSeasonLongPress?.let { { it(season) } },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedChipFocusRequester) else Modifier)
                    .focusProperties {
                        canFocus = isFocusEnabled
                        if (isSelected) downFocusRequester()?.let { down = it }
                        if (upFocusRequester != null) up = upFocusRequester
                        // Left/Right stay in the row at its ends (start/end follow RTL).
                        if (season == sortedSeasons.first()) start = FocusRequester.Cancel
                        if (season == sortedSeasons.last()) end = FocusRequester.Cancel
                    }
                    .onFocusChanged {
                        if (!it.isFocused) return@onFocusChanged
                        onSeasonFocused(season)
                        if (!isSelected && !suppressFocusSwitch) pendingSeason = season
                    }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val longPress = rememberMarqueeLongPress()
    Surface(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .height(detail.chipHeight)
            .then(longPress.modifier(onLongPress)),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) marquee.glassFill else Color.Transparent,
            contentColor = if (isSelected) white else marquee.onImage3,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = detail.chipPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, style = NuvioTheme.textStyles.cardLabel, maxLines = 1)
        }
    }
}

/**
 * The episode cards of one season (208x117 Marquee landscape cards) plus the episode options
 * (long press). Focus: [restoreEpisodeId] + [restoreFocusToken] put focus back on a card (only
 * that card is focusable meanwhile); [scrollToEpisodeId] scrolls to a card once;
 * [anchorEpisodeId] (the season's last focused card, read once when the row is built) is where
 * the row starts otherwise; Up/Down go to [upFocusRequester]/[downFocusRequester] (the latter
 * resolved when focus moves).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodesRow(
    episodes: List<Video>,
    accent: () -> Color,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    episodeRatings: Map<Pair<Int, Int>, Double> = emptyMap(),
    watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
    episodeWatchedPendingKeys: Set<String> = emptySet(),
    blurUnwatchedEpisodes: Boolean = false,
    onEpisodeClick: (Video) -> Unit,
    onEpisodeManualPlayClick: (Video) -> Unit = onEpisodeClick,
    onEpisodeStartFromBeginningClick: (Video) -> Unit = onEpisodeClick,
    onToggleEpisodeWatched: (Video) -> Unit,
    showManualPlayOption: Boolean = false,
    onMarkSeasonWatched: (Int) -> Unit = {},
    onMarkSeasonUnwatched: (Int) -> Unit = {},
    isSeasonFullyWatched: Boolean = false,
    selectedSeason: Int = 1,
    onMarkPreviousEpisodesWatched: (Video) -> Unit = {},
    upFocusRequester: FocusRequester,
    downFocusRequester: () -> FocusRequester? = { null },
    episodeFocusRequesters: MutableMap<String, FocusRequester> = mutableMapOf(),
    restoreEpisodeId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onEpisodeFocused: (episode: Video) -> Unit = {},
    scrollToEpisodeId: String? = null,
    onScrollToEpisodeHandled: () -> Unit = {},
    anchorEpisodeId: () -> String? = { null }
) {
    val detail = NuvioTheme.marquee.detail
    val cardGap = NuvioTheme.marquee.home.cardGap
    val dedupedEpisodes = remember(episodes) { episodes.distinctBy { it.id } }
    val restoreTargetRequester = restoreEpisodeId?.let { episodeFocusRequesters[it] }
    var optionsEpisode by remember { mutableStateOf<Video?>(null) }
    // Device-level presentation pref (see EpisodeOptionsStylePreference); RICH is the
    // approved default, CLASSIC is the pre-existing dialog — flip in Appearance to roll back.
    val context = LocalContext.current
    val episodeOptionsStyleFlow = remember(context) { EpisodeOptionsStylePreference.flow(context) }
    val episodeOptionsStyle by episodeOptionsStyleFlow.collectAsState(initial = EpisodeOptionsStyle.RICH)
    val density = LocalDensity.current
    // A restored/scrolled-to card lands about a third of a card in from the row start.
    val targetOffsetPx = with(density) { (detail.cardWidth * 2f / 3f - cardGap).roundToPx() }
    val rowPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val initialEpisodeIndex = remember(dedupedEpisodes, restoreEpisodeId, scrollToEpisodeId) {
        // Unobserved read: the anchor's source is written on every card move.
        val anchorId = Snapshot.withoutReadObservation { anchorEpisodeId() }
        episodeRowInitialIndex(dedupedEpisodes.map { it.id }, restoreEpisodeId, scrollToEpisodeId, anchorId)
    }
    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialEpisodeIndex,
        prefetchStrategy = rowPrefetchStrategy
    )
    var lastHorizontalKeyRepeatTime by remember { mutableStateOf(0L) }
    val episodeIds = remember(dedupedEpisodes) { dedupedEpisodes.mapTo(mutableSetOf()) { it.id } }
    LaunchedEffect(episodeIds, episodeFocusRequesters) {
        episodeFocusRequesters.keys.retainAll(episodeIds)
    }

    LaunchedEffect(restoreFocusToken, restoreEpisodeId, restoreTargetRequester, dedupedEpisodes) {
        if (restoreFocusToken <= 0 || restoreEpisodeId.isNullOrBlank()) return@LaunchedEffect
        if (dedupedEpisodes.none { it.id == restoreEpisodeId }) {
            delay(EPISODE_RESTORE_FALLBACK_MS)
            onRestoreFocusHandled()
            return@LaunchedEffect
        }
        val index = dedupedEpisodes.indexOfFirst { it.id == restoreEpisodeId }
        if (index >= 0) lazyListState.scrollToItem(index, scrollOffset = -targetOffsetPx)
        val focusRequested = restoreTargetRequester?.requestFocusAfterFrames(frames = 1) == true
        if (!focusRequested) {
            onRestoreFocusHandled()
            return@LaunchedEffect
        }
        delay(EPISODE_RESTORE_FALLBACK_MS)
        onRestoreFocusHandled()
    }

    LaunchedEffect(scrollToEpisodeId, dedupedEpisodes) {
        if (scrollToEpisodeId.isNullOrBlank()) return@LaunchedEffect
        val index = dedupedEpisodes.indexOfFirst { it.id == scrollToEpisodeId }
        if (index < 0) return@LaunchedEffect
        lazyListState.scrollToItem(index, scrollOffset = -targetOffsetPx)
        onScrollToEpisodeHandled()
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val isHorizontalKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                if (
                    isHorizontalKey &&
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.repeatCount > 0
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastHorizontalKeyRepeatTime < EPISODE_SCROLL_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true
                    }
                    lastHorizontalKeyRepeatTime = now
                }
                false
            },
        state = lazyListState,
        contentPadding = PaddingValues(horizontal = NuvioTheme.layout.tvSafeHorizontal),
        horizontalArrangement = Arrangement.spacedBy(cardGap)
    ) {
        items(
            items = dedupedEpisodes,
            key = { it.id },
            contentType = { EPISODE_CARD_CONTENT_TYPE }
        ) { episode ->
            val seasonEp = remember(episode.season, episode.episode) { episode.season?.let { s -> episode.episode?.let { e -> s to e } } }
            val progress = remember(seasonEp, episodeProgressMap) { seasonEp?.let { episodeProgressMap[it] } }
            val isMarkedWatched = remember(seasonEp, watchedEpisodes) { seasonEp?.let { watchedEpisodes.contains(it) } ?: false }
            val episodeFocusRequester = remember(episode.id) { episodeFocusRequesters.getOrPut(episode.id) { FocusRequester() } }
            // Keyed on the Video itself: a meta refresh re-emits episodes under the same ids, and
            // the focused one feeds the scrolled-state block.
            val episodeOnClick = remember(episode) { { onEpisodeClick(episode) } }
            val episodeOnLongPress = remember(episode) { { optionsEpisode = episode } }
            val episodeOnFocused = remember(episode) { { onEpisodeFocused(episode) } }
            val isRestoreTarget = episode.id == restoreEpisodeId
            val episodeOnFocusRestored = remember(isRestoreTarget, onRestoreFocusHandled) {
                if (isRestoreTarget) onRestoreFocusHandled else null
            }
            DetailEpisodeCard(
                episode = episode,
                watchProgress = progress,
                isMarkedWatched = isMarkedWatched,
                blurUnwatched = blurUnwatchedEpisodes,
                accent = accent,
                onClick = episodeOnClick,
                onLongPress = episodeOnLongPress,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                focusRequester = episodeFocusRequester,
                isFocusEnabled = restoreEpisodeId.isNullOrBlank() || isRestoreTarget,
                onFocused = episodeOnFocused,
                onFocusRestored = episodeOnFocusRestored
            )
        }
    }

    optionsEpisode?.let { selectedEpisode ->
        val selectedWatched = selectedEpisode.season?.let { season ->
            selectedEpisode.episode?.let { episode -> isEpisodeWatched(episodeProgressMap, watchedEpisodes, season, episode) }
        } ?: false
        val isPending = episodeWatchedPendingKeys.contains(episodePendingKey(selectedEpisode))
        val firstEpisodeInSeason = dedupedEpisodes.minByOrNull { it.episode ?: Int.MAX_VALUE }
        val hasPreviousEpisodes = selectedEpisode.episode != null &&
            firstEpisodeInSeason?.episode != null &&
            selectedEpisode.episode > firstEpisodeInSeason.episode

        val hasProgress = selectedEpisode.season?.let { season ->
            selectedEpisode.episode?.let { episode ->
                episodeProgressMap[season to episode]?.let { progress ->
                    !progress.isCompleted() && progress.position > 0
                }
            }
        } ?: false
        val imdbRating = selectedEpisode.season?.let { season ->
            selectedEpisode.episode?.let { episode -> episodeRatings[season to episode] }
        }
        val dismissOptions = { optionsEpisode = null }
        val playAction: () -> Unit = {
            onEpisodeClick(selectedEpisode)
            optionsEpisode = null
        }
        val startFromBeginningAction: () -> Unit = {
            onEpisodeStartFromBeginningClick(selectedEpisode)
            optionsEpisode = null
        }
        val playManuallyAction: () -> Unit = {
            onEpisodeManualPlayClick(selectedEpisode)
            optionsEpisode = null
        }
        val toggleWatchedAction: () -> Unit = {
            onToggleEpisodeWatched(selectedEpisode)
            optionsEpisode = null
        }
        val markSeasonWatchedAction: () -> Unit = {
            onMarkSeasonWatched(selectedSeason)
            optionsEpisode = null
        }
        val markSeasonUnwatchedAction: () -> Unit = {
            onMarkSeasonUnwatched(selectedSeason)
            optionsEpisode = null
        }
        val markPreviousWatchedAction: () -> Unit = {
            onMarkPreviousEpisodesWatched(selectedEpisode)
            optionsEpisode = null
        }

        if (episodeOptionsStyle == EpisodeOptionsStyle.RICH) {
            EpisodeOptionsOverlay(
                episode = selectedEpisode,
                imdbRating = imdbRating,
                isWatched = selectedWatched,
                blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                isPending = isPending,
                isSeasonFullyWatched = isSeasonFullyWatched,
                hasPreviousEpisodes = hasPreviousEpisodes,
                hasProgress = hasProgress,
                onDismiss = dismissOptions,
                onPlay = playAction,
                onStartFromBeginning = startFromBeginningAction,
                onPlayManually = playManuallyAction,
                showPlayManually = showManualPlayOption,
                onToggleWatched = toggleWatchedAction,
                onMarkSeasonWatched = markSeasonWatchedAction,
                onMarkSeasonUnwatched = markSeasonUnwatchedAction,
                onMarkPreviousEpisodesWatched = markPreviousWatchedAction
            )
        } else {
            EpisodeOptionsDialog(
                episode = selectedEpisode,
                isWatched = selectedWatched,
                isPending = isPending,
                isSeasonFullyWatched = isSeasonFullyWatched,
                hasPreviousEpisodes = hasPreviousEpisodes,
                hasProgress = hasProgress,
                onDismiss = dismissOptions,
                onPlay = playAction,
                onStartFromBeginning = startFromBeginningAction,
                onPlayManually = playManuallyAction,
                showPlayManually = showManualPlayOption,
                onToggleWatched = toggleWatchedAction,
                onMarkSeasonWatched = markSeasonWatchedAction,
                onMarkSeasonUnwatched = markSeasonUnwatchedAction,
                onMarkPreviousEpisodesWatched = markPreviousWatchedAction
            )
        }
    }
}

/**
 * Episode card (spec §Components "Landscape card", detail size): still (blurred while unwatched
 * when the setting is on), "E3" chip, title, a white tick when watched, a progress bar while in
 * progress (ambient accent when focused). UNAVAILABLE episodes dim their content and carry a
 * top-right chip. Runtime, date, rating and overview live in the scrolled-state block.
 */
@Composable
private fun DetailEpisodeCard(
    episode: Video,
    watchProgress: WatchProgress?,
    isMarkedWatched: Boolean,
    blurUnwatched: Boolean,
    accent: () -> Color,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    upFocusRequester: FocusRequester,
    downFocusRequester: () -> FocusRequester?,
    focusRequester: FocusRequester,
    isFocusEnabled: Boolean,
    onFocused: () -> Unit,
    onFocusRestored: (() -> Unit)?
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val context = LocalContext.current
    val density = LocalDensity.current
    var isFocused by remember { mutableStateOf(false) }
    val isWatched = watchProgress?.isCompleted() == true || isMarkedWatched
    val shouldBlur = blurUnwatched && !isWatched
    val isUnavailable = episode.available == false
    val title = remember(episode.title, context) { episode.title.localizeEpisodeTitle(context) }
    val widthPx = with(density) { detail.cardWidth.roundToPx() }
    val heightPx = with(density) { detail.cardHeight.roundToPx() }
    val thumbnailRequest = remember(context, episode.thumbnail, widthPx, heightPx, shouldBlur) {
        ImageRequest.Builder(context)
            .data(episode.thumbnail)
            .crossfade(true)
            .size(width = widthPx, height = heightPx)
            .apply { if (shouldBlur) transformations(BlurTransformation()) }
            .build()
    }
    // Only the focused card reads the accent, so an accent change recomposes one card.
    val glowColor = if (isFocused) accent().copy(alpha = marquee.glowCardAlpha) else Color.Transparent

    MarqueeCard(
        onClick = onClick,
        onLongPress = onLongPress,
        width = detail.cardWidth,
        height = detail.cardHeight,
        glowColor = glowColor,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = isFocusEnabled
                up = upFocusRequester
                downFocusRequester()?.let { down = it }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    onFocusRestored?.invoke()
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isUnavailable) Modifier.graphicsLayer { alpha = detail.unavailableAlpha } else Modifier)
        ) {
            AsyncImage(
                model = thumbnailRequest,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = CARD_BACKING_PAINTER,
                error = CARD_BACKING_PAINTER,
                fallback = CARD_BACKING_PAINTER
            )
            MarqueeCardTitle(title)
            episode.episode?.let { MarqueeCardChip(stringResource(R.string.ratings_episode_label, it)) }
            when {
                isUnavailable -> MarqueeCardChip(
                    text = stringResource(R.string.episodes_unavailable).uppercase(Locale.getDefault()),
                    color = detail.unavailableText,
                    alignment = Alignment.TopEnd
                )
                isWatched -> MarqueeCardTick(contentDescription = stringResource(R.string.episodes_cd_watched))
            }
            if (watchProgress?.isInProgress() == true) {
                MarqueeCardProgress(
                    fraction = watchProgress.progressPercentage,
                    fill = if (isFocused) accent() else NuvioPrimitives.white
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeOptionsDialog(
    episode: Video,
    isWatched: Boolean,
    isPending: Boolean,
    isSeasonFullyWatched: Boolean = false,
    hasPreviousEpisodes: Boolean = false,
    hasProgress: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    onPlayManually: () -> Unit = {},
    showPlayManually: Boolean = false,
    onToggleWatched: () -> Unit,
    onMarkSeasonWatched: () -> Unit = {},
    onMarkSeasonUnwatched: () -> Unit = {},
    onMarkPreviousEpisodesWatched: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = episode.title.localizeEpisodeTitle(context),
        subtitle = stringResource(R.string.episodes_dialog_subtitle)
    ) {
        PanelActionRow(
            label = if (isWatched) stringResource(R.string.episodes_mark_unwatched) else stringResource(R.string.episodes_mark_watched),
            onClick = onToggleWatched,
            enabled = !isPending,
            focusRequester = primaryFocusRequester
        )

        PanelActionRow(
            label = if (isSeasonFullyWatched) stringResource(R.string.episodes_mark_season_unwatched) else stringResource(R.string.episodes_mark_season_watched),
            onClick = if (isSeasonFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched
        )

        if (hasPreviousEpisodes) {
            PanelActionRow(
                label = stringResource(R.string.episodes_mark_previous_watched),
                onClick = onMarkPreviousEpisodesWatched
            )
        }

        PanelActionRow(
            label = stringResource(R.string.episodes_play),
            onClick = onPlay
        )

        if (showPlayManually) {
            PanelActionRow(
                label = stringResource(R.string.play_manually),
                onClick = onPlayManually
            )
        }

        if (hasProgress) {
            PanelActionRow(
                label = stringResource(R.string.cw_action_start_from_beginning),
                onClick = onStartFromBeginning
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonOptionsDialog(
    season: Int,
    isFullyWatched: Boolean,
    hasPreviousSeasons: Boolean = false,
    onDismiss: () -> Unit,
    onMarkSeasonWatched: () -> Unit,
    onMarkSeasonUnwatched: () -> Unit,
    onMarkPreviousSeasonsWatched: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = seasonLabel(season),
        subtitle = stringResource(R.string.episodes_season_actions)
    ) {
        PanelActionRow(
            label = if (isFullyWatched) stringResource(R.string.episodes_mark_season_unwatched) else stringResource(R.string.episodes_mark_season_watched),
            onClick = if (isFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched,
            focusRequester = primaryFocusRequester
        )

        if (hasPreviousSeasons && season > 0) {
            PanelActionRow(
                label = stringResource(R.string.episodes_mark_previous_seasons_watched),
                onClick = onMarkPreviousSeasonsWatched
            )
        }
    }
}

private fun episodePendingKey(video: Video): String {
    return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
}
