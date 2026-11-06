package com.nuvio.tv.ui.screens.cast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.RowFocusRequesterRegistry
import com.nuvio.tv.ui.components.errorStateText
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeCardRoleCaption
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.marquee.MarqueeSkeletonLine
import com.nuvio.tv.ui.components.marquee.MARQUEE_SKELETON_BAR_RADIUS
import com.nuvio.tv.ui.components.marquee.MarqueeTab
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonTextBar
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.components.rememberRowFocusRequesterRegistry
import com.nuvio.tv.ui.screens.detail.DetailDescription
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.tmdb.TmdbKeyNeededMessage
import com.nuvio.tv.ui.screens.home.MarqueeContextLine
import com.nuvio.tv.ui.screens.home.MarqueeRowItem
import com.nuvio.tv.ui.screens.search.DiscoverRowTitle
import com.nuvio.tv.ui.screens.search.MarqueeResultsHero
import com.nuvio.tv.ui.screens.search.rememberMarqueeHeroSettle
import com.nuvio.tv.ui.screens.search.rememberResultsHeroState
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Person page (Spec 29, sandbox u9-cast-marquee v3): first frame, loading and error (29d: stages 1,
// 6, 10, 11), the department band and its credit hero (29e: stages 3-5, 12), Read more (29f: stage 2,
// PersonReadMoreSheet).

// The photo field: decoded small (it is blurred and scaled up anyway), then stack-blurred once.
private const val FIELD_DECODE_WIDTH_PX = 160
private const val FIELD_DECODE_HEIGHT_PX = 240
private const val FIELD_BLUR_RADIUS = 16

// Skeleton bar sizes from the approved mockup (stage 10); line boxes follow the first frame.
private val EYEBROW_BAR = DpSize(140.dp, 11.dp)
private val LIFE_BAR = DpSize(280.dp, 13.dp)
private val AKA_BAR = DpSize(240.dp, 13.dp)
private val BIO_BARS = listOf(DpSize(480.dp, 13.dp), DpSize(460.dp, 13.dp), DpSize(300.dp, 13.dp))
private val TABS_BAR = DpSize(200.dp, 14.dp)

@Composable
fun CastDetailScreen(
    viewModel: CastDetailViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onAddTmdbKey: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
    ) {
        when (val state = uiState) {
            is CastDetailUiState.Loading -> PersonLoadingState(name = state.name, breadcrumb = viewModel.breadcrumb)
            is CastDetailUiState.Error -> PersonErrorState(
                message = state.message,
                eyebrow = viewModel.sectionLabel.takeIf { it.isNotBlank() },
                breadcrumb = viewModel.breadcrumb,
                onRetry = viewModel::retry
            )
            // "Cast · Mira Halden" (stage t1-person).
            CastDetailUiState.NeedsTmdbKey -> TmdbKeyNeededMessage(
                eyebrow = listOf(viewModel.sectionLabel, viewModel.personName)
                    .filter { it.isNotBlank() }
                    .joinToString(FACT_SEPARATOR)
                    .ifBlank { null },
                onAddKey = onAddTmdbKey,
                onBack = onBackPress,
                breadcrumb = viewModel.breadcrumb
            )
            is CastDetailUiState.Success -> PersonContent(
                page = state.page,
                breadcrumb = viewModel.breadcrumb,
                onNavigateToDetail = onNavigateToDetail,
                posterOptions = viewModel.posterOptions,
                isItemWatched = { item ->
                    if (item.apiType == "movie") item.id in watchedMovieIds
                    else item.id in watchedSeriesIds
                }
            )
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsState()
        com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
            }
        )
    }
}

/**
 * The loaded page: the first frame (profile) over the department band (tabs + one credit row,
 * stages 1, 3-5, 12). A focused card scrolls the page (stage 3): the profile leaves upward, the
 * band rises to Home's anchor under the focused credit's hero, and "name · department" stays at
 * the top. The biography's focus, or Up from the tabs, brings the first frame back (stage 6). OK on
 * the biography opens Read more (stage 2): the profile and band fade under it, and Back returns
 * focus to the biography.
 */
