package com.nuvio.tv.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.scout.ScoutRating
import com.nuvio.tv.core.scout.isTv
import com.nuvio.tv.core.stream.SourcePrefetchSignal
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.ui.components.marquee.MarqueeCreditLine
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassButton
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTitle
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.components.marquee.titleTypeEyebrow
import com.nuvio.tv.ui.components.marquee.topAward
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.extractYearText
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.formatPlaybackClock
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.util.requestFocusAfterFrames

private const val MAX_META_GENRES = 2

/**
 * Detail first frame, info column (Marquee spec §Screens "Detail as decided at the U2 slice"):
 * eyebrow (type · seasons · status · "Part of" the TMDB collection, ambient accent), title or
 * logo, one meta line, MDBList's ratings line while reserved (Spec 105 C1: while it shows scores the meta
 * line drops its IMDb / TMDB), the reserved source line ("Last played" when it applies), a 3-line
 * description (2 lines while the ratings line is reserved, Spec 105 D27, so the episodes band keeps its place;
 * focusable when truncated: OK opens the About sheet), the credit line (+ top award),
 * then the actions: primary (Resume shows the position) + glass Library / Watched (movies) / Rate
 * (Spec 100 S4, [DetailRateControl]) / Trailer / About (opens the About sheet; data-additions-marquee
 * st2, st4).
 *
 * Focus: the primary takes focus on entry and on [restorePlayFocusToken]; Up from the actions
 * reaches the description only when it is truncated (else Up is blocked), Up from the
 * description is blocked. Every action reports [onActionFocused]; the primary also
 * [onPlayFocused] (it feeds the trailer idle timer).
 *
 * `DetailSkeleton` (DetailStates.kt) mirrors this stack's order, line boxes and gaps; change both.
 */
