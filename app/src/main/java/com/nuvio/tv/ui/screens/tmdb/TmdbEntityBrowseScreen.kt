package com.nuvio.tv.ui.screens.tmdb

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbEntityHeader
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.errorStateText
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTile
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.marquee.MARQUEE_SKELETON_BAR_RADIUS
import com.nuvio.tv.ui.components.marquee.MarqueeSkeletonLine
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonTextBar
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.screens.detail.DetailDescription
import com.nuvio.tv.ui.screens.detail.ReadMoreSheet
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.home.MarqueeContextLine
import com.nuvio.tv.ui.screens.home.MarqueeHomeContent
import com.nuvio.tv.ui.screens.home.MarqueeRowItem
import com.nuvio.tv.ui.screens.home.MarqueeRowTitle
import com.nuvio.tv.ui.screens.home.heroBackdropOf
import com.nuvio.tv.ui.screens.home.isWatchedTitle
import com.nuvio.tv.ui.screens.home.usesLandscapeCards
import com.nuvio.tv.ui.screens.search.DiscoverRowTitle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The header's row peek: enough cards to fill the width in either Card shape.
private const val PEEK_CARDS = 8

// Loading skeleton bars (stage 10), the person page's sizes.
private val EYEBROW_BAR = DpSize(140.dp, 11.dp)
private val SUBLINE_BAR = DpSize(240.dp, 13.dp)
private val DESCRIPTION_BARS = listOf(DpSize(480.dp, 13.dp), DpSize(460.dp, 13.dp), DpSize(300.dp, 13.dp))
private val ROW_TITLE_BAR = DpSize(200.dp, 14.dp)

/**
 * Studio / network page (Spec 29, U9 stages 7-13), two modes held in the VM
 * ([TmdbEntityBrowseViewModel.pageMode]): the header frame over a peek of the first row (stage 7,
 * slice 29i) while the description is cut off, Down to the rows; else straight to Home's Marquee
 * rows (stages 8-9, slice 29h) via [MarqueeHomeContent], fed by
 * [TmdbEntityBrowseUiState.Success.homeState]. Loading, error and no titles: stages 10, 11, 13 (29j).
 */
@Composable
fun TmdbEntityBrowseScreen(
    viewModel: TmdbEntityBrowseViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onAddTmdbKey: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pageMode by viewModel.pageMode.collectAsStateWithLifecycle()
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsStateWithLifecycle()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsStateWithLifecycle()
    val isWatched: (MetaPreview) -> Boolean = remember(watchedMovieIds, watchedSeriesIds) {
        { item -> isWatchedTitle(item.id, item.apiType, watchedMovieIds, watchedSeriesIds) }
    }

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        when (val state = uiState) {
            TmdbEntityBrowseUiState.Loading -> EntityLoadingState(
                name = viewModel.entityName,
                breadcrumb = viewModel.breadcrumb
            )

            is TmdbEntityBrowseUiState.Error -> EntityErrorState(
                message = state.message,
                eyebrow = viewModel.sectionLabel.takeIf { it.isNotBlank() },
                breadcrumb = viewModel.breadcrumb,
                onRetry = viewModel::retry
            )

            // "Studios · Blender Studio", as the person page's "Cast · <name>" (stage t1-person).
            TmdbEntityBrowseUiState.NeedsTmdbKey -> TmdbKeyNeededMessage(
                eyebrow = listOf(viewModel.sectionLabel, viewModel.entityName)
                    .filter { it.isNotBlank() }
                    .joinToString(FACT_SEPARATOR)
                    .ifBlank { null },
                onAddKey = onAddTmdbKey,
                onBack = onBackPress,
                breadcrumb = viewModel.breadcrumb
            )

            is TmdbEntityBrowseUiState.Success -> {
                // homeState is null until the first rows build publishes (a few ms off the main
                // thread); both modes draw from it, so the skeleton stays until then, and under the
                // header frame while it is laid out invisibly to pick the mode.
                val homeState = state.homeState
                when {
                    state.data.rails.isEmpty() -> EntityEmptyState(
                        header = state.data.header,
                        subline = state.subline,
                        breadcrumb = viewModel.breadcrumb
                    )
                    homeState == null -> EntityLoadingState(viewModel.entityName, viewModel.breadcrumb)
                    pageMode == EntityPageMode.ROWS -> EntityRowsContent(
                        homeState = homeState,
                        name = state.data.header.name,
                        kindLabel = entityKindLabel(state.data.header.kind),
                        focusState = focusState,
                        isWatched = isWatched,
                        onNavigateToDetail = { id, type, addonBaseUrl ->
                            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
                        },
                        onLoadMoreCatalog = { catalogId, addonId, _ -> viewModel.loadMoreForCatalog(catalogId, addonId) },
                        onSaveFocusState = viewModel::saveFocusState,
                        onLongPress = { item -> viewModel.posterOptions.show(item, null) }
                    )
                    else -> {
                        if (pageMode == null) EntityLoadingState(viewModel.entityName, viewModel.breadcrumb)
                        EntityHeaderFrame(
                            state = state,
                            homeState = homeState,
                            breadcrumb = viewModel.breadcrumb,
                            deciding = pageMode == null,
                            isWatched = isWatched,
                            onPageMode = viewModel::setPageMode
                        )
                    }
                }
            }
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
            }
        )
    }
}

