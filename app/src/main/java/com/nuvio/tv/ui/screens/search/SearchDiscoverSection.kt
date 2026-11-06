@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.marquee.MarqueeActionTile
import com.nuvio.tv.ui.components.marquee.MarqueeFilterPicker
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueePickerOption
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.LocalFastScrollActive
import com.nuvio.tv.ui.screens.home.MarqueeHomeSkeleton
import com.nuvio.tv.ui.screens.home.MarqueeRowItem
import com.nuvio.tv.ui.screens.home.ModernCatalogCardMetrics
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.allCardsShaped
import com.nuvio.tv.ui.screens.home.catalogCarouselItem
import com.nuvio.tv.ui.screens.home.marqueeCardSize
import com.nuvio.tv.ui.screens.home.usesLandscapeCards
import com.nuvio.tv.ui.screens.home.rememberMarqueeBandBringIntoViewSpec
import com.nuvio.tv.ui.theme.NuvioMarqueeTokens
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel

// Discover in Marquee (approved sandbox `u4-search-discover-marquee` v2, stages 9-13): the settled
// result's backdrop + results hero, one fixed row title, and a band of grid rows (cards packed into
// lines by the grid's form, Spec 41 / u41-addon-card-shapes v2 st7-st9) whose focused row
// sits at Home's anchor while rows above it hide. The filter pills come before the
// grid in the host's list, so they scroll away under the bar once the grid moves. The pieces
// (DiscoverBand state, pills, grid items, hero layer) let a host other than the Discover tab
// place the grid in its own list; the band state lives in DiscoverBand.kt.

/**
 * A grid's form (Spec 41 D1-D3, u41-addon-card-shapes v2 st7-st9): [LANDSCAPE] 207x116 cards, 4 a
 * line (Card shape = Landscape, or every card landscape); [SQUARE] 163x163, 5 a line (every card
 * square); [OWN] each card its own shape at the poster height, 7 posters a line.
 */
internal enum class GridForm { LANDSCAPE, SQUARE, OWN }

/** The form of a grid of these cards under the Card shape setting [useLandscapePosters]. */
internal fun List<ModernCarouselItem>.gridForm(useLandscapePosters: Boolean): GridForm = when {
    usesLandscapeCards(useLandscapePosters) -> GridForm.LANDSCAPE
    allCardsShaped(PosterShape.SQUARE) -> GridForm.SQUARE
    else -> GridForm.OWN
}

/** The grid form a loading state draws before any card is known: the setting's. */
internal fun gridFormOf(useLandscapePosters: Boolean): GridForm =
    if (useLandscapePosters) GridForm.LANDSCAPE else GridForm.OWN

/** A [form] grid's plain cell (the action tile, skeleton cards); its height is every line's. */
internal fun discoverGridCardSize(form: GridForm, marquee: NuvioMarqueeTokens): ModernCatalogCardMetrics = when (form) {
    GridForm.LANDSCAPE -> ModernCatalogCardMetrics(marquee.search.landscapeGridCardWidth, marquee.search.landscapeGridCardHeight)
    GridForm.SQUARE -> ModernCatalogCardMetrics(marquee.search.squareGridCardSize, marquee.search.squareGridCardSize)
    GridForm.OWN -> ModernCatalogCardMetrics(marquee.home.portraitCardWidth, marquee.home.portraitCardHeight)
}

/** [item]'s cell in a [form] grid: the form's cell, or under [GridForm.OWN] the card's own shape. */
internal fun gridCellSize(item: ModernCarouselItem?, form: GridForm, marquee: NuvioMarqueeTokens): ModernCatalogCardMetrics =
    if (form == GridForm.OWN && item != null) {
        item.marqueeCardSize(landscapeRow = false, continueWatchingPoster = false, home = marquee.home)
    } else {
        discoverGridCardSize(form, marquee)
    }

/** Plain cells in one line of a [form] grid (the skeleton's width): 4, 5 or 7. */
internal fun gridCellsPerLine(form: GridForm, marquee: NuvioMarqueeTokens): Int {
    val gap = marquee.search.gridGap
    return ((marquee.search.gridLineWidth + gap) / (discoverGridCardSize(form, marquee).width + gap)).toInt().coerceAtLeast(1)
}