@Composable
internal fun DetailInfoColumn(
    meta: Meta,
    isSeries: Boolean,
    seasonCount: Int,
    collectionName: String?,
    nextToWatch: NextToWatch?,
    accent: () -> Color,
    sourceSignal: SourcePrefetchSignal?,
    showImdbRating: Boolean,
    showFullReleaseDate: Boolean,
    isInLibrary: Boolean,
    mdbListRatings: DetailMdbListRatingsUi,
    mdbListChip: Int?,
    showListsButton: Boolean,
    onListsClick: () -> Unit,
    listsFocusRequester: FocusRequester,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    trailerAvailable: Boolean,
    playFocusRequester: FocusRequester,
    restorePlayFocusToken: Int,
    onPlayClick: () -> Unit,
    onPlayLongPress: (() -> Unit)?,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onToggleMovieWatched: () -> Unit,
    onTrailerClick: () -> Unit,
    onActionFocused: () -> Unit,
    onPlayFocused: () -> Unit,
    onShowAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val descriptionFocusRequester = remember { FocusRequester() }
    val description = meta.description?.takeIf { it.isNotBlank() }
    var descriptionTruncated by rememberSaveable(description) { mutableStateOf(false) }
    val upFromActions = if (descriptionTruncated) descriptionFocusRequester else FocusRequester.Cancel

    LaunchedEffect(restorePlayFocusToken) {
        if (restorePlayFocusToken > 0) playFocusRequester.requestFocusAfterFrames()
    }

    Column(
        modifier = modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = detail.infoTop)
            .width(detail.infoWidth),
        verticalArrangement = Arrangement.spacedBy(detail.infoGap)
    ) {
        // The collection arrives with TMDB's collection fetch (movies, TMDB on): the eyebrow grows then.
        MarqueeEyebrow(
            text = titleTypeEyebrow(meta.apiType, isSeries, seasonCount, meta.status, collectionName),
            accent = accent
        )
        MarqueeLogoTitle(
            title = meta.name,
            logo = meta.logo,
            logoHeight = detail.logoMaxHeight,
            logoMaxWidth = detail.logoMaxWidth,
            textStyle = NuvioTheme.textStyles.displayHero
        )
        val ratings = mdbListRatings.shown
        DetailMetaLine(
            meta = meta,
            showImdbRating = showImdbRating && ratings.isEmpty(),
            showFullReleaseDate = showFullReleaseDate
        )
        if (mdbListRatings.reserved) MdbListRatingsLine(items = ratings)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(detail.sourceLineHeight),
            contentAlignment = Alignment.CenterStart
        ) {
            DetailSourceLine(signal = sourceSignal, accent = accent, listsChip = mdbListChip)
        }
        if (description != null) {
            DetailDescription(
                text = description,
                truncated = descriptionTruncated,
                onTruncatedChange = { descriptionTruncated = it },
                focusRequester = descriptionFocusRequester,
                downFocusRequester = playFocusRequester,
                onFocused = onActionFocused,
                onClick = onShowAbout,
                maxLines = if (mdbListRatings.reserved) RATINGS_DESCRIPTION_MAX_LINES else detail.descriptionMaxLines
            )
        }
        MarqueeCreditLine(
            directors = meta.director,
            writers = meta.writer,
            isSeries = isSeries,
            reserveHeight = true,
            award = topAward(meta.awards)
        )
        Row(
            modifier = Modifier.padding(top = detail.actionsExtraTop),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(detail.actionGap)
        ) {
            fun Modifier.action(onFocused: () -> Unit = onActionFocused): Modifier = this
                .onFocusChanged { if (it.isFocused) onFocused() }
                .focusProperties { up = upFromActions }

            MarqueePrimaryButton(
                label = nextToWatch?.displayText ?: stringResource(R.string.hero_play),
                icon = Icons.Rounded.PlayArrow,
                onClick = onPlayClick,
                focusRequester = playFocusRequester,
                suffix = primarySuffix(nextToWatch),
                glowColor = accent,
                onLongPress = onPlayLongPress,
                modifier = Modifier.action(onFocused = {
                    onActionFocused()
                    onPlayFocused()
                })
            )
            MarqueeGlassButton(
                icon = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                contentDescription = stringResource(
                    if (isInLibrary) R.string.hero_remove_from_library else R.string.hero_add_to_library
                ),
                onClick = onToggleLibrary,
                onLongPress = onLibraryLongPress,
                modifier = Modifier.action()
            )
            // Spec 105 C2: MDBList's lists, right after Library, while MDBList is connected.
            if (showListsButton) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.detail_mdblist_lists_button),
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    onClick = onListsClick,
                    modifier = Modifier.focusRequester(listsFocusRequester).action()
                )
            }
            if (meta.apiType == "movie") {
                MarqueeGlassButton(
                    icon = if (isMovieWatched) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = stringResource(
                        if (isMovieWatched) R.string.hero_mark_unwatched else R.string.hero_mark_watched
                    ),
                    onClick = onToggleMovieWatched,
                    enabled = !isMovieWatchedPending,
                    modifier = Modifier.action()
                )
            }
            DetailRateControl(
                meta = meta,
                accent = accent,
                actionModifier = { it.action() }
            )
            if (trailerAvailable) {
                MarqueeGlassButton(
                    icon = Icons.Default.Movie,
                    contentDescription = stringResource(R.string.hero_play_trailer),
                    onClick = onTrailerClick,
                    modifier = Modifier.action()
                )
            }
            // About opens the About sheet (every title has facts to show); OK on a truncated
            // description does too.
            MarqueeGlassButton(
                icon = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.about_title),
                onClick = onShowAbout,
                modifier = Modifier.action()
            )
        }
    }
}

// Rate control sizes, mockup `s100-scout-lite` b2 (`.s100rate`): the open pill keeps the glass button's height.
private val RATE_PILL_PADDING = 6.dp
private val RATE_OPTION_HEIGHT = 34.dp
private val RATE_OPTION_PADDING = 14.dp
private val RATE_OPTION_ICON = 17.dp
private val RATE_OPTION_ICON_GAP = 8.dp
private val RATE_CURRENT_RING = 1.5.dp
private const val LOVE_BACK_THUMB_ALPHA = 0.55f

/**
 * Rate (Spec 100 S4, D3; mockup `s100-scout-lite` b1-b3), shown only while [ScoutRateViewModel.visible]: a
 * glass icon button, an outline thumb until the profile rates the title, then its choice in [accent]. OK opens
 * Not for me / Like / Love in place, focus on the current choice (else Like); OK saves and closes, OK on the
 * current choice clears it, Back closes without a change, and focus leaving the choices closes them too. The
 * rating feeds Picked for you. Kept in this file (the lane's file fence); RateButton repeats
 * MarqueeGlassButton's look because that button takes no tint or custom icon.
 */