@Composable
private fun entityKindLabel(kind: TmdbEntityKind): String = stringResource(
    when (kind) {
        TmdbEntityKind.COMPANY -> R.string.tmdb_entity_kind_company
        TmdbEntityKind.NETWORK -> R.string.tmdb_entity_kind_network
    }
)

/**
 * Static skeleton in the header frame's geometry with the route's name (stage 10; Home loading
 * rule, no shimmer): logo tile, info column bars, and the first row's peek in the vertical Card
 * shape (the Card shape pref is read with the data).
 */
@Composable
private fun EntityLoadingState(name: String, breadcrumb: List<String>?) {
    val marquee = NuvioTheme.marquee
    val entity = marquee.entity
    val home = marquee.home
    val styles = NuvioTheme.textStyles
    val density = LocalDensity.current
    val start = NuvioTheme.layout.tvSafeHorizontal
    val rowTitleLine = with(density) { styles.rowTitle.lineHeight.toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val fill = marquee.skeletonFill
                val width = size.width.toDp()
                marqueeSkeletonBar(
                    fill, width - start - entity.logoWidth, entity.logoTop,
                    entity.logoWidth, entity.logoHeight, entity.logoRadius
                )
                marqueeSkeletonTextBar(fill, start, entity.peekTop, rowTitleLine, ROW_TITLE_BAR, MARQUEE_SKELETON_BAR_RADIUS)
                val cardsTop = entity.peekTop + rowTitleLine + home.rowTitleGap
                var x = start
                while (x < width) {
                    marqueeSkeletonBar(fill, x, cardsTop, home.portraitCardWidth, home.portraitCardHeight, marquee.cardRadius)
                    x += home.portraitCardWidth + home.cardGap
                }
            }
    ) {
        EntityInfoColumn {
            MarqueeSkeletonLine(with(density) { styles.eyebrow.lineHeight.toDp() }, EYEBROW_BAR)
            EntityName(name)
            MarqueeSkeletonLine(with(density) { styles.meta.lineHeight.toDp() }, SUBLINE_BAR)
            Column {
                val bodyLine = with(density) { styles.bodyDetail.lineHeight.toDp() }
                DESCRIPTION_BARS.forEach { MarqueeSkeletonLine(bodyLine, it) }
            }
        }
        breadcrumb?.let { MarqueeBreadcrumb(it) }
    }
}

/** Home's message block, the section as eyebrow, Retry focused (stage 11; the person page's error). */
@Composable
private fun EntityErrorState(message: String, eyebrow: String?, breadcrumb: List<String>?, onRetry: () -> Unit) {
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
        breadcrumb?.let { MarqueeBreadcrumb(it) }
    }
}

/**
 * No titles (stage 13): the header stays (no description or peek) with Home's empty copy in the
 * peek's place. No title art, so no backdrop; nothing is focusable and Back pops.
 */
@Composable
private fun EntityEmptyState(header: TmdbEntityHeader, subline: String?, breadcrumb: List<String>?) {
    val accent = rememberAmbientAccentProvider(null)
    Box(modifier = Modifier.fillMaxSize()) {
        EntityHeaderInfo(header = header, subline = subline, accent = accent)
        breadcrumb?.let { MarqueeBreadcrumb(it) }
        DiscoverRowTitle(
            title = stringResource(R.string.tmdb_entity_empty_title),
            subtitle = stringResource(R.string.tmdb_entity_empty_subtitle),
            modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = NuvioTheme.marquee.entity.peekTop)
        )
    }
}