/**
 * Packs cells of [widths] into lines at most [lineWidth] wide, [gap] apart, in order: the first
 * cell of each line. A cell wider than the line still gets a line of its own.
 */
internal fun packGridLines(widths: List<Dp>, lineWidth: Dp, gap: Dp): IntArray {
    val starts = ArrayList<Int>()
    var used = 0.dp
    widths.forEachIndexed { index, width ->
        if (index == 0 || used + gap + width > lineWidth) {
            starts.add(index)
            used = width
        } else {
            used += gap + width
        }
    }
    return starts.toIntArray()
}

/** The line (grid row) holding [cell], for lines starting at [lineStarts]; 0 when there are none. */
internal fun gridRowOf(lineStarts: IntArray, cell: Int): Int {
    val found = lineStarts.binarySearch(cell)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

/**
 * Fast-scroll landing cell on [row] (lines starting at [lineStarts]): [column] places into that line,
 * clamped to its last content cell (never the action tile); null when the row holds no content.
 */
internal fun gridLandingCell(lineStarts: IntArray, row: Int, column: Int, contentCount: Int): Int? {
    val first = lineStarts.getOrNull(row) ?: return null
    if (first >= contentCount) return null
    val end = minOf(lineStarts.getOrNull(row + 1) ?: contentCount, contentCount)
    return (first + column.coerceAtLeast(0)).coerceAtMost(end - 1)
}

/** A grid band's loading state: Home's compact skeleton, one line of [form] grid cells wide. */
@Composable
internal fun GridBandSkeleton(form: GridForm) {
    val marquee = NuvioTheme.marquee
    MarqueeHomeSkeleton(
        landscape = form == GridForm.LANDSCAPE,
        compact = true,
        maxCards = gridCellsPerLine(form, marquee),
        card = discoverGridCardSize(form, marquee)
    )
}

/** Where a band's focused grid row starts: the grid card's height above the band anchor. */
internal fun discoverCardsTop(screenHeight: Dp, form: GridForm, marquee: NuvioMarqueeTokens): Dp =
    screenHeight - marquee.home.focusedCardBottomInset - discoverGridCardSize(form, marquee).height

/**
 * Search's band anchor past the first row (Spec 38 Q4 B), from the screen bottom: the focused row's
 * top, [rowHeadHeight] (a result row's title and its gap) above its [cardHeight] cards, sits at
 * `search.compactBandTop`.
 */
internal fun compactBandBottomInset(
    screenHeight: Dp,
    cardHeight: Dp,
    marquee: NuvioMarqueeTokens,
    rowHeadHeight: Dp = 0.dp
): Dp = (screenHeight - marquee.search.compactBandTop - rowHeadHeight - cardHeight).coerceAtLeast(0.dp)

/** The Discover tab's first grid row in its list: the filter header is item 0. */
internal const val DISCOVER_TAB_FIRST_GRID_ITEM = 1

/** Auto-load once the last laid-out grid row is within two rows of the end. */
internal fun discoverShouldAutoLoad(lastLaidOutRow: Int, rowCount: Int): Boolean =
    rowCount > 0 && lastLaidOutRow >= rowCount - 2

/** The row title's small part: addon, type (when the catalog type suffix is on), genre. */
internal fun discoverRowSubtitle(addonName: String, typeLabel: String?, genreLabel: String?): String =
    listOfNotNull(addonName, typeLabel?.takeIf { it.isNotEmpty() }, genreLabel).joinToString(" · ")

/** The compact hero's eyebrow context (Spec 38 Q4 B): catalog, addon, genre (the item's type leads it). */
internal fun discoverHeroContext(catalogName: String, addonName: String, genreLabel: String?): String =
    listOf(catalogName, discoverRowSubtitle(addonName, typeLabel = null, genreLabel)).joinToString(" · ")

/** Discover results as Marquee cards; a repeated id gets its own key (Home's occurrence count). */
internal fun discoverCarouselItems(
    results: List<MetaPreview>,
    rowKey: String,
    addonBaseUrl: String
): List<ModernCarouselItem> {
    val occurrences = HashMap<String, Int>()
    return results.map { item ->
        val occurrence = occurrences.getOrDefault(item.id, 0)
        occurrences[item.id] = occurrence + 1
        catalogCarouselItem(item, rowKey, addonBaseUrl, useLandscapePosters = false, occurrence = occurrence)
    }
}

/** The Genre picker's "Default" row (no genre filter). */
private const val GENRE_DEFAULT_VALUE = "__default__"

/** The grid rows as items of the host list. */
internal fun LazyListScope.discoverGridItems(band: DiscoverBand, accent: () -> Color) {
    val grid = band.grid
    items(
        count = grid.rowCount,
        key = { "discover_row_$it" },
        contentType = { "discover_row" }
    ) { rowIndex ->
        DiscoverGridRow(
            rowIndex = rowIndex,
            grid = grid,
            activeRow = band.activeRow,
            requesters = band.requesters,
            accent = accent,
            isWatched = band.isItemWatched,
            onItemFocused = band.onItemFocused,
            onActionFocused = band.onActionFocused,
            onItemClick = band.onItemClick,
            onItemLongPress = band.onItemLongPress,
            onAction = band.onAction
        )
    }
}

/**
 * Discover's backdrop + results hero and the fixed row title naming the catalog (stage 9), over the
 * band whose focused row's cards start at [cardsTop] (the host's geometry, [screenHeight] the
 * window). [heroTopLimit]: the bottom of what sits above the hero (the hero's room).
 * [compact] (Search past grid row 1, Spec 38 Q4 B): the top-anchored compact hero, whose eyebrow
 * takes the row title's catalog, addon and genre; no row title.
 */
@Composable
internal fun DiscoverHeroLayer(
    uiState: DiscoverUiState,
    hero: ResultsHeroState,
    screenHeight: Dp,
    cardsTop: Dp,
    heroTopLimit: Dp,
    drawTopScrim: Boolean,
    compact: Boolean = false
) {
    val context = LocalContext.current
    val home = NuvioTheme.marquee.home
    val rowTitleHeight = with(LocalDensity.current) { NuvioTheme.textStyles.rowTitle.lineHeight.toDp() }
    val rowTitleTop = cardsTop - home.rowTitleGap - rowTitleHeight
    val heroBottom = rowTitleTop - home.heroAboveRow
    val selectedCatalog = uiState.selectedCatalog()
    val genreLabel = uiState.selectedDiscoverGenre?.let { localizedGenreLabel(context, it) }
    // Its own box: the row title starts at the safe edge whatever the host's content alignment.
    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeResultsHero(
            hero = hero,
            heroBottomInset = (screenHeight - heroBottom).coerceAtLeast(0.dp),
            heroMaxHeight = (heroBottom - heroTopLimit - home.heroGap).coerceAtLeast(0.dp),
            drawTopScrim = drawTopScrim,
            compact = compact,
            compactContext = selectedCatalog?.let {
                discoverHeroContext(it.catalogName, it.addonName, genreLabel)
            }
        )
        if (selectedCatalog != null && !compact) {
            DiscoverRowTitle(
                title = selectedCatalog.catalogName,
                subtitle = discoverRowSubtitle(
                    addonName = selectedCatalog.addonName,
                    typeLabel = localizedContentType(context, selectedCatalog.type)
                        .takeIf { uiState.catalogTypeSuffixEnabled },
                    genreLabel = genreLabel
                ),
                modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = rowTitleTop)
            )
        }
    }
}