@Composable
private fun DetailRateControl(
    meta: Meta,
    accent: () -> Color,
    actionModifier: (Modifier) -> Modifier
) {
    val viewModel: ScoutRateViewModel = hiltViewModel()
    // Typed as history types it (meta.apiType, what playback records), not by the layout's isSeries, which a
    // movie with a videos list also sets: a mistyped rating would never meet the title's history (D6).
    val tv = meta.apiType.isTv()
    LaunchedEffect(meta.id, meta.imdbId, tv, meta.name) { viewModel.bind(meta.id, meta.imdbId, tv, meta.name) }
    val rating by viewModel.rating.collectAsState()
    val visible by viewModel.visible.collectAsState()
    var open by remember(meta.id) { mutableStateOf(false) }
    var refocusToken by remember { mutableIntStateOf(0) }
    val buttonFocus = remember { FocusRequester() }
    val optionFocus = remember { ScoutRating.entries.associateWith { FocusRequester() } }

    fun close(refocus: Boolean) {
        open = false
        if (refocus) refocusToken++
    }
    BackHandler(enabled = open) { close(refocus = true) }
    LaunchedEffect(refocusToken) {
        if (refocusToken > 0) buttonFocus.requestFocusAfterFrames()
    }

    // Hidden while open (key cleared, switch turned off): closed, so Back is not held by an unseen pill.
    LaunchedEffect(visible) { if (!visible) open = false }
    if (!visible) return
    if (!open) {
        RateButton(
            rating = rating,
            accent = accent,
            onClick = { open = true },
            modifier = actionModifier(Modifier.focusRequester(buttonFocus))
        )
        return
    }
    val current = rating
    LaunchedEffect(Unit) { optionFocus.getValue(current ?: ScoutRating.LIKE).requestFocusAfterFrames() }
    var hadFocus by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .height(NuvioTheme.marquee.glassButton.size)
            .background(NuvioTheme.marquee.glassFill, CircleShape)
            .padding(horizontal = RATE_PILL_PADDING)
            .onFocusChanged {
                if (it.hasFocus) hadFocus = true else if (hadFocus) close(refocus = false)
            },
        horizontalArrangement = Arrangement.spacedBy(RATE_PILL_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScoutRating.entries.forEach { option ->
            RateOption(
                option = option,
                current = option == current,
                accent = accent,
                onClick = {
                    viewModel.choose(option)
                    close(refocus = true)
                },
                modifier = actionModifier(Modifier.focusRequester(optionFocus.getValue(option)))
            )
        }
    }
}

/** The closed Rate button: [MarqueeGlassButton]'s look, the icon in [accent] once rated (b3). */
@Composable
private fun RateButton(rating: ScoutRating?, accent: () -> Color, onClick: () -> Unit, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val description = if (rating == null) {
        stringResource(R.string.detail_rate)
    } else {
        stringResource(R.string.detail_rate_current, stringResource(rating.label()))
    }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(marquee.glassButton.size)
            .semantics { contentDescription = description },
        interactionSource = interaction,
        scale = IconButtonDefaults.scale(focusedScale = 1f),
        shape = IconButtonDefaults.shape(shape = CircleShape),
        colors = IconButtonDefaults.colors(
            containerColor = marquee.glassFill,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        )
    ) {
        RateIcon(
            rating = rating,
            size = marquee.glassButton.iconSize,
            tint = if (rating != null && !focused) accent() else LocalContentColor.current
        )
    }
}

/** One choice of the open pill: transparent, white fill focused, the current choice ringed in [accent]. */
@Composable
private fun RateOption(
    option: ScoutRating,
    current: Boolean,
    accent: () -> Color,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val ring = if (current) Border(BorderStroke(RATE_CURRENT_RING, accent()), shape = CircleShape) else Border.None
    Surface(
        onClick = onClick,
        modifier = modifier.height(RATE_OPTION_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(border = ring, focusedBorder = ring, pressedBorder = ring)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = RATE_OPTION_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RateIcon(rating = option, size = RATE_OPTION_ICON, tint = LocalContentColor.current)
            Spacer(modifier = Modifier.width(RATE_OPTION_ICON_GAP))
            Text(
                text = stringResource(option.label()),
                style = NuvioTheme.textStyles.panelButtonSmall,
                maxLines = 1
            )
        }
    }
}

