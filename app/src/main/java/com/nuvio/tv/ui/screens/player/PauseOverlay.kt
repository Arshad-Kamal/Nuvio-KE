package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTitle
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.marquee.titleMetaParts
import com.nuvio.tv.ui.screens.detail.DetailKeyHint
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.isSelectKey
import com.nuvio.tv.ui.util.localizeEpisodeTitle

/** Most cast chips the pause overlay shows (stage 11). */
internal const val PAUSE_CAST_MAX = 8

private const val PAUSE_FADE_IN_MS = 250
private const val PAUSE_FADE_OUT_MS = 200
private val WORD_SEPARATOR = Regex("\\s+")

/**
 * Pause overlay (approved sandbox `u6-player-panels-marquee` stages 11-11c): Detail's block over
 * the paused frame (start + bottom scrims), the OSD [clock] top-end, and the [cast] as glass chips
 * (at most [PAUSE_CAST_MAX], the caller caps the list). Nothing takes focus at show: keys reach the
 * player container, which routes them through [pauseOverlayKeyAction].
 *
 * The cast state is hoisted to PlayerScreen and never a PlayerEvent, since every event cancels
 * this overlay (Spec 25 P-6): [castDetailIndex] swaps the block for that person in place;
 * [castFocusRequest] asks that chip for focus once it is composed, then [onCastFocusRequestHandled].
 *
 * [networkChipText] (Spec 33 D7, stage t-pause; null = no chip) is the Network chip under the cast row on
 * the film the network test ran on; OK on it calls [onNetworkChipClick].
 */
@Composable
internal fun PauseOverlay(
    visible: Boolean,
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    runtimeMinutes: Int?,
    genres: String?,
    imdb: String?,
    tmdb: String?,
    description: String?,
    cast: List<MetaCastMember>,
    castDetailIndex: Int?,
    castFocusRequest: Int?,
    accent: () -> Color,
    onCastFocusChanged: (index: Int, focused: Boolean) -> Unit,
    onCastFocusRequestHandled: () -> Unit,
    onCastClick: (index: Int) -> Unit,
    networkChipText: String?,
    networkChipFocusRequester: FocusRequester,
    onNetworkChipFocusChanged: (focused: Boolean) -> Unit,
    onNetworkChipClick: () -> Unit,
    modifier: Modifier = Modifier,
    clock: @Composable () -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val safe = NuvioTheme.layout.tvSafeHorizontal
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(PAUSE_FADE_IN_MS)),
        exit = fadeOut(animationSpec = tween(PAUSE_FADE_OUT_MS)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MarqueeScrims(
                leftStops = tokens.pauseScrimStartStops,
                bottomStops = tokens.pauseScrimBottomStops,
                drawTopScrim = false
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = NuvioTheme.marquee.osd.topInset, end = safe)
            ) {
                clock()
            }
            val anchor = Modifier
                .align(Alignment.BottomStart)
                .padding(start = safe, bottom = tokens.pauseBlockBottom)
            val member = castDetailIndex?.let(cast::getOrNull)
            if (member != null) {
                PauseCastDetail(member = member, accent = accent, modifier = anchor)
            } else {
                PauseDetails(
                    title = title,
                    logo = logo,
                    episodeTitle = episodeTitle,
                    season = season,
                    episode = episode,
                    metaParts = remember(year, runtimeMinutes, genres) { titleMetaParts(year, runtimeMinutes, genres) },
                    imdb = imdb,
                    tmdb = tmdb,
                    description = description,
                    cast = cast,
                    castFocusRequest = castFocusRequest,
                    accent = accent,
                    onCastFocusChanged = onCastFocusChanged,
                    onCastFocusRequestHandled = onCastFocusRequestHandled,
                    onCastClick = onCastClick,
                    networkChipText = networkChipText,
                    networkChipFocusRequester = networkChipFocusRequester,
                    onNetworkChipFocusChanged = onNetworkChipFocusChanged,
                    onNetworkChipClick = onNetworkChipClick,
                    modifier = anchor
                )
            }
        }
    }
}

/**
 * Stage 11's block: eyebrow, logo or title, episode name, the meta line (year, runtime, two genres,
 * IMDb or TMDB; 26/B5), a 3-line plot, then the cast row and the Network chip (Spec 33 D7).
 */
