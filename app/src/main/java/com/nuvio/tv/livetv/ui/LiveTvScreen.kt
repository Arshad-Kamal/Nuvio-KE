@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalHideTopBar
import com.nuvio.tv.R
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.abs

/**
 * Live TV, layout A "List + preview" (approved sandbox `u34-live-tv-marquee` v3; Spec 34 D16,
 * L7b): filter pills at y 76 (select on focus), the channel list left, the preview and the info
 * block right; the static skeleton while the stores load and the "Add a channel source" empty
 * state. Always a top-bar tab: content starts at the bar's `contentTop`. OK on a row goes full
 * screen (L7c, [LiveTvFullScreen]; the bar hides; nothing on a lost row, L8-D4); hold OK opens its
 * side panel (L7d, [LiveTvOptionsPanel]); CH +/- zap and focus follows (L8-D6). The preview plays
 * only while the screen is started (another tab or the background stops it). [onOpenSources]
 * opens Live TV › Sources (L7e): false from the Sources pill, true from the empty state's "Add
 * source URL" (its dialog opens too).
 */
@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    onOpenSources: (addSource: Boolean) -> Unit = {}
) {
    val ui by viewModel.uiState.collectAsState()
    LifecycleStartEffect(viewModel) {
        viewModel.resumePreview()
        onStopOrDispose { viewModel.stopPreview() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
    ) {
        when {
            ui.loading -> LiveTvSkeleton()
            !ui.hasChannelSources -> LiveTvEmpty(onAddBroadcasters = viewModel::addBroadcasters, onAddSourceUrl = { onOpenSources(true) })
            else -> LiveTvContent(viewModel = viewModel, ui = ui, onOpenSources = { onOpenSources(false) })
        }
    }
}

@Composable
private fun LiveTvContent(viewModel: LiveTvViewModel, ui: LiveTvUiState, onOpenSources: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val tune by viewModel.player.state.collectAsState()
    val player by viewModel.player.player.collectAsState()
    val info by viewModel.info.collectAsState()
    val focusedKey by viewModel.focusedKey.collectAsState()
    val sourceNames by viewModel.sourceNames.collectAsState()
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val contentRequester = LocalContentFocusRequester.current
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val requesterFor = { key: String -> rowRequesters.getOrPut(key) { FocusRequester() } }
    val pillRequesters = remember { mutableMapOf<LiveTvFilter, FocusRequester>() }
    val selectedPill = pillRequesters.getOrPut(if (ui.filter is LiveTvFilter.Browse) PICKERS_PILL else ui.filter) {
        FocusRequester()
    }
    val retryRequester = remember { FocusRequester() }
    val rows = ui.rows
    val rowsEmpty = rows.isEmpty()
    var fullScreen by remember { mutableStateOf(false) }
    // The row whose hold-OK side panel is open (L7d) and its index then; null when closed.
    var panelRow by remember { mutableStateOf<LiveRow?>(null) }
    var panelIndex by remember { mutableIntStateOf(-1) }
    val hideTopBar = LocalHideTopBar.current
    DisposableEffect(fullScreen, hideTopBar) {
        hideTopBar(fullScreen)
        onDispose { hideTopBar(false) }
    }

    val playingKey = tune.channel?.key.takeIf { tune !is LiveTuneState.Unavailable }
    val unavailableKey = (tune as? LiveTuneState.Unavailable)?.channel?.key

    /** Focus [key]'s row (scrolled to sit at most `focusRowsAbove` down), else the selected pill. */
    val focusRowOrPill = { key: String? ->
        scope.launch {
            val index = rows.indexOfFirst { it.channel.key == key }
            if (key != null && index >= 0) {
                listState.scrollToItem((index - live.focusRowsAbove).coerceAtLeast(0))
                requesterFor(key).requestFocusAfterFrames()
            } else {
                selectedPill.requestFocusAfterFrames()
            }
        }
    }

    // Initial focus (Q1), once: the last-focused channel's row, else the first row; the selected
    // pill when the filter lists nothing (Down enters the list once it has rows).
    LaunchedEffect(Unit) {
        if (rowsEmpty) selectedPill.requestFocusAfterFrames()
        else focusRowOrPill(focusedKey?.takeIf { k -> rows.any { it.channel.key == k } } ?: rows.first().channel.key)
    }
    // Down pressed on a pill before its filter's rows were derived: entered once they land.
    var pendingEnter by remember { mutableStateOf(false) }
    // A new filter starts at its top (Down from the pills enters its first row).
    var shownFilter by remember { mutableStateOf(ui.filter) }
    LaunchedEffect(ui.filter) {
        if (ui.filter == shownFilter) return@LaunchedEffect
        shownFilter = ui.filter
        listState.scrollToItem(0)
        if (pendingEnter && viewModel.selectedFilter == ui.filter) {
            pendingEnter = false
            rows.firstOrNull()?.let { focusRowOrPill(it.channel.key) }
        }
    }

    // One picture for preview and full screen (O1 (a)): this call site never moves, only its size
    // changes, so the PlayerView and its surface survive the switch.
    Box(
        modifier = if (fullScreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .padding(start = live.previewStart, top = live.previewTop)
                .size(live.previewWidth, live.previewHeight)
                .background(live.previewFill)
        }
    ) {
        player?.let {
            LiveTvPlayerView(
                player = it,
                keepScreenOn = tune is LiveTuneState.Tuning || tune is LiveTuneState.Playing,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (fullScreen) {
        LiveTvFullScreen(
            viewModel = viewModel,
            ui = ui,
            tune = tune,
            info = info,
            timeFormat = timeFormat,
            onExit = {
                fullScreen = false
                focusRowOrPill(viewModel.focusedKey.value)
            }
        )
        return
    }

    LiveTvPillRow(
        selected = ui.filter,
        browse = ui.browse,
        pickers = ui.pickers,
        categoryLabels = ui.categoryLabels,
        counts = ui.counts,
        pillRequesters = pillRequesters,
        selectedPill = selectedPill,
        // Down from the bar lands on the pills only while the list is empty (TopBarScaffold).
        contentRequester = contentRequester.takeIf { rowsEmpty },
        onSelect = viewModel::selectFilter,
        onOpenSources = onOpenSources,
        onDown = {
            when {
                // The rows shown are another filter's: enter once the selected one's land.
                viewModel.selectedFilter != ui.filter -> {
                    pendingEnter = true
                    true
                }
                !rowsEmpty -> {
                    focusRowOrPill(rows.first().channel.key)
                    true
                }
                else -> false
            }
        },
        modifier = Modifier.padding(
            start = NuvioTheme.layout.tvSafeHorizontal,
            end = NuvioTheme.layout.tvSafeHorizontal,
            top = marquee.topBar.contentTop + live.pillsTop
        )
    )

    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberLiveRowBringIntoViewSpec(listState)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(start = live.listStart, top = live.listTop)
                .width(live.listWidth)
                .fillMaxHeight()
                // Down from the bar returns to the list's last-focused row (stage 2).
                .then(if (!rowsEmpty) Modifier.focusRequester(contentRequester) else Modifier)
                // A preview still waiting for focus to rest never starts once focus leaves (Q2).
                .onFocusChanged { if (!it.hasFocus) viewModel.cancelPendingPreview() }
                // CH +/- zap from the tuned channel and focus follows (L8-D6); one zap per press.
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    val step = when (key.keyCode) {
                        KeyEvent.KEYCODE_CHANNEL_UP -> 1
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        viewModel.zap(step)?.let { focusRowOrPill(it) }
                    }
                    true
                }
                .focusRestorer()
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = live.fadeHeight),
            verticalArrangement = Arrangement.spacedBy(live.rowGap)
        ) {
            items(rows, key = { it.channel.key }, contentType = { "live_row" }) { row ->
                val rowKey = row.channel.key
                LiveTvRow(
                    row = row,
                    selected = rowKey == focusedKey,
                    playing = rowKey == playingKey,
                    muted = rowKey == unavailableKey,
                    timeFormat = timeFormat,
                    sourceName = sourceNames[row.channel.sourceId],
                    // OK: full screen on this channel, tuned at once (a lost channel: nothing, L8-D4).
                    onClick = { if (viewModel.tuneNow(row.channel)) fullScreen = true },
                    onLongPress = {
                        panelRow = row
                        panelIndex = rows.indexOfFirst { it.channel.key == row.channel.key }
                    },
                    modifier = Modifier
                        .focusRequester(requesterFor(rowKey))
                        // Right from the channel that gave up reaches Retry (stage 6).
                        .focusProperties { if (rowKey == unavailableKey) right = retryRequester }
                        .onFocusChanged { if (it.isFocused) viewModel.onRowFocused(row.channel) }
                )
            }
        }
    }

    // Q-L7b (sandbox v4 stage 17): the selected filter lists nothing; focus stays on its pill.
    // rows and ui.filter come from one state, so the name always matches the rows shown.
    if (rowsEmpty) {
        LiveTvEmptyFilter(
            filter = ui.filter,
            categoryLabels = ui.categoryLabels,
            modifier = Modifier
                .padding(start = live.listStart, top = live.listTop)
                .width(live.listWidth)
        )
    }

    LiveTvPreview(
        tune = tune,
        number = viewModel::number,
        timeFormat = timeFormat,
        retryRequester = retryRequester,
        onRetry = {
            // The buttons leave with the Unavailable state: focus goes back to the channel's row.
            focusRowOrPill(unavailableKey)
            viewModel.retry()
        },
        onNextChannel = { focusRowOrPill(viewModel.zap(+1)) },
        modifier = Modifier.padding(start = live.previewStart, top = live.previewTop)
    )
    info?.let { row ->
        LiveTvInfo(
            row = row,
            nowMs = ui.nowMs,
            unavailable = row.channel.key == unavailableKey,
            timeFormat = timeFormat,
            modifier = Modifier.padding(start = live.previewStart, top = live.infoTop),
            sourceName = sourceNames[row.channel.sourceId]
        )
    }
    LiveTvBottomFade()

    panelRow?.let { row ->
        val key = row.channel.key
        LiveTvOptionsPanel(
            viewModel = viewModel,
            row = row,
            tune = tune,
            timeFormat = timeFormat,
            onDismiss = {
                panelRow = null
                // Unfavorited in Favorites, the row left the list: the one now at its place, else the last.
                val stillListed = rows.any { it.channel.key == key }
                val neighbour = rows.getOrNull(panelIndex) ?: rows.lastOrNull()
                focusRowOrPill(if (stillListed) key else neighbour?.channel?.key)
            },
            onHidden = {
                // The row leaves the list: focus the one after it, else the one before.
                val index = rows.indexOfFirst { it.channel.key == key }
                val neighbour = rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
                panelRow = null
                focusRowOrPill(neighbour?.channel?.key.takeIf { index >= 0 })
            }
        )
    }
}