/** Outline thumb (unrated), thumb down, thumb up, or Love's two thumbs (the back one offset and faded). */
@Composable
private fun RateIcon(rating: ScoutRating?, size: Dp, tint: Color) {
    when (rating) {
        null -> Icon(Icons.Outlined.ThumbUp, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        ScoutRating.NOT_FOR_ME ->
            Icon(Icons.Filled.ThumbDown, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        ScoutRating.LIKE -> Icon(Icons.Filled.ThumbUp, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        ScoutRating.LOVE -> Box(modifier = Modifier.width(size + size / 3).height(size)) {
            Icon(
                Icons.Filled.ThumbUp,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.offset(x = size / 3).size(size).alpha(LOVE_BACK_THUMB_ALPHA)
            )
            Icon(Icons.Filled.ThumbUp, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    }
}

private fun ScoutRating.label(): Int = when (this) {
    ScoutRating.NOT_FOR_ME -> R.string.detail_rate_not_for_me
    ScoutRating.LIKE -> R.string.detail_rate_like
    ScoutRating.LOVE -> R.string.detail_rate_love
}

/**
 * The resume position, "7:22 of 12:14" (U5), when resuming with a known duration; null otherwise
 * (the label already says Play/Next).
 */
@Composable
private fun primarySuffix(nextToWatch: NextToWatch?): String? {
    val progress = nextToWatch?.takeIf { it.isResume }?.watchProgress?.takeIf { it.duration > 0L } ?: return null
    val position = progress.resolveResumePosition(progress.duration)
    return stringResource(
        R.string.detail_resume_position,
        formatPlaybackClock(position),
        formatPlaybackClock(progress.duration)
    )
}

/** One line: year · age rating · ≤2 genres · runtime · IMDb (country/language live in the About sheet). */
@Composable
private fun DetailMetaLine(meta: Meta, showImdbRating: Boolean, showFullReleaseDate: Boolean) {
    val parts = detailMetaParts(meta, showFullReleaseDate)
    val context = LocalContext.current
    // The genres give way first on a long line (Spec 118 D4).
    val genres = remember(meta.genres, context) { metaGenres(meta.genres, context) }
    MarqueeMetaRow(
        parts = parts,
        imdb = formatRatingScore(meta.imdbRating?.takeIf { showImdbRating }),
        tmdb = formatRatingScore(meta.tmdbRating?.takeIf { showImdbRating }),
        shrinkPart = genres
    )
}

/** The meta line's parts before IMDb: year, age rating, ≤2 genres, runtime (shared with the scrolled-state block). */
@Composable
internal fun detailMetaParts(meta: Meta, showFullReleaseDate: Boolean): List<String> {
    val context = LocalContext.current
    return remember(meta, showFullReleaseDate, context) {
        buildList {
            extractYearText(meta.type, meta.releaseInfo, meta.released, showFullReleaseDate)
                ?.takeIf { it.isNotBlank() }?.let(::add)
            meta.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            metaGenres(meta.genres, context)?.let(::add)
            formatHeroRuntime(meta.runtime)?.let(::add)
        }
    }
}

/** Up to two localized genres, comma-joined; null when there are none. */
internal fun metaGenres(genres: List<String>, context: android.content.Context): String? =
    genres.filter { it.isNotBlank() }.take(MAX_META_GENRES)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { localizedGenreLabel(context, it) }

/**
 * `bodyDetail` description clamped to [maxLines] in [width]. Truncated text is focusable (also
 * when it fits, with [focusableWhenFits]: the person page with no credits, Spec 29 stage 12): a
 * `glassFill` plate is drawn outside the text box when focused (no reflow) and OK runs [onClick]
 * (Detail: the About sheet). Up is blocked; Down goes to [downFocusRequester].
 */
@Composable
internal fun DetailDescription(
    text: String,
    truncated: Boolean,
    onTruncatedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    maxLines: Int = NuvioTheme.marquee.detail.descriptionMaxLines,
    width: Dp = NuvioTheme.marquee.detail.descriptionWidth,
    focusableWhenFits: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = NuvioTheme.textStyles.bodyDetail,
        color = marquee.onImageBody,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow != truncated) onTruncatedChange(result.hasVisualOverflow)
        },
        modifier = Modifier
            .widthIn(max = width)
            .then(
                if (!truncated && !focusableWhenFits) Modifier else Modifier
                    .focusRequester(focusRequester)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = downFocusRequester
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) onFocused()
                    }
                    .drawBehind {
                        if (!focused) return@drawBehind
                        val dx = detail.descriptionFocusInsetHorizontal.toPx()
                        val dy = detail.descriptionFocusInsetVertical.toPx()
                        val radius = detail.descriptionFocusRadius.toPx()
                        drawRoundRect(
                            color = marquee.glassFill,
                            topLeft = Offset(-dx, -dy),
                            size = Size(size.width + dx * 2, size.height + dy * 2),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
    )
}