@Composable
private fun PauseDetails(
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    metaParts: List<String>,
    imdb: String?,
    tmdb: String?,
    description: String?,
    cast: List<MetaCastMember>,
    castFocusRequest: Int?,
    accent: () -> Color,
    onCastFocusChanged: (index: Int, focused: Boolean) -> Unit,
    onCastFocusRequestHandled: () -> Unit,
    onCastClick: (index: Int) -> Unit,
    networkChipText: String?,
    networkChipFocusRequester: FocusRequester,
    onNetworkChipFocusChanged: (focused: Boolean) -> Unit,
    onNetworkChipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.playerPanel
    val context = LocalContext.current
    val watching = stringResource(R.string.pause_you_are_watching)
    val eyebrow = if (season != null && episode != null) {
        "$watching \u00b7 " + stringResource(R.string.season_episode_format, season, episode)
    } else {
        watching
    }
    val episodeName = remember(episodeTitle, context) {
        episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
    }
    val lineWidth = Modifier.widthIn(max = tokens.pauseBlockWidth)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(tokens.pauseBlockGap)
    ) {
        MarqueeEyebrow(text = eyebrow, accent = accent, modifier = lineWidth)
        MarqueeLogoTitle(
            title = title,
            logo = logo,
            logoHeight = marquee.home.heroLogoMaxHeight,
            logoMaxWidth = marquee.home.heroLogoMaxWidth,
            textStyle = NuvioTheme.textStyles.heroTitle,
            modifier = lineWidth
        )
        if (episodeName != null) {
            Text(
                text = episodeName,
                style = NuvioTheme.textStyles.pauseEpisodeName,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = lineWidth
            )
        }
        MarqueeMetaRow(parts = metaParts, imdb = imdb, tmdb = tmdb)
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = NuvioTheme.textStyles.bodyHome,
                color = marquee.onImageBody,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = tokens.pausePlotWidth)
            )
        }
        if (cast.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = tokens.castLabelTop),
                verticalArrangement = Arrangement.spacedBy(tokens.castChipGap)
            ) {
                Text(
                    text = stringResource(R.string.pause_cast_label).uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = marquee.onImage3,
                    maxLines = 1
                )
                PauseCastRow(
                    cast = cast,
                    castFocusRequest = castFocusRequest,
                    onCastFocusChanged = onCastFocusChanged,
                    onCastFocusRequestHandled = onCastFocusRequestHandled,
                    onCastClick = onCastClick
                )
            }
        }
        if (networkChipText != null) {
            Column(
                modifier = Modifier.padding(top = tokens.castLabelTop),
                verticalArrangement = Arrangement.spacedBy(tokens.castChipGap)
            ) {
                Text(
                    text = stringResource(R.string.pause_network_label).uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = marquee.onImage3,
                    maxLines = 1
                )
                PauseNetworkChip(
                    text = networkChipText,
                    onClick = onNetworkChipClick,
                    modifier = Modifier
                        .focusRequester(networkChipFocusRequester)
                        .onFocusChanged { onNetworkChipFocusChanged(it.isFocused) }
                )
            }
        }
    }
}

/** The Network chip (stage t-pause): a text-only glass `.cch`, focused = white, no scale. */
@Composable
private fun PauseNetworkChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.playerPanel
    Button(
        onClick = onClick,
        modifier = modifier.height(tokens.castChipHeight),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = osdGlassPillColors(),
        contentPadding = PaddingValues(horizontal = tokens.castChipPaddingEnd)
    ) {
        Text(
            text = text,
            style = NuvioTheme.textStyles.cardLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = tokens.pausePlotWidth)
        )
    }
}

/**
 * The cast chips (stage 11b), all composed in a plain scrolling Row (at most [PAUSE_CAST_MAX]), so
 * no chip can leave composition under a pending focus request (CMP-FOCUS-04). Back from the person
 * view composes the row afresh; the hoisted [castFocusRequest] then focuses that chip. The row's
 * end fades out; trailing padding as wide as the fade lets the last chip scroll clear of it.
 */