/**
 * The Discover tab body under [headerTop] (the list clips there, so nothing passes under the
 * top bar).
 */
@Composable
internal fun DiscoverSection(
    band: DiscoverBand,
    uiState: DiscoverUiState,
    headerTop: Dp,
    drawTopScrim: Boolean,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val home = marquee.home
    val search = marquee.search
    val context = LocalContext.current
    val density = LocalDensity.current

    var listHasFocus by remember { mutableStateOf(false) }
    var headerHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = listHasFocus && !headerHasFocus) { band.backToFilters() }

    val hero = rememberResultsHeroState(band.settledItem.value)

    // ---- Geometry (Marquee spec §Screens "Home" band, frame rule BC-D1) ----
    val screenHeight = with(density) { context.resources.displayMetrics.heightPixels.toDp() }
    val cardsTop = discoverCardsTop(screenHeight, band.grid.form, marquee)
    val pillsBottom = headerTop + search.filterPillHeight
    val headerHeight = (cardsTop - headerTop - search.gridGap).coerceAtLeast(search.filterPillHeight)
    val bandBottomInset = home.focusedCardBottomInset
    val bandSpec = rememberMarqueeBandBringIntoViewSpec(band.listState, bandBottomInset)
    val defaultSpec = LocalBringIntoViewSpec.current

    Box(modifier = modifier.fillMaxSize()) {
        when {
            band.grid.items.isNotEmpty() -> DiscoverHeroLayer(
                uiState = uiState,
                hero = hero,
                screenHeight = screenHeight,
                cardsTop = cardsTop,
                heroTopLimit = pillsBottom,
                drawTopScrim = drawTopScrim
            )
            uiState.discoverLoading -> GridBandSkeleton(band.grid.form)
            uiState.discoverInitialized && uiState.selectedCatalog() == null -> MarqueeMessage(
                eyebrow = stringResource(R.string.discover_title),
                title = stringResource(R.string.discover_empty_no_catalog_title),
                body = AnnotatedString(stringResource(R.string.discover_empty_no_catalog_subtitle))
            )
            uiState.discoverInitialized -> MarqueeMessage(
                eyebrow = stringResource(R.string.discover_title),
                title = stringResource(R.string.discover_empty_no_content_title),
                body = AnnotatedString(stringResource(R.string.discover_empty_no_content_subtitle))
            )
        }

        CompositionLocalProvider(
            LocalBringIntoViewSpec provides bandSpec,
            LocalFastScrollActive provides band.isFastScrolling
        ) {
            LazyColumn(
                state = band.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = headerTop)
                    .onFocusChanged { listHasFocus = it.hasFocus }
                    .discoverFastScroll(band, with(density) { bandBottomInset.roundToPx() }),
                contentPadding = PaddingValues(bottom = bandBottomInset),
                verticalArrangement = Arrangement.spacedBy(search.gridGap)
            ) {
                item(key = "discover_filters", contentType = "discover_filters") {
                    // The pickers' panels scroll inside: they keep the default bring-into-view.
                    CompositionLocalProvider(LocalBringIntoViewSpec provides defaultSpec) {
                        DiscoverFilterPills(
                            band = band,
                            uiState = uiState,
                            modifier = Modifier
                                .height(headerHeight)
                                .onFocusChanged { headerHasFocus = it.hasFocus }
                                .fillMaxWidth()
                                .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
                        )
                    }
                }
                discoverGridItems(band, hero.accent)
            }
        }
    }
}