/** The header's name line (stage 7; the loading skeleton draws the route's name with it). */
@Composable
private fun EntityName(name: String) {
    Text(
        text = name,
        style = NuvioTheme.textStyles.displayHero,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * The header's logo at the end edge and its info column (kind eyebrow, name, subline, then
 * [description]) in the stage 7 geometry; shared by the header frame and the no-titles state.
 */
@Composable
private fun BoxScope.EntityHeaderInfo(
    header: TmdbEntityHeader,
    subline: String?,
    accent: () -> Color,
    description: @Composable () -> Unit = {}
) {
    val entity = NuvioTheme.marquee.entity
    header.logo?.takeIf { it.isNotBlank() }?.let { logo ->
        EntityLogo(
            logo = logo,
            name = header.name,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = NuvioTheme.layout.tvSafeHorizontal, top = entity.logoTop)
        )
    }
    EntityInfoColumn {
        MarqueeEyebrow(text = entityKindLabel(header.kind), accent = accent)
        EntityName(header.name)
        subline?.let { MarqueeMetaRow(parts = listOf(it), imdb = null) }
        description()
    }
}

/** The stage 7 info column's place and spacing (the header and its loading skeleton). */
@Composable
private fun EntityInfoColumn(content: @Composable ColumnScope.() -> Unit) {
    val marquee = NuvioTheme.marquee
    Column(
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = marquee.entity.infoTop)
            .width(marquee.entity.infoWidth),
        verticalArrangement = Arrangement.spacedBy(marquee.detail.infoGap),
        content = content
    )
}

/**
 * Rows mode (stages 8-9) under the context line "Blender Studio · Production company". TMDB rows
 * have no trailers or Continue Watching, so those callbacks are no-ops.
 */
@Composable
private fun EntityRowsContent(
    homeState: HomeUiState,
    name: String,
    kindLabel: String,
    focusState: HomeScreenFocusState,
    isWatched: (MetaPreview) -> Boolean,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Int, Int) -> Unit,
    onLongPress: (MetaPreview) -> Unit
) {
    MarqueeHomeContent(
        uiState = homeState,
        modernPresentation = homeState.modernHomePresentation,
        focusState = focusState,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = {},
        onRequestTrailerPreview = { _, _, _, _ -> },
        onLoadMoreCatalog = onLoadMoreCatalog,
        onRemoveContinueWatching = { _, _, _, _ -> },
        isCatalogItemWatched = isWatched,
        onCatalogItemLongPress = { item, _ -> onLongPress(item) },
        onSaveFocusState = onSaveFocusState,
        topLine = { MarqueeContextLine(name = name, detail = kindLabel) }
    )
}

/**
 * Header frame (stage 7) over the first title's backdrop under the veil: source breadcrumb, kind
 * eyebrow, name, subline, the 3-line description, the logo, and a non-focusable peek of the first
 * row. While [deciding] it is laid out invisibly: the description's first layout picks the mode
 * (cut off = HEADER, else ROWS). In HEADER the description holds focus: OK opens Read more, Down
 * shows the rows. Up from the rows does not come back (Back pops, as built).
 */