@Composable
private fun PersonContent(
    page: PersonPage,
    breadcrumb: List<String>,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    isItemWatched: (MetaPreview) -> Boolean
) {
    val marquee = NuvioTheme.marquee
    val person = marquee.person
    val density = LocalDensity.current
    val tabs = page.tabs

    var selectedDepartment by rememberSaveable { mutableStateOf(tabs.firstOrNull()?.department) }
    val selectedTab = tabs.firstOrNull { it.department == selectedDepartment } ?: tabs.firstOrNull()
    // A card's focus scrolls the page; the biography's focus or Up from the tabs ends it.
    var scrolled by rememberSaveable { mutableStateOf(false) }
    // The last focused card (its row key carries department + type, 29/Q9): back from Detail it
    // takes focus again.
    var lastCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    var bioTruncated by rememberSaveable(page.biography) { mutableStateOf(false) }
    var bioLaidOut by remember { mutableStateOf(false) }
    var showReadMore by rememberSaveable { mutableStateOf(false) }
    val bioFocusableWhenFits = tabs.isEmpty()
    // A function, so the arrival focus reads the cut-off after the biography's first layout.
    fun bioTakesFocus() = page.biography != null && (bioTruncated || bioFocusableWhenFits)

    val cardRequesters = rememberRowFocusRequesterRegistry()
    val bioRequester = remember { FocusRequester() }
    val rowRequester = remember { FocusRequester() }
    val tabRequesters = remember { mutableMapOf<String, FocusRequester>() }
    // One row state per tab: another tab starts at its first card.
    val rowState = key(selectedTab?.department) { rememberLazyListState() }
    val settle = rememberMarqueeHeroSettle(seedIndex = 0, scrollState = rowState)
    LaunchedEffect(selectedTab?.department) { settle.reset() }
    // Before a settle (another tab, back from Detail): the last focused card when it is in this
    // tab, else the first.
    val heroCard = selectedTab?.cards?.let { cards ->
        cards.firstOrNull { it.item.key == settle.settledKey }
            ?: cards.firstOrNull { it.item.key == lastCardKey }
            ?: cards.firstOrNull()
    }
    val hero = rememberResultsHeroState(heroCard?.item, heroCard?.heroEyebrow)

    PersonArrivalFocus(
        waitForBio = { page.biography != null && !bioLaidOut },
        target = {
            val restore = lastCardKey?.takeIf { key -> scrolled && selectedTab?.cards?.any { it.item.key == key } == true }
            when {
                restore != null -> cardRequesters.requesterFor(restore)
                // Q1: the biography when it is cut off (OK = Read more) or there are no credits.
                bioTakesFocus() -> bioRequester
                else -> selectedTab?.cards?.firstOrNull()?.let { cardRequesters.requesterFor(it.item.key) }
            }
        }
    )

    // Frame rule (BC-D1): real window pixels through the current (scaled) density.
    val screenHeight = with(density) { LocalContext.current.resources.displayMetrics.heightPixels.toDp() }
    val geometry = personBandGeometry(screenHeight, page.landscape, marquee)
    val travelPx = with(density) { geometry.travel.toPx() }
    val tabLine = with(density) { NuvioTheme.textStyles.tab.lineHeight.toDp() }
    val scroll by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = NuvioMotion.mediumTween(),
        label = "personScroll"
    )
    // The profile and the band fade out under the Read more sheet (Detail's About rule); the field stays.
    val sheetFade = animateFloatAsState(
        targetValue = if (showReadMore) 0f else 1f,
        animationSpec = tween(NuvioTheme.motion.durations.overlay),
        label = "personSheetFade"
    )
    val accent = rememberAmbientAccentProvider(page.fieldImage)
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - scroll }) {
            PersonField(page.fieldImage)
        }
        if (selectedTab != null) {
            MarqueeResultsHero(
                hero = hero,
                heroBottomInset = geometry.heroBottomInset,
                heroMaxHeight = geometry.heroMaxHeight,
                drawTopScrim = true,
                modifier = Modifier.graphicsLayer { alpha = scroll }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -travelPx * scroll
                    alpha = (1f - scroll) * sheetFade.value
                }
        ) {
            PersonPortrait(
                photo = page.photo,
                initials = page.initials,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = NuvioTheme.layout.tvSafeHorizontal, top = marquee.detail.infoTop)
            )
            PersonInfoColumn(
                page = page,
                accent = accent,
                bioTruncated = bioTruncated,
                onBioTruncatedChange = { bioTruncated = it },
                onBioLaidOut = { bioLaidOut = true },
                bioFocusRequester = bioRequester,
                bioDownRequester = if (selectedTab != null) rowRequester else FocusRequester.Default,
                bioFocusableWhenFits = bioFocusableWhenFits,
                onBioFocused = { scrolled = false },
                onBioClick = { showReadMore = true }
            )
            MarqueeBreadcrumb(breadcrumb)
        }
        MarqueeContextLine(
            name = page.person.name,
            detail = selectedTab?.department,
            modifier = Modifier
                .graphicsLayer { alpha = scroll }
                .widthIn(max = person.infoWidth)
        )

        if (selectedTab == null) {
            // Stage 12: the band's place says why it is empty; the biography keeps focus.
            val withoutPoster = page.person.creditsWithoutPoster
            DiscoverRowTitle(
                title = stringResource(R.string.cast_no_titles),
                subtitle = if (withoutPoster > 0) {
                    pluralStringResource(R.plurals.cast_credits_without_poster, withoutPoster, withoutPoster)
                } else "",
                modifier = Modifier
                    .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = person.bandTop)
                    .graphicsLayer { alpha = sheetFade.value }
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(top = person.bandTop)
                    .offset { IntOffset(0, -(travelPx * scroll).roundToInt()) }
                    .graphicsLayer { alpha = sheetFade.value }
            ) {
                PersonTabRow(
                    tabs = tabs,
                    selected = selectedTab.department,
                    tabRequesters = tabRequesters,
                    lineHeight = tabLine,
                    upTarget = if (bioTakesFocus()) bioRequester else FocusRequester.Cancel,
                    onSelect = { selectedDepartment = it },
                    onUp = { scrolled = false }
                )
                Spacer(modifier = Modifier.height(person.tabsToCards - tabLine))
                key(selectedTab.department) {
                    PersonCreditRow(
                        cards = selectedTab.cards,
                        landscape = page.landscape,
                        cardWidth = geometry.cardWidth,
                        state = rowState,
                        lastFocusedKey = lastCardKey,
                        rowRequester = rowRequester,
                        cardRequesters = cardRequesters,
                        accent = hero.accent,
                        isItemWatched = isItemWatched,
                        onCardFocused = { card ->
                            scrolled = true
                            lastCardKey = card.item.key
                            settle.onFocused(card.item.key)
                        },
                        onCardClick = { card ->
                            val preview = card.credit.preview
                            onNavigateToDetail(preview.id, preview.apiType, null)
                        },
                        onCardLongPress = { card -> posterOptions.show(card.credit.preview, null) }
                    )
                }
            }
        }
    }

    if (showReadMore) {
        PersonReadMoreSheet(
            page = page,
            accent = accent,
            onDismiss = {
                showReadMore = false
                // Back on the page, the biography that opened the sheet takes focus again.
                coroutineScope.launch { bioRequester.requestFocusAfterFrames() }
            }
        )
    }
}

