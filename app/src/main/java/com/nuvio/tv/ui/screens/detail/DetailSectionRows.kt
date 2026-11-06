package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.ui.components.marquee.MarqueeCard
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTile
import com.nuvio.tv.ui.components.marquee.MarqueeCardTick
import com.nuvio.tv.ui.components.marquee.MarqueeCardTitle
import com.nuvio.tv.ui.components.rememberRowFocusRequesterRegistry
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

// Section band rows (Marquee spec §Screens "Detail as decided at the U2 slice", sandbox
// u2-detail-marquee stages 5, 6, 8): cast circles, 208x117 landscape cards (related, collection,
// trailers) and studio/network logo tiles. Card depth does not apply (Q-U2).

private val ART_BACKING_PAINTER = ColorPainter(NuvioPrimitives.neutral900)

/**
 * A focus-restoring lazy row: entering it lands on the first item, or on [restoreKey] while a
 * restore ([restoreFocusToken]) is pending; the restore scrolls to that item, focuses it and
 * reports [onRestoreFocusHandled] once it holds focus. [itemContent] receives the item's focus
 * modifier (requester, Up to [upFocusRequester], restore bookkeeping) for its focus target.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun <T> DetailRestoringRow(
    items: List<T>,
    key: (T) -> String,
    gap: Dp,
    upFocusRequester: FocusRequester,
    restoreKey: String?,
    restoreFocusToken: Int,
    onRestoreFocusHandled: () -> Unit,
    itemContent: @Composable (item: T, focusModifier: Modifier) -> Unit
) {
    val registry = rememberRowFocusRequesterRegistry()
    val prefetch = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val rowState = rememberLazyListState(prefetchStrategy = prefetch)
    var restorePending by remember { mutableStateOf(false) }

    LaunchedEffect(items) {
        registry.retainKeys(items.mapTo(mutableSetOf()) { key(it) })
    }
    // Keyed on the token only: the target is read when a restore fires (on return to the screen),
    // not at the moment of the click that recorded it.
    LaunchedEffect(restoreFocusToken) {
        val index = restoreKey?.takeIf { restoreFocusToken > 0 }?.let { target -> items.indexOfFirst { key(it) == target } } ?: -1
        if (index < 0) {
            restorePending = false
            return@LaunchedEffect
        }
        restorePending = true
        if (rowState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowState.scrollToItem(index)
        registry.requesterFor(key(items[index])).requestFocusAfterFrames()
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer(
                // W5.1: item requesters resolve only while composed.
                registry.restorerTarget(
                    visibleIndices = rowState.layoutInfo.visibleItemsInfo.map { it.index },
                    keyAt = { index -> items.getOrNull(index)?.let(key) },
                    preferredIndex = if (restorePending) items.indexOfFirst { key(it) == restoreKey } else 0,
                    fallbackToFirstVisible = false
                )
            ),
        state = rowState,
        contentPadding = PaddingValues(horizontal = NuvioTheme.layout.tvSafeHorizontal),
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        items(items, key = key) { item ->
            val itemKey = key(item)
            val focusModifier = Modifier
                .focusRequester(registry.requesterFor(itemKey))
                .focusProperties { up = upFocusRequester }
                .onFocusChanged {
                    if (it.isFocused && itemKey == restoreKey && restoreFocusToken > 0) {
                        restorePending = false
                        onRestoreFocusHandled()
                    }
                }
            itemContent(item, focusModifier)
        }
    }
}

/** Cast: lead credits (creator/director/writer, as built) then the cast, 80 dp circles, name + role. */
@Composable
internal fun DetailCastRow(
    leadingCast: List<MetaCastMember>,
    cast: List<MetaCastMember>,
    accent: () -> Color,
    upFocusRequester: FocusRequester,
    restorePersonId: Int?,
    restoreFocusToken: Int,
    onRestoreFocusHandled: () -> Unit,
    onFocused: () -> Unit,
    onClick: (MetaCastMember) -> Unit
) {
    val detail = NuvioTheme.marquee.detail
    // Block-prefixed keys: a director can also be in the cast. The restore lands on the lead
    // credit first, as built.
    val entries = remember(leadingCast, cast) {
        (leadingCast.map { "leading" to it } + cast.map { "cast" to it })
            .distinctBy { (block, member) -> castKey(block, member) }
    }
    val restoreKey = restorePersonId?.let { id ->
        entries.firstOrNull { it.second.tmdbId == id }?.let { (block, member) -> castKey(block, member) }
    }
    DetailRestoringRow(
        items = entries,
        key = { (block, member) -> castKey(block, member) },
        gap = detail.castGap,
        upFocusRequester = upFocusRequester,
        restoreKey = restoreKey,
        restoreFocusToken = restoreFocusToken,
        onRestoreFocusHandled = onRestoreFocusHandled
    ) { (_, member), focusModifier ->
        DetailCastItem(
            member = member,
            accent = accent,
            focusModifier = focusModifier,
            onFocused = onFocused,
            onClick = { onClick(member) }
        )
    }
}