@Composable
private fun EntityHeaderFrame(
    state: TmdbEntityBrowseUiState.Success,
    homeState: HomeUiState,
    breadcrumb: List<String>?,
    deciding: Boolean,
    isWatched: (MetaPreview) -> Boolean,
    onPageMode: (EntityPageMode) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val entity = marquee.entity
    val header = state.data.header
    val firstRow = homeState.modernHomePresentation.rows.list.firstOrNull()
    // The rows open on the same art: MarqueeHomeContent's first frame shows the last displayed backdrop.
    val backdrop = heroBackdropOf(firstRow?.items?.list?.firstOrNull()?.heroPreview)
    LaunchedEffect(backdrop) {
        if (!backdrop.isNullOrBlank()) HeroBackdropState.lastDisplayedUrl = backdrop
    }
    val accent = rememberAmbientAccentProvider(backdrop)

    var descriptionTruncated by remember { mutableStateOf(false) }
    var descriptionLaidOut by remember { mutableStateOf(false) }
    val descriptionRequester = remember { FocusRequester() }
    var showReadMore by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(deciding) {
        if (deciding) {
            // The text layout reports the cut-off before the description is placed.
            snapshotFlow { descriptionLaidOut }.first { it }
            onPageMode(if (descriptionTruncated) EntityPageMode.HEADER else EntityPageMode.ROWS)
        } else {
            descriptionRequester.requestFocusAfterFrames()
        }
    }
    // The header fades under the Read more sheet (Detail's About rule); the backdrop stays.
    val sheetFade by animateFloatAsState(
        targetValue = if (showReadMore) 0f else 1f,
        animationSpec = tween(NuvioTheme.motion.durations.overlay),
        label = "entitySheetFade"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (deciding) 0f else 1f }
    ) {
        MarqueeBackdropImage(url = backdrop, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(entity.veil)
        )
        MarqueeScrims(
            leftStops = marquee.scrimLeftStops,
            bottomStops = marquee.scrimBottomStops,
            drawTopScrim = true
        )
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = sheetFade }) {
            EntityHeaderInfo(header = header, subline = state.subline, accent = accent) {
                header.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Box(
                        modifier = Modifier
                            .onPlaced { descriptionLaidOut = true }
                            .onPreviewKeyEvent { event ->
                                val down = event.key == Key.DirectionDown
                                if (down && event.type == KeyEventType.KeyDown) onPageMode(EntityPageMode.ROWS)
                                down
                            }
                    ) {
                        DetailDescription(
                            text = description,
                            truncated = descriptionTruncated,
                            onTruncatedChange = { descriptionTruncated = it },
                            focusRequester = descriptionRequester,
                            // Unreached: the wrapper above takes Down to show the rows.
                            downFocusRequester = FocusRequester.Cancel,
                            onFocused = {},
                            onClick = { showReadMore = true },
                            width = entity.descriptionWidth
                        )
                    }
                    if (descriptionTruncated) {
                        Text(
                            text = stringResource(R.string.cast_read_more_hint),
                            style = NuvioTheme.textStyles.panelPill,
                            color = marquee.onImage3,
                            maxLines = 1
                        )
                    }
                }
            }
            breadcrumb?.let { MarqueeBreadcrumb(it) }
            firstRow?.let { row ->
                EntityRowPeek(
                    row = row,
                    landscape = row.usesLandscapeCards(homeState.modernLandscapePostersEnabled),
                    accent = accent,
                    isWatched = isWatched,
                    modifier = Modifier.padding(top = entity.peekTop)
                )
            }
        }
    }

    if (showReadMore) {
        ReadMoreSheet(
            title = header.name,
            body = header.description,
            facts = emptyList(),
            accent = accent,
            onDismiss = {
                showReadMore = false
                // Back on the page, the description that opened the sheet takes focus again.
                coroutineScope.launch { descriptionRequester.requestFocusAfterFrames() }
            }
        )
    }
}

/**
 * The logo on Detail's white tile, or drawn white without the tile when it is dark and monochrome
 * (Q7). Hidden until the logo loads and that is known; a failed logo shows the name on the tile.
 */
@Composable
private fun EntityLogo(logo: String, name: String, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val entity = marquee.entity
    val shape = RoundedCornerShape(entity.logoRadius)
    var tiled by remember(logo) { mutableStateOf<Boolean?>(null) }
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (tiled == null) 0f else 1f }
            .then(
                if (tiled == true) {
                    Modifier
                        .shadow(entity.logoShadow, shape)
                        .clip(shape)
                        .background(marquee.detail.logoTileFill)
                } else Modifier
            )
    ) {
        MarqueeLogoTile(
            logo = logo,
            name = name,
            width = entity.logoWidth,
            height = entity.logoHeight,
            tintDarkMonochrome = true,
            onTiled = { tiled = it }
        )
    }
}

/** The first row as it will look in the rows (title + cards in the Card shape), not focusable. */
@Composable
private fun EntityRowPeek(
    row: HeroCarouselRow,
    landscape: Boolean,
    accent: () -> Color,
    isWatched: (MetaPreview) -> Boolean,
    modifier: Modifier = Modifier
) {
    // Never requested: the cards block focus.
    val requester = remember { FocusRequester() }
    Column(modifier = modifier) {
        MarqueeRowTitle(row = row, active = { true })
        Row(
            modifier = Modifier
                .padding(start = NuvioTheme.layout.tvSafeHorizontal)
                // The row runs past the screen's end edge.
                .wrapContentWidth(align = Alignment.Start, unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.home.cardGap)
        ) {
            row.items.list.take(PEEK_CARDS).forEach { item ->
                MarqueeRowItem(
                    item = item,
                    requester = requester,
                    isTargetItem = false,
                    blockFocus = true,
                    landscapeRow = landscape,
                    continueWatchingPoster = false,
                    blurUnwatchedEpisodes = false,
                    useEpisodeThumbnails = false,
                    accent = accent,
                    isCatalogItemWatched = isWatched,
                    onFocused = {},
                    onSettled = {},
                    onClick = {},
                    onLongPress = {}
                )
            }
        }
    }
}