/**
 * The pill row (`.lvpills`, Spec 38 stage 1): Favorites and Recents with their counts (select on
 * focus, D16, as Library's tabs), the Country, Category and Language pickers (OK opens one and
 * makes them the shown filter; while Favorites or Recents show they keep their values, dimmed),
 * then Sources at the row's end. Entering it lands on [selectedPill] (the Country picker for a
 * Browse). [onDown] handles Down from any pill and says whether it consumed the key.
 */
@Composable
private fun LiveTvPillRow(
    selected: LiveTvFilter,
    browse: LiveTvFilter.Browse,
    pickers: Pickers?,
    categoryLabels: Map<String, String>,
    counts: Map<LiveTvFilter, Int>,
    pillRequesters: MutableMap<LiveTvFilter, FocusRequester>,
    selectedPill: FocusRequester,
    contentRequester: FocusRequester?,
    onSelect: (LiveTvFilter) -> Unit,
    onOpenSources: () -> Unit,
    onDown: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentRequester != null) Modifier.focusRequester(contentRequester) else Modifier)
            .focusRestorer(selectedPill)
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                // Focus moves on key-down only; the key-up needs no answer.
                key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && key.action == KeyEvent.ACTION_DOWN && onDown()
            },
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.liveTv.pillGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(LiveTvFilter.Favorites, LiveTvFilter.Recents).forEach { filter ->
            key(filter) {
                LiveTvPill(
                    label = liveTvFilterLabel(filter),
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    count = counts[filter],
                    modifier = Modifier
                        .focusRequester(pillRequesters.getOrPut(filter) { FocusRequester() })
                        .onFocusChanged { if (it.isFocused) onSelect(filter) }
                )
            }
        }
        val active = selected is LiveTvFilter.Browse
        val all = stringResource(R.string.live_tv_picker_all)
        val (country, category, language) = liveTvBrowseLabels(browse, categoryLabels)
        // The panel's eyebrow names what its counts are inside: "Category · Canada".
        fun eyebrow(title: String, vararg inside: String?) = (listOf(title) + inside.filterNotNull()).joinToString(FACT_SEPARATOR)

        val countryTitle = stringResource(R.string.live_tv_picker_country)
        LiveTvPicker(
            label = countryTitle,
            value = country ?: all,
            eyebrow = eyebrow(countryTitle, category, language),
            active = active,
            all = browse.country == null,
            options = {
                liveTvPickerOptions(pickers?.country, stringResource(R.string.live_tv_filter_all_countries), numbers) {
                    liveTvCountryLabel(it)
                }
            },
            selectedValue = browse.country ?: PICKER_ALL,
            onOpen = { onSelect(browse) },
            onSelect = { v -> onSelect(browse.copy(country = v.ifEmpty { null })) },
            modifier = Modifier.focusRequester(pillRequesters.getOrPut(PICKERS_PILL) { FocusRequester() })
        )
        val categoryTitle = stringResource(R.string.live_tv_picker_category)
        LiveTvPicker(
            label = categoryTitle,
            value = category ?: all,
            eyebrow = eyebrow(categoryTitle, country, language),
            active = active,
            all = browse.category == null,
            options = {
                liveTvPickerOptions(pickers?.category, stringResource(R.string.live_tv_all_categories), numbers) {
                    liveTvCategoryLabel(it, categoryLabels)
                }
            },
            selectedValue = browse.category ?: PICKER_ALL,
            onOpen = { onSelect(browse) },
            onSelect = { v -> onSelect(browse.copy(category = v.ifEmpty { null })) }
        )
        val languageTitle = stringResource(R.string.live_tv_picker_language)
        LiveTvPicker(
            label = languageTitle,
            value = language ?: all,
            eyebrow = eyebrow(languageTitle, country, category),
            active = active,
            all = browse.language == null,
            options = {
                liveTvPickerOptions(pickers?.language, stringResource(R.string.live_tv_all_languages), numbers) {
                    LiveTvModel.languageName(it)
                }
            },
            selectedValue = browse.language ?: PICKER_ALL,
            onOpen = { onSelect(browse) },
            onSelect = { v -> onSelect(browse.copy(language = v.ifEmpty { null })) }
        )
        Spacer(modifier = Modifier.weight(1f))
        LiveTvPill(
            label = stringResource(R.string.live_tv_sources),
            selected = false,
            onClick = onOpenSources,
            // Sandbox icon `lv_ant`.
            icon = Icons.Filled.LiveTv,
            modifier = Modifier.focusProperties { right = FocusRequester.Cancel }
        )
    }
}