/** Filter pills (stage 10): Type, Catalog, Genre; unfocusable while a return restores the grid. */
@Composable
internal fun DiscoverFilterPills(band: DiscoverBand, uiState: DiscoverUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selectedCatalog = uiState.selectedCatalog()
    val filteredCatalogs = uiState.discoverCatalogs.filter { it.type == uiState.selectedDiscoverType }
    val genres = selectedCatalog?.genres.orEmpty()
    val availableTypes = remember(uiState.discoverCatalogs) {
        uiState.discoverCatalogs.map { it.type }.distinct()
    }
    val selectedCatalogLabel = selectedCatalog?.catalogName ?: stringResource(R.string.discover_select_catalog)
    val selectedGenreLabel = uiState.selectedDiscoverGenre?.let { localizedGenreLabel(it) }
        ?: stringResource(R.string.discover_genre_default)

    val pillFocus = Modifier.focusProperties { canFocus = !band.blocksFilterFocus }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.search.filterPillGap)
    ) {
        MarqueeFilterPicker(
            label = stringResource(R.string.discover_filter_type),
            value = localizedContentType(context, uiState.selectedDiscoverType),
            options = availableTypes.map { type -> MarqueePickerOption(localizedContentType(context, type), type) },
            selectedValue = uiState.selectedDiscoverType,
            onSelect = band::selectType,
            modifier = pillFocus.focusRequester(band.filterFocusRequester)
        )
        MarqueeFilterPicker(
            label = stringResource(R.string.discover_filter_catalog),
            value = selectedCatalogLabel,
            options = filteredCatalogs.map { MarqueePickerOption(it.catalogName, it.key, detail = it.addonName) },
            selectedValue = uiState.selectedDiscoverCatalogKey,
            onSelect = band::selectCatalog,
            modifier = pillFocus
        )
        MarqueeFilterPicker(
            label = stringResource(R.string.discover_filter_genre),
            value = selectedGenreLabel,
            options = buildList {
                add(MarqueePickerOption(stringResource(R.string.discover_genre_default), GENRE_DEFAULT_VALUE))
                addAll(genres.map { MarqueePickerOption(localizedGenreLabel(it), it) })
            },
            selectedValue = uiState.selectedDiscoverGenre ?: GENRE_DEFAULT_VALUE,
            onSelect = { value -> band.selectGenre(value.takeUnless { it == GENRE_DEFAULT_VALUE }) },
            modifier = pillFocus
        )
    }
}