/**
 * Focus on arrival (each time the page composes: first visit and back from Detail), once
 * [waitForBio] clears: the biography's first layout reports whether it is cut off, which the
 * [target] rule reads. A [target] that never attaches (its card gone) leaves focus alone.
 */
@Composable
private fun PersonArrivalFocus(waitForBio: () -> Boolean, target: () -> FocusRequester?) {
    val currentWait by rememberUpdatedState(waitForBio)
    val currentTarget by rememberUpdatedState(target)
    LaunchedEffect(Unit) {
        snapshotFlow { currentWait() }.first { !it }
        // The cut-off recomposes the biography's focus target in the next frame.
        withFrameNanos { }
        currentTarget()?.requestFocusAfterFrames(frames = 1)
    }
}

/**
 * One tab per department with its credit count, select on focus (Detail's rule, stage 4). Entering
 * the row lands on the selected tab; Up goes to [upTarget] (the biography, else nowhere) and
 * calls [onUp] (the first frame comes back, stage 6).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PersonTabRow(
    tabs: List<PersonTab>,
    selected: String,
    tabRequesters: MutableMap<String, FocusRequester>,
    lineHeight: Dp,
    upTarget: FocusRequester,
    onSelect: (String) -> Unit,
    onUp: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal)
            // One tab line tall: the cards sit `person.tabsToCards` under the line's top.
            .height(lineHeight)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) onUp()
                false
            }
            .focusRestorer(tabRequesters.getOrPut(selected) { FocusRequester() })
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.detail.sectionTabGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            key(tab.department) {
                MarqueeTab(
                    label = tab.department,
                    count = tab.cards.size.toString(),
                    selected = tab.department == selected,
                    onClick = { onSelect(tab.department) },
                    modifier = Modifier
                        .focusRequester(tabRequesters.getOrPut(tab.department) { FocusRequester() })
                        .focusProperties { up = upTarget }
                        .onFocusChanged { if (it.isFocused) onSelect(tab.department) }
                )
            }
        }
    }
}

/**
 * The selected department's credits in the Card shape (stages 3, 5), each card over its role
 * caption. Entering the row ([rowRequester], Down from the biography or the tabs) returns to the
 * last focused card ([lastFocusedKey]), else the first visible one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PersonCreditRow(
    cards: List<PersonCreditCard>,
    landscape: Boolean,
    cardWidth: Dp,
    state: LazyListState,
    lastFocusedKey: String?,
    rowRequester: FocusRequester,
    cardRequesters: RowFocusRequesterRegistry,
    accent: () -> Color,
    isItemWatched: (MetaPreview) -> Boolean,
    onCardFocused: (PersonCreditCard) -> Unit,
    onCardClick: (PersonCreditCard) -> Unit,
    onCardLongPress: (PersonCreditCard) -> Unit
) {
    val captionGap = NuvioTheme.marquee.library.cardCaptionGap
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(rowRequester)
            .focusRestorer(
                // W5.1: item requesters resolve only while composed. The last focused card, not the
                // first visible (12 F-5): a card's requestFocus from outside the row (the arrival
                // restore back from Detail) runs this restorer's enter, and with no saved child (its
                // node is new after Detail) Compose 1.12 redirects the request to this fallback.
                cardRequesters.restorerTarget(
                    visibleIndices = state.layoutInfo.visibleItemsInfo.map { it.index },
                    keyAt = { index -> cards.getOrNull(index)?.item?.key },
                    preferredIndex = lastFocusedKey?.let { key -> cards.indexOfFirst { it.item.key == key } },
                    fallbackToFirstVisible = true
                )
            ),
        state = state,
        contentPadding = PaddingValues(horizontal = NuvioTheme.layout.tvSafeHorizontal),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.home.cardGap)
    ) {
        items(items = cards, key = { it.item.key }) { card ->
            Column(modifier = Modifier.width(cardWidth)) {
                MarqueeRowItem(
                    item = card.item,
                    requester = cardRequesters.requesterFor(card.item.key),
                    isTargetItem = false,
                    blockFocus = false,
                    landscapeRow = landscape,
                    continueWatchingPoster = false,
                    blurUnwatchedEpisodes = false,
                    useEpisodeThumbnails = false,
                    accent = accent,
                    isCatalogItemWatched = isItemWatched,
                    onFocused = { onCardFocused(card) },
                    onSettled = {},
                    onClick = { onCardClick(card) },
                    onLongPress = { onCardLongPress(card) }
                )
                MarqueeCardRoleCaption(role = card.role, detail = card.detail, accent = accent, gap = captionGap)
            }
        }
    }
}

/** The photo (else the first credit's backdrop) pre-blurred under the veil and Home's scrims (stage 1). */
@Composable
private fun PersonField(imageUrl: String?) {
    val marquee = NuvioTheme.marquee
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        if (imageUrl != null) {
            val request = remember(context, imageUrl) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(FIELD_DECODE_WIDTH_PX, FIELD_DECODE_HEIGHT_PX)
                    .transformations(BlurTransformation(FIELD_BLUR_RADIUS))
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(marquee.person.fieldVeil)
            )
        }
        MarqueeScrims(
            leftStops = marquee.scrimLeftStops,
            bottomStops = marquee.scrimBottomStops,
            drawTopScrim = true
        )
    }
}