/**
 * The list keeps the focused row at most `focusRowsAbove` rows down (D16; the sandbox's window):
 * every focus move scrolls the row to that slot, clamped by the list's bounds.
 */
@Composable
internal fun rememberLiveRowBringIntoViewSpec(listState: LazyListState): BringIntoViewSpec {
    val live = NuvioTheme.marquee.liveTv
    val density = LocalDensity.current
    val defaultSpec = LocalBringIntoViewSpec.current
    return remember(density, defaultSpec, listState, live) {
        val slotPx = with(density) { ((live.rowHeight + live.rowGap) * live.focusRowsAbove).toPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = defaultSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val distance = offset - slotPx
                if (abs(distance) < 1f) return 0f
                // Never fight the scroll bounds (the first rows sit above the slot).
                if (distance < 0f && !listState.canScrollBackward) return 0f
                return distance
            }
        }
    }
}

/** `.lvfade`: the bottom 56 dp fade to black over everything. */
@Composable
private fun LiveTvBottomFade() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.marquee.liveTv.fadeHeight)
                .background(Brush.verticalGradient(listOf(Color.Transparent, NuvioPrimitives.black)))
        )
    }
}

/**
 * Stage 13 (Q7): no channel source yet. "Add broadcasters · 7 channels" is the focused primary
 * (one press to a working tab); "Add source URL" second.
 */