private fun castKey(block: String, member: MetaCastMember): String =
    "$block:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"

@Composable
private fun DetailCastItem(
    member: MetaCastMember,
    accent: () -> Color,
    focusModifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { detail.castSize.roundToPx() }
    val photoModel = remember(context, member.photo, sizePx) {
        member.photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = sizePx, height = sizePx)
                .build()
        }
    }
    var isFocused by remember { mutableStateOf(false) }
    val glowColor = if (isFocused) accent().copy(alpha = marquee.glowCardAlpha) else Color.Transparent
    val role = castRoleLabel(member.character)

    Column(
        modifier = Modifier.width(detail.castItemWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MarqueeCard(
            onClick = onClick,
            width = detail.castSize,
            height = detail.castSize,
            shape = CircleShape,
            glowColor = glowColor,
            modifier = focusModifier.onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
        ) {
            if (photoModel != null) {
                AsyncImage(
                    model = photoModel,
                    contentDescription = member.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = ART_BACKING_PAINTER,
                    error = ART_BACKING_PAINTER,
                    fallback = ART_BACKING_PAINTER
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(marquee.glassFill),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = member.name.firstOrNull()?.uppercase() ?: "?",
                        style = NuvioTheme.textStyles.sectionTitle,
                        color = NuvioPrimitives.white
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(detail.castTextGap))
        CastText(text = member.name, style = NuvioTheme.textStyles.cardLabel, color = NuvioPrimitives.white)
        if (role != null) {
            CastText(text = role, style = NuvioTheme.textStyles.caption, color = marquee.onImage2)
        }
    }
}

/** One centred line that may spread into the gaps beside the item (`castTextMaxWidth`). */
@Composable
private fun CastText(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .wrapContentWidth(unbounded = true)
            .widthIn(max = NuvioTheme.marquee.detail.castTextMaxWidth)
    )
}

@Composable
private fun castRoleLabel(character: String?): String? {
    val role = character?.takeIf { it.isNotBlank() } ?: return null
    return when {
        role.equals("Creator", ignoreCase = true) -> stringResource(R.string.cast_role_creator)
        role.equals("Director", ignoreCase = true) -> stringResource(R.string.cast_role_director)
        role.equals("Writer", ignoreCase = true) -> stringResource(R.string.cast_role_writer)
        else -> role
    }
}

/** One 208x117 landscape card's data (related, collection, trailer). */
@Immutable
internal data class DetailCardItem<T>(
    val key: String,
    val title: String,
    val imageUrl: String?,
    val isWatched: Boolean,
    val value: T
)

/**
 * Related / collection / trailer cards: 208x117 Marquee landscape cards (art, title, a white
 * tick when watched), accent glow on focus, [onLongPress] when given (poster options).
 */
@Composable
internal fun <T> DetailCardRow(
    items: List<DetailCardItem<T>>,
    accent: () -> Color,
    upFocusRequester: FocusRequester,
    restoreKey: String?,
    restoreFocusToken: Int,
    onRestoreFocusHandled: () -> Unit,
    onFocused: (T) -> Unit,
    onClick: (T) -> Unit,
    onLongPress: ((T) -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { detail.cardWidth.roundToPx() }
    val heightPx = with(density) { detail.cardHeight.roundToPx() }
    val watchedLabel = stringResource(R.string.episodes_cd_watched)
    val distinct = remember(items) { items.distinctBy { it.key } }
    DetailRestoringRow(
        items = distinct,
        key = { it.key },
        gap = marquee.home.cardGap,
        upFocusRequester = upFocusRequester,
        restoreKey = restoreKey,
        restoreFocusToken = restoreFocusToken,
        onRestoreFocusHandled = onRestoreFocusHandled
    ) { item, focusModifier ->
        var isFocused by remember { mutableStateOf(false) }
        val request = remember(context, item.imageUrl, widthPx, heightPx) {
            ImageRequest.Builder(context)
                .data(item.imageUrl)
                .crossfade(true)
                .size(width = widthPx, height = heightPx)
                .build()
        }
        MarqueeCard(
            onClick = { onClick(item.value) },
            onLongPress = onLongPress?.let { longPress -> { longPress(item.value) } },
            width = detail.cardWidth,
            height = detail.cardHeight,
            glowColor = if (isFocused) accent().copy(alpha = marquee.glowCardAlpha) else Color.Transparent,
            modifier = focusModifier.onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused(item.value)
            }
        ) {
            AsyncImage(
                model = request,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = ART_BACKING_PAINTER,
                error = ART_BACKING_PAINTER,
                fallback = ART_BACKING_PAINTER
            )
            MarqueeCardTitle(item.title)
            if (item.isWatched) MarqueeCardTick(contentDescription = watchedLabel)
        }
    }
}

/** A studio or network logo and the TMDB browse kind it opens ("company" / "network"). */
@Immutable
internal data class DetailCompanyItem(val company: MetaCompany, val kind: String)

/** Studio / network logos on white .92 tiles; OK opens the TMDB browse page (companies with a TMDB id). */
@Composable
internal fun DetailLogoRow(
    companies: List<DetailCompanyItem>,
    upFocusRequester: FocusRequester,
    restoreCompanyId: Int?,
    restoreFocusToken: Int,
    onRestoreFocusHandled: () -> Unit,
    onFocused: () -> Unit,
    onClick: (DetailCompanyItem) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val entries = remember(companies) { companies.distinctBy { companyKey(it) } }
    val restoreKey = restoreCompanyId?.let { id -> entries.firstOrNull { it.company.tmdbId == id }?.let(::companyKey) }
    DetailRestoringRow(
        items = entries,
        key = ::companyKey,
        gap = marquee.home.cardGap,
        upFocusRequester = upFocusRequester,
        restoreKey = restoreKey,
        restoreFocusToken = restoreFocusToken,
        onRestoreFocusHandled = onRestoreFocusHandled
    ) { item, focusModifier ->
        val company = item.company
        MarqueeCard(
            onClick = { if (company.tmdbId != null) onClick(item) },
            width = detail.logoTileWidth,
            height = detail.logoTileHeight,
            containerColor = detail.logoTileFill,
            ringGap = marquee.primaryButton.ringGap,
            modifier = focusModifier.onFocusChanged { if (it.isFocused) onFocused() }
        ) {
            MarqueeLogoTile(
                logo = company.logo,
                name = company.name,
                width = detail.logoTileWidth,
                height = detail.logoTileHeight
            )
        }
    }
}

private fun companyKey(item: DetailCompanyItem): String =
    "${item.kind}:${item.company.tmdbId ?: item.company.name}"