/**
 * Eyebrow, name, life line · birthplace, also known as, then the 4-line biography (stages 1, 6);
 * OK on it opens Read more ([onBioClick]), hinted under it when it is cut off.
 */
@Composable
private fun PersonInfoColumn(
    page: PersonPage,
    accent: () -> Color,
    bioTruncated: Boolean,
    onBioTruncatedChange: (Boolean) -> Unit,
    onBioLaidOut: () -> Unit,
    bioFocusRequester: FocusRequester,
    bioDownRequester: FocusRequester,
    bioFocusableWhenFits: Boolean,
    onBioFocused: () -> Unit,
    onBioClick: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val person = marquee.person
    Column(
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = marquee.detail.infoTop)
            .width(person.infoWidth),
        verticalArrangement = Arrangement.spacedBy(marquee.detail.infoGap)
    ) {
        page.eyebrow?.let { MarqueeEyebrow(text = it, accent = accent) }
        PersonName(page.person.name)
        MarqueeMetaRow(parts = listOfNotNull(page.lifeLine, page.placeOfBirth), imdb = null)
        page.alsoKnownAs?.let { names ->
            Text(
                text = stringResource(R.string.cast_also_known_as, names),
                style = NuvioTheme.textStyles.meta,
                color = marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        page.biography?.let { bio ->
            // Placed after its text layout, which has reported the cut-off (the arrival focus waits on it).
            Box(modifier = Modifier.onPlaced { onBioLaidOut() }) {
                DetailDescription(
                    text = bio,
                    truncated = bioTruncated,
                    onTruncatedChange = onBioTruncatedChange,
                    focusRequester = bioFocusRequester,
                    downFocusRequester = bioDownRequester,
                    onFocused = onBioFocused,
                    onClick = onBioClick,
                    maxLines = person.bioMaxLines,
                    width = person.bioWidth,
                    focusableWhenFits = bioFocusableWhenFits
                )
            }
            if (bioTruncated) {
                Text(
                    text = stringResource(R.string.cast_read_more_hint),
                    style = NuvioTheme.textStyles.panelPill,
                    color = marquee.onImage3,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PersonName(name: String) {
    Text(
        text = name,
        style = NuvioTheme.textStyles.displayHero,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** The 200x300 portrait over the initials on a neutral tile, which show without a photo (stage 6) or when it fails. */
@Composable
private fun PersonPortrait(photo: String?, initials: String, modifier: Modifier = Modifier) {
    val person = NuvioTheme.marquee.person
    val shape = RoundedCornerShape(person.portraitRadius)
    val density = LocalDensity.current
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(person.portraitWidth, person.portraitHeight)
            .shadow(person.portraitShadow, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(person.initialsFillStart, person.initialsFillEnd)))
            .border(person.portraitOutlineWidth, person.portraitOutline, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = NuvioTheme.textStyles.displayHero.copy(
                fontSize = person.initialsSize,
                lineHeight = person.initialsSize
            ),
            color = person.initialsText
        )
        if (photo != null) {
            val request = remember(context, photo, density) {
                ImageRequest.Builder(context)
                    .data(photo)
                    .size(
                        width = with(density) { person.portraitWidth.roundToPx() },
                        height = with(density) { person.portraitHeight.roundToPx() }
                    )
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** Static skeleton in the first-frame geometry with the route's name (stage 10; Home loading rule, no shimmer). */
@Composable
private fun PersonLoadingState(name: String, breadcrumb: List<String>) {
    val marquee = NuvioTheme.marquee
    val person = marquee.person
    val styles = NuvioTheme.textStyles
    val density = LocalDensity.current
    val start = NuvioTheme.layout.tvSafeHorizontal
    val tabLine = with(density) { styles.tab.lineHeight.toDp() }
    val cardWidth = marquee.home.portraitCardWidth
    val cardHeight = marquee.home.portraitCardHeight
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val fill = marquee.skeletonFill
                val width = size.width.toDp()
                marqueeSkeletonBar(
                    fill, width - start - person.portraitWidth, marquee.detail.infoTop,
                    person.portraitWidth, person.portraitHeight, person.portraitRadius
                )
                marqueeSkeletonTextBar(fill, start, person.bandTop, tabLine, TABS_BAR, MARQUEE_SKELETON_BAR_RADIUS)
                val cardsTop = person.bandTop + person.tabsToCards
                var x = start
                while (x < width) {
                    marqueeSkeletonBar(fill, x, cardsTop, cardWidth, cardHeight, marquee.cardRadius)
                    x += cardWidth + marquee.home.cardGap
                }
            }
    ) {
        Column(
            modifier = Modifier
                .padding(start = start, top = marquee.detail.infoTop)
                .width(person.infoWidth),
            verticalArrangement = Arrangement.spacedBy(marquee.detail.infoGap)
        ) {
            val metaLine = with(density) { styles.meta.lineHeight.toDp() }
            MarqueeSkeletonLine(with(density) { styles.eyebrow.lineHeight.toDp() }, EYEBROW_BAR)
            PersonName(name)
            MarqueeSkeletonLine(metaLine, LIFE_BAR)
            MarqueeSkeletonLine(metaLine, AKA_BAR)
            Column {
                val bodyLine = with(density) { styles.bodyDetail.lineHeight.toDp() }
                BIO_BARS.forEach { MarqueeSkeletonLine(bodyLine, it) }
            }
        }
        MarqueeBreadcrumb(breadcrumb)
    }
}


/** Home's message block under the breadcrumb, the section as eyebrow, Retry focused (stage 11). */
@Composable
private fun PersonErrorState(message: String, eyebrow: String?, breadcrumb: List<String>, onRetry: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeMessage(
            eyebrow = eyebrow,
            title = stringResource(R.string.cast_detail_error),
            body = errorStateText(message),
            actionLabel = stringResource(R.string.action_retry),
            actionIcon = Icons.Default.Refresh,
            onAction = onRetry,
            focusRequester = focusRequester
        )
        MarqueeBreadcrumb(breadcrumb)
    }
}