@Composable
private fun LiveTvEmpty(onAddBroadcasters: () -> Unit, onAddSourceUrl: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val requester = LocalContentFocusRequester.current
    LaunchedEffect(requester) { requester.requestFocusAfterFrames() }
    Column(
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = live.emptyTop)
            .width(live.emptyWidth),
        verticalArrangement = Arrangement.spacedBy(live.emptyGap)
    ) {
        MarqueeEyebrow(text = stringResource(R.string.nav_live_tv), accent = { live.accent })
        Text(
            text = stringResource(R.string.live_tv_empty_title),
            style = NuvioTheme.textStyles.signInTitle,
            color = NuvioPrimitives.white
        )
        Text(
            text = stringResource(R.string.live_tv_empty_body),
            style = NuvioTheme.textStyles.signInIntro,
            color = marquee.onImage2
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MarqueePrimaryButton(
                label = stringResource(R.string.live_tv_add_broadcasters),
                icon = null,
                onClick = onAddBroadcasters,
                focusRequester = requester
            )
            MarqueeGlassTextButton(label = stringResource(R.string.live_tv_add_source_url), onClick = onAddSourceUrl)
        }
        Text(
            text = stringResource(R.string.live_tv_empty_note),
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage3,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Stage 16: the static skeleton in the final geometry (white .06, no shimmer), sandbox `s_loading`. */
@Composable
private fun LiveTvSkeleton() {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val fill = marquee.skeletonFill
    val pillsTop = marquee.topBar.contentTop + live.pillsTop
    val left = NuvioTheme.layout.tvSafeHorizontal
    Canvas(modifier = Modifier.fillMaxSize()) {
        repeat(SKELETON_PILLS) { k ->
            marqueeSkeletonBar(fill, left + 92.dp * k, pillsTop, 80.dp, live.pillHeight, live.pillHeight / 2)
        }
        val pitch = live.rowHeight + live.rowGap
        val textX = 126.dp
        repeat(SKELETON_ROWS) { k ->
            val y = live.listTop + pitch * k
            marqueeSkeletonBar(fill, 72.dp, y + 11.dp, live.logoSize, live.logoSize, live.logoRadius)
            marqueeSkeletonBar(fill, textX, y + 14.dp, 180.dp - 24.dp * (k % 3), 14.dp, 7.dp)
            marqueeSkeletonBar(fill, textX, y + 36.dp, 240.dp - 50.dp * (k % 2), 11.dp, 6.dp)
        }
        val x = live.previewStart
        marqueeSkeletonBar(fill, x, live.previewTop, live.previewWidth, live.previewHeight, live.previewRadius)
        marqueeSkeletonBar(fill, x, 384.dp, 120.dp, 11.dp, 6.dp)
        marqueeSkeletonBar(fill, x, 406.dp, 260.dp, 24.dp, 8.dp)
        marqueeSkeletonBar(fill, x, 442.dp, 300.dp, 11.dp, 6.dp)
    }
}

/** The pill-requester key of the Country picker: a Browse filter's pill, whatever its values. */
private val PICKERS_PILL: LiveTvFilter = LiveTvFilter.ALL

private const val SKELETON_PILLS = 7
private const val SKELETON_ROWS = 6

/** An empty filter's line at the list top (Q-L7b), with how to fill Favorites or Recents. */
@Composable
private fun LiveTvEmptyFilter(filter: LiveTvFilter, categoryLabels: Map<String, String>, modifier: Modifier = Modifier) {
    val hint = when (filter) {
        LiveTvFilter.Favorites -> stringResource(R.string.live_tv_empty_favorites_hint)
        LiveTvFilter.Recents -> stringResource(R.string.live_tv_empty_recents_hint)
        else -> null
    }
    Column(
        // Sandbox stage 17: 14 dp above, 10 dp in, 4 dp between.
        modifier = modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.live_tv_empty_filter, liveTvFilterLabel(filter, categoryLabels)),
            style = NuvioTheme.textStyles.rowTitle,
            color = NuvioPrimitives.white
        )
        if (hint != null) {
            Text(text = hint, style = NuvioTheme.textStyles.meta, color = NuvioTheme.marquee.onImage3)
        }
    }
}