@Composable
private fun PauseCastRow(
    cast: List<MetaCastMember>,
    castFocusRequest: Int?,
    onCastFocusChanged: (index: Int, focused: Boolean) -> Unit,
    onCastFocusRequestHandled: () -> Unit,
    onCastClick: (index: Int) -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val requesters = remember(cast.size) { List(cast.size) { FocusRequester() } }
    val currentOnRequestHandled by rememberUpdatedState(onCastFocusRequestHandled)
    LaunchedEffect(castFocusRequest, requesters) {
        val index = castFocusRequest ?: return@LaunchedEffect
        // A requester not yet attached throws; focus then stays on the player container.
        runCatching { requesters.getOrNull(index)?.requestFocus() }
        currentOnRequestHandled()
    }
    Row(
        modifier = Modifier
            .width(tokens.castRowWidth)
            .fadeOutEnd(tokens.castRowFadeStart, isRtl)
            .horizontalScroll(rememberScrollState())
            .padding(end = tokens.castRowWidth * (1f - tokens.castRowFadeStart)),
        horizontalArrangement = Arrangement.spacedBy(tokens.castChipGap)
    ) {
        cast.forEachIndexed { index, member ->
            PauseCastChip(
                member = member,
                onClick = { onCastClick(index) },
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .onFocusChanged { onCastFocusChanged(index, it.isFocused) }
            )
        }
    }
}

/** A glass cast chip (`.cch`): photo or initials circle + name; focused = white, no scale. */
@Composable
private fun PauseCastChip(member: MetaCastMember, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.playerPanel
    Button(
        onClick = onClick,
        modifier = modifier.height(tokens.castChipHeight),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = osdGlassPillColors(),
        contentPadding = PaddingValues(start = tokens.castChipPaddingStart, end = tokens.castChipPaddingEnd)
    ) {
        CastPortrait(
            member = member,
            width = tokens.castAvatarSize,
            height = tokens.castAvatarSize,
            shape = CircleShape,
            initialsStyle = NuvioTheme.textStyles.castInitials
        )
        Spacer(modifier = Modifier.width(tokens.castChipGap))
        Text(
            text = member.name,
            style = NuvioTheme.textStyles.cardLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = tokens.castChipNameMaxWidth)
        )
    }
}