/** Discover's poster options (long press); opening Details from them restores the card on return. */
@Composable
internal fun DiscoverPosterOptions(
    viewModel: DiscoverViewModel,
    band: DiscoverBand,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            band.onLeavingForDetail()
            val clickedItem = viewModel.uiState.value.discoverResults.firstOrNull { it.id == id }
            HeroBackdropState.update(clickedItem?.backdropUrl)
            onNavigateToDetail(id, type, addonBaseUrl)
        }
    )
}

/** The band's fixed row title: catalog name + small "addon · type · genre" (stage 9); Library's grid title too. */
@Composable
internal fun DiscoverRowTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = NuvioTheme.textStyles.rowTitle,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline()
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/**
 * One grid row (a line of [DiscoverGrid.cellsOf], each card at its [gridCellSize]), then the action
 * tile after the last result (Library's grid has none). Hidden while a row
 * below it holds focus (still focusable, so Up brings it back to the anchor); Left/Right never
 * leave the row (they would land in a hidden row).
 */
@Composable
internal fun DiscoverGridRow(
    rowIndex: Int,
    grid: DiscoverGrid,
    activeRow: State<Int>,
    requesters: StableRef<MutableMap<Int, FocusRequester>>,
    accent: () -> Color,
    isWatched: (MetaPreview) -> Boolean,
    onItemFocused: (Int, ModernCarouselItem) -> Unit,
    onActionFocused: (Int) -> Unit,
    onItemClick: (ModernCarouselItem) -> Unit,
    onItemLongPress: (ModernCarouselItem) -> Unit,
    onAction: () -> Unit
) {
    val items = grid.items
    val action = grid.action
    val marquee = NuvioTheme.marquee
    Row(
        modifier = Modifier
            .graphicsLayer { alpha = if (rowIndex < activeRow.value) 0f else 1f }
            .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left ||
                        requestedFocusDirection == FocusDirection.Right
                    ) cancelFocusChange()
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.search.gridGap)
    ) {
        for (cell in grid.cellsOf(rowIndex)) {
            val item = items.getOrNull(cell)
            val cardSize = gridCellSize(item, grid.form, marquee)
            if (item != null) {
                key(item.key) {
                    MarqueeRowItem(
                        item = item,
                        requester = requesters.value.getOrPut(cell) { FocusRequester() },
                        isTargetItem = false,
                        blockFocus = false,
                        landscapeRow = grid.form == GridForm.LANDSCAPE,
                        continueWatchingPoster = false,
                        blurUnwatchedEpisodes = false,
                        useEpisodeThumbnails = false,
                        accent = accent,
                        isCatalogItemWatched = isWatched,
                        onFocused = { onItemFocused(cell, item) },
                        onSettled = {},
                        onClick = { onItemClick(item) },
                        onLongPress = { onItemLongPress(item) },
                        cardSize = cardSize
                    )
                }
            } else if (cell == items.size && action != DiscoverGridAction.None) {
                key("discover_action") {
                    MarqueeActionTile(
                        label = stringResource(
                            if (action == DiscoverGridAction.Loading) R.string.discover_loading else R.string.discover_load_more
                        ),
                        icon = Icons.Default.Add.takeIf { action != DiscoverGridAction.Loading },
                        onClick = { if (action != DiscoverGridAction.Loading) onAction() },
                        width = cardSize.width,
                        height = cardSize.height,
                        accent = accent,
                        focusable = action != DiscoverGridAction.Loading,
                        modifier = Modifier
                            .focusRequester(requesters.value.getOrPut(cell) { FocusRequester() })
                            .onFocusChanged { if (it.isFocused) onActionFocused(cell) }
                    )
                }
            }
        }
    }
}