/** Stage 11c: the person in place of the block; photo or initials tile, eyebrow, name, role, BACK hint. */
@Composable
private fun PauseCastDetail(member: MetaCastMember, accent: () -> Color, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.playerPanel
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(tokens.castDetailGap),
        verticalAlignment = Alignment.Bottom
    ) {
        CastPortrait(
            member = member,
            width = tokens.castPhotoWidth,
            height = tokens.castPhotoHeight,
            shape = RoundedCornerShape(tokens.castPhotoRadius),
            initialsStyle = NuvioTheme.textStyles.castName
        )
        Column(
            modifier = Modifier
                .padding(bottom = tokens.castDetailTextBottom)
                .widthIn(max = tokens.pauseBlockWidth),
            verticalArrangement = Arrangement.spacedBy(tokens.castDetailTextGap)
        ) {
            MarqueeEyebrow(text = stringResource(R.string.pause_cast_label), accent = accent)
            Text(
                text = member.name,
                style = NuvioTheme.textStyles.castName,
                color = NuvioPrimitives.white,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val character = member.character?.takeIf { it.isNotBlank() }
            if (character != null) {
                Text(
                    text = stringResource(R.string.pause_as_character, character),
                    style = NuvioTheme.textStyles.castRole,
                    color = marquee.onImage2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DetailKeyHint(
                key = stringResource(R.string.detail_key_back),
                label = stringResource(R.string.pause_back_to_details),
                modifier = Modifier.padding(top = tokens.castBackTop)
            )
        }
    }
}

/**
 * The addon's photo, cropped to [width] x [height] in [shape] and decoded at that size, over the
 * initials on a name-picked colour: the initials show while it loads and stay if it fails.
 */
@Composable
private fun CastPortrait(
    member: MetaCastMember,
    width: Dp,
    height: Dp,
    shape: Shape,
    initialsStyle: TextStyle
) {
    val colors = NuvioTheme.marquee.playerPanel.castAvatarColors
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(colors[castAvatarColorIndex(member.name, colors.size)]),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = castInitials(member.name),
            style = initialsStyle,
            color = NuvioPrimitives.white,
            maxLines = 1
        )
        val photo = member.photo?.takeIf { it.isNotBlank() }
        if (photo != null) {
            val context = LocalContext.current
            val density = LocalDensity.current
            val model = remember(context, photo, density, width, height) {
                with(density) {
                    ImageRequest.Builder(context)
                        .data(photo)
                        .crossfade(true)
                        .size(width.roundToPx(), height.roundToPx())
                        .build()
                }
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** Fades the end of the content out from [fadeStart] (a fraction of the width); start side in RTL. */
private fun Modifier.fadeOutEnd(fadeStart: Float, isRtl: Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val mask = if (isRtl) {
            Brush.horizontalGradient(0f to Color.Transparent, (1f - fadeStart) to Color.Black)
        } else {
            Brush.horizontalGradient(fadeStart to Color.Black, 1f to Color.Transparent)
        }
        onDrawWithContent {
            drawContent()
            drawRect(brush = mask, blendMode = BlendMode.DstIn)
        }
    }

/** What a key does while the pause overlay shows (Spec 25 U6h). Back is not here: handleBackPress owns it. */
internal enum class PauseKeyAction {
    /** OnPlayPause. */
    RESUME,

    /** OnDismissPauseOverlay (the controls show). */
    DISMISS,

    /** Focus the first cast chip. */
    FOCUS_CAST,

    /** Focus the Network chip (Spec 33 D7). */
    FOCUS_NETWORK,

    /** Leave the chips: focus returns to the player container. */
    LEAVE_CHIPS,

    /** Eat the key. */
    CONSUME,

    /** Not consumed: the focused chip or focus search takes it. */
    PASS
}

/**
 * Stages 11-11c key model. Play/Pause and Play always resume; Pause and Stop do nothing (as
 * built); key releases are eaten (a Left/Right release would commit a preview seek, and every
 * PlayerEvent cancels the overlay). With no chip focused (overview or person view): OK resumes,
 * Down focuses the first of [chipCount] cast chips, else the Network chip when [hasNetworkChip]
 * (overview only), anything else dismisses. With cast chip [focusedChip] focused: OK is the
 * chip's, Up leaves the row, Down goes to the Network chip (eaten without one), Left/Right at
 * either end of the row are eaten (no escape to Skip Intro or the container), inner Left/Right
 * walk the row by focus search, anything else dismisses. With the Network chip focused
 * ([networkChipFocused], Spec 33 D7; it wins over [focusedChip]): OK is the chip's, Up returns to the first cast chip (or
 * leaves the chips), Down and Left/Right are eaten, anything else dismisses.
 */
internal fun pauseOverlayKeyAction(
    keyCode: Int,
    isKeyDown: Boolean,
    focusedChip: Int?,
    chipCount: Int,
    detailOpen: Boolean,
    isRtl: Boolean,
    hasNetworkChip: Boolean,
    networkChipFocused: Boolean
): PauseKeyAction {
    if (!isKeyDown) return PauseKeyAction.CONSUME
    val isOk = isSelectKey(keyCode)
    val towardStart = if (isRtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    val towardEnd = if (isRtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    return when {
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ->
            PauseKeyAction.RESUME
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP ->
            PauseKeyAction.CONSUME
        networkChipFocused -> when {
            isOk -> PauseKeyAction.PASS
            keyCode == KeyEvent.KEYCODE_DPAD_UP ->
                if (chipCount > 0) PauseKeyAction.FOCUS_CAST else PauseKeyAction.LEAVE_CHIPS
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == towardStart || keyCode == towardEnd ->
                PauseKeyAction.CONSUME
            else -> PauseKeyAction.DISMISS
        }
        focusedChip == null -> when {
            isOk -> PauseKeyAction.RESUME
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !detailOpen && chipCount > 0 -> PauseKeyAction.FOCUS_CAST
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !detailOpen && hasNetworkChip -> PauseKeyAction.FOCUS_NETWORK
            else -> PauseKeyAction.DISMISS
        }
        isOk -> PauseKeyAction.PASS
        keyCode == KeyEvent.KEYCODE_DPAD_UP -> PauseKeyAction.LEAVE_CHIPS
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ->
            if (hasNetworkChip) PauseKeyAction.FOCUS_NETWORK else PauseKeyAction.CONSUME
        keyCode == towardStart -> if (focusedChip <= 0) PauseKeyAction.CONSUME else PauseKeyAction.PASS
        keyCode == towardEnd -> if (focusedChip >= chipCount - 1) PauseKeyAction.CONSUME else PauseKeyAction.PASS
        else -> PauseKeyAction.DISMISS
    }
}

/** "Vanja Rukavina" -> "VR", "Derek de Lint" -> "DL" (first + last word), "Zendaya" -> "Z"; blank -> "". */
internal fun castInitials(name: String): String =
    name.trim()
        .split(WORD_SEPARATOR)
        .filter { it.isNotEmpty() }
        .let { words -> if (words.size > 1) listOf(words.first(), words.last()) else words }
        .joinToString("") { word -> word.substring(0, word.offsetByCodePoints(0, 1)).uppercase() }

/** A stable colour slot per name, so a person keeps their colour across pauses. */
internal fun castAvatarColorIndex(name: String, colorCount: Int): Int = Math.floorMod(name.hashCode(), colorCount)
