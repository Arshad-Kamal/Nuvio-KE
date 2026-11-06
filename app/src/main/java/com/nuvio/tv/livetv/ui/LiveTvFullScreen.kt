@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// Live TV full screen (Spec 34 L7c; sandbox u34-live-tv-marquee v3 stages lv-full, lv-zap,
// lv-list-overlay; Q3 keys).

/** What full screen draws over the picture, one at a time (stages 9-11). */
private enum class LiveTvChrome { NONE, OSD, BANNER, LIST }

private const val OSD_MS = 5_000L
private const val BANNER_MS = 4_000L
private const val LIST_MS = 8_000L

/** `.lvtop`: black .75 fading out downwards. */
private val TOP_SCRIM = Brush.verticalGradient(listOf(NuvioPrimitives.black.copy(alpha = 0.75f), Color.Transparent))

/** `.lvbot`: clear, black .6 half way, black .92 at the bottom. */
private val BOTTOM_SCRIM = Brush.verticalGradient(
    0f to Color.Transparent,
    0.5f to NuvioPrimitives.black.copy(alpha = 0.6f),
    1f to NuvioPrimitives.black.copy(alpha = 0.92f)
)

/** `.lvpanel .lvfade`: the overlay list's bottom fade to black .9. */
private val PANEL_FADE = Brush.verticalGradient(listOf(Color.Transparent, NuvioPrimitives.black.copy(alpha = 0.9f)))

/**
 * Full screen over the shared picture (the screen draws [LiveTvPlayerView] under this). The OSD
 * shows on entry and on any key, for [OSD_MS]; Up/Down and CH +/- zap within the shown filter
 * ([LiveTvViewModel.zap]) and show the zap banner for [BANNER_MS]; OK opens the list overlay
 * ([LIST_MS] without a key); Back closes the overlay, else leaves through [onExit]; holding Back
 * swaps to the last channel (Arshad 2026-10-20: a press always leaves, so repeated Backs no longer
 * ping-pong between two channels). Left/Right only show the OSD. Tuning and unavailable
 * reuse the preview's veil and box; Retry takes focus while the channel is unavailable.
 */
@Composable
internal fun LiveTvFullScreen(
    viewModel: LiveTvViewModel,
    ui: LiveTvUiState,
    tune: LiveTuneState,
    info: LiveRow?,
    timeFormat: DateFormat,
    onExit: () -> Unit
) {
    val live = NuvioTheme.marquee.liveTv
    var chrome by remember { mutableStateOf(LiveTvChrome.OSD) }
    // Bumped on every key that restarts the shown chrome's timer.
    var poke by remember { mutableIntStateOf(0) }
    val show = { c: LiveTvChrome ->
        chrome = c
        poke++
    }
    LaunchedEffect(chrome, poke) {
        val ms = when (chrome) {
            LiveTvChrome.OSD -> OSD_MS
            LiveTvChrome.BANNER -> BANNER_MS
            LiveTvChrome.LIST -> LIST_MS
            LiveTvChrome.NONE -> return@LaunchedEffect
        }
        delay(ms)
        chrome = LiveTvChrome.NONE
    }
    val previousKey by viewModel.previousKey.collectAsState()
    val rootRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }
    val unavailable = tune as? LiveTuneState.Unavailable
    val listOpen = chrome == LiveTvChrome.LIST
    // OK pressed on the root: the overlay opens on its key-up, so no stray key-up reaches a row.
    var okArmed by remember { mutableStateOf(false) }
    // Back held past the key-repeat timeout swapped channels: its key-up (BackHandler) must not also leave.
    var backHeld by remember { mutableStateOf(false) }

    // Focus: the overlay's own rows while it is open, else Retry while the channel is unavailable, else the root.
    LaunchedEffect(listOpen, unavailable != null) {
        if (listOpen) return@LaunchedEffect
        if (unavailable != null) retryRequester.requestFocusAfterFrames() else rootRequester.requestFocusAfterFrames()
    }

    // The hold's key-up reaches here too; it ends the hold instead of leaving (whatever had focus
    // at key-up: a swap from an unavailable channel removes the focused Retry).
    BackHandler {
        when {
            listOpen -> chrome = LiveTvChrome.NONE
            backHeld -> backHeld = false
            else -> onExit()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (!listOpen && key.keyCode == KeyEvent.KEYCODE_BACK) {
                    val down = key.action == KeyEvent.ACTION_DOWN
                    return@onPreviewKeyEvent when {
                        // The press itself goes on to the Activity (it tracks Back from its down).
                        down && key.repeatCount == 0 -> {
                            backHeld = false
                            false
                        }
                        // First repeat = held (the system repeats after its long-press timeout).
                        down && !backHeld -> {
                            backHeld = true
                            show(if (viewModel.lastChannel()) LiveTvChrome.BANNER else LiveTvChrome.OSD)
                            true
                        }
                        down -> true
                        // Key-up goes on to BackHandler, which ends a hold instead of leaving.
                        else -> false
                    }
                }
                if (listOpen) {
                    // Any key inside the overlay keeps it open; its rows handle the keys.
                    if (key.action == KeyEvent.ACTION_DOWN) poke++
                    return@onPreviewKeyEvent false
                }
                val step = when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> 1
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
                    else -> return@onPreviewKeyEvent false
                }
                // One zap per press: a held key would start a tune per repeat. Nothing to zap to: the OSD.
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                    show(if (viewModel.zap(step) != null) LiveTvChrome.BANNER else LiveTvChrome.OSD)
                }
                true
            }
            .focusRequester(rootRequester)
            .onKeyEvent { event ->
                if (listOpen) return@onKeyEvent false
                val key = event.nativeKeyEvent
                val down = key.action == KeyEvent.ACTION_DOWN
                when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down && key.repeatCount == 0) okArmed = true
                        if (!down && okArmed) {
                            okArmed = false
                            show(LiveTvChrome.LIST)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> false
                    // Any other key shows the OSD and goes on: volume and media keys, and Left/Right
                    // between Retry and Next channel (the root has no horizontal neighbour).
                    else -> {
                        if (down) show(LiveTvChrome.OSD)
                        false
                    }
                }
            }
            .focusable()
    ) {
        if (tune is LiveTuneState.Tuning) LiveTvTuning(tune, viewModel.number(tune.channel.key))
        // Not under the open list: its Retry would stay focusable beside the rows.
        if (unavailable != null && !listOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(live.chromeFill),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(live.previewWidth, live.previewHeight)) {
                    LiveTvUnavailable(
                        state = unavailable,
                        number = viewModel.number(unavailable.channel.key),
                        timeFormat = timeFormat,
                        retryRequester = retryRequester,
                        onRetry = viewModel::retry,
                        onNextChannel = {
                            if (viewModel.zap(+1) != null) show(LiveTvChrome.BANNER)
                        }
                    )
                }
            }
        }
        val clock = timeFormat.format(Date(ui.nowMs))
        when (chrome) {
            LiveTvChrome.OSD -> info?.let { LiveTvOsd(it, ui.nowMs, timeFormat, clock) }
            LiveTvChrome.BANNER -> info?.let { row ->
                LiveTvZapBanner(row, previousKey?.let { lastChannelLabel(viewModel, it) }, ui.nowMs, timeFormat, clock)
            }
            LiveTvChrome.LIST -> LiveTvListOverlay(
                viewModel = viewModel,
                ui = ui,
                tune = tune,
                timeFormat = timeFormat,
                clock = clock,
                onPicked = { show(LiveTvChrome.BANNER) }
            )
            LiveTvChrome.NONE -> Unit
        }
    }
}

/** "n Name" of the last channel [key]; null when it is no longer listable. */
private fun lastChannelLabel(viewModel: LiveTvViewModel, key: String): String? {
    val channel = viewModel.channel(key) ?: return null
    return listOfNotNull(viewModel.number(key), channel.shownName).joinToString(" ")
}

/** A top scrim [top] tall and a bottom scrim [bottom] tall (`.lvtop`, `.lvbot`). */
@Composable
private fun LiveTvScrims(top: Dp, bottom: Dp) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(top)
                .background(TOP_SCRIM)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottom)
                .background(BOTTOM_SCRIM)
        )
    }
}

/** The row's "category · country" caption (OSD, banner without a programme). */
@Composable
private fun LiveTvFactsLine(row: LiveRow) {
    val facts = LiveTvModel.facts(row)
    if (facts.isEmpty()) return
    Text(
        text = facts.joinToString(FACT_SEPARATOR),
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage2,
        maxLines = 1
    )
}

/** `.lvclk`: the clock top-right. */
@Composable
private fun LiveTvClock(clock: String) {
    val live = NuvioTheme.marquee.liveTv
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text = clock,
            style = NuvioTheme.textStyles.episodeName.copy(fontFeatureSettings = "tnum"),
            color = NuvioTheme.marquee.onImage2,
            maxLines = 1,
            modifier = Modifier.padding(end = live.fullInset, top = live.clockTop)
        )
    }
}

/**
 * Stage 9 OSD: number, tile, name + facts top-left; the clock; LIVE + eyebrow, the programme,
 * time progress (no seek), NEXT and the key hints at the bottom. No current programme: the
 * no-guide line (when no guide covers it) and the hints.
 */
@Composable
private fun LiveTvOsd(row: LiveRow, nowMs: Long, timeFormat: DateFormat, clock: String) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val now = row.now
    LiveTvScrims(live.osdScrimTop, live.osdScrimBottom)
    Row(
        modifier = Modifier.padding(start = live.fullInset, top = live.fullTop),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = row.number,
            style = NuvioTheme.textStyles.pinTitle.copy(lineHeight = 32.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum"),
            color = NuvioPrimitives.white,
            maxLines = 1
        )
        LiveTvChannelTile(channel = row.channel, size = live.logoSize, radius = live.logoRadius)
        Column {
            Text(
                text = row.channel.shownName,
                style = NuvioTheme.textStyles.episodeName,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LiveTvFactsLine(row)
        }
    }
    LiveTvClock(clock)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier.padding(start = live.fullInset, end = live.fullInset, bottom = live.fullBottom),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (now != null) {
                val category = LiveTvModel.categoryLabel(row)
                val eyebrow = listOfNotNull(category, now.category?.takeUnless { it.equals(category, ignoreCase = true) })
                    .joinToString(FACT_SEPARATOR)
                if (row.live || eyebrow.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (row.live) LiveTvLiveBadge()
                        if (eyebrow.isNotEmpty()) MarqueeEyebrow(text = eyebrow, accent = { live.accent })
                    }
                }
                Text(
                    text = now.title,
                    style = NuvioTheme.textStyles.blockTitle,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LiveTvTimeLine(row, nowMs, timeFormat, live.fullProgressWidth)
                row.next?.let { LiveTvNextLine(it, timeFormat) }
            } else if (!row.hasGuide) {
                Text(
                    text = stringResource(R.string.live_tv_no_guide),
                    style = NuvioTheme.textStyles.meta,
                    color = marquee.onImage3,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LiveTvKeyHint(
                    listOf(stringResource(R.string.live_tv_hint_up_down), stringResource(R.string.live_tv_hint_ch)),
                    stringResource(R.string.live_tv_hint_change_channel)
                )
                LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_ok)), stringResource(R.string.live_tv_hint_channel_list))
                LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_back)), stringResource(R.string.nav_live_tv))
            }
        }
    }
}

/**
 * Stage 10 zap banner (`.lvban`), bottom-left: number, tile, name, LIVE; now, time progress, NEXT
 * (else the facts); "HOLD BACK Last channel: n Name" when there is one ([last]).
 */
@Composable
private fun LiveTvZapBanner(row: LiveRow, last: String?, nowMs: Long, timeFormat: DateFormat, clock: String) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val shape = RoundedCornerShape(live.bannerRadius)
    val now = row.now
    LiveTvScrims(live.zapScrimTop, live.zapScrimBottom)
    LiveTvClock(clock)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier
                .padding(start = live.fullInset, bottom = live.fullBottom)
                .width(live.bannerWidth)
                .background(live.chromeFill, shape)
                .border(1.dp, live.chromeEdge, shape)
                .padding(horizontal = live.bannerPaddingHorizontal, vertical = live.bannerPaddingVertical),
            verticalArrangement = Arrangement.spacedBy(live.bannerGap)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = row.number,
                    style = NuvioTheme.textStyles.panelStateTitle.copy(lineHeight = 28.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum"),
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 30.dp)
                )
                LiveTvChannelTile(channel = row.channel, size = live.bannerLogoSize, radius = live.logoRadius)
                Text(
                    text = row.channel.shownName,
                    style = NuvioTheme.textStyles.episodeName,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (row.live) LiveTvLiveBadge()
            }
            if (now != null) {
                Text(
                    text = now.title,
                    style = NuvioTheme.textStyles.osdTime,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LiveTvTimeLine(row, nowMs, timeFormat, live.bannerProgressWidth)
                row.next?.let { LiveTvNextLine(it, timeFormat) }
            } else {
                LiveTvFactsLine(row)
            }
            if (last != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(NuvioPrimitives.white.copy(alpha = 0.1f))
                    )
                    LiveTvKeyHint(
                        listOf(stringResource(R.string.live_tv_hint_hold_back)),
                        stringResource(R.string.live_tv_last_channel, last)
                    )
                }
            }
        }
    }
}

/**
 * Stage 11: the shown filter's list over the picture (`.lvpanel`, black .80, no blur) with the
 * filter chips (select on focus, as the pills). Up/Down move focus and the picture follows after
 * the same 600 ms rest (Q2); OK tunes the row at once and closes ([onPicked]). Opens on the tuned
 * channel's row.
 */
@Composable
private fun LiveTvListOverlay(
    viewModel: LiveTvViewModel,
    ui: LiveTvUiState,
    tune: LiveTuneState,
    timeFormat: DateFormat,
    clock: String,
    onPicked: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val shape = RoundedCornerShape(live.overlayRadius)
    val rows = ui.rows
    val tunedKey = tune.channel?.key
    val playingKey = tunedKey.takeIf { tune !is LiveTuneState.Unavailable }
    val unavailableKey = (tune as? LiveTuneState.Unavailable)?.channel?.key
    val listState = rememberLazyListState()
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val requesterFor = { k: String -> rowRequesters.getOrPut(k) { FocusRequester() } }
    val chipRequester = remember { FocusRequester() }
    val sourceNames by viewModel.sourceNames.collectAsState()

    // Opens on the tuned row; the selected chip when this filter does not list it (a focused row
    // would start tuning another channel after the rest).
    LaunchedEffect(Unit) {
        val index = rows.indexOfFirst { it.channel.key == tunedKey }
        if (index < 0) {
            chipRequester.requestFocusAfterFrames()
        } else {
            listState.scrollToItem((index - live.focusRowsAbove).coerceAtLeast(0))
            requesterFor(rows[index].channel.key).requestFocusAfterFrames()
        }
    }
    // A chip picked another filter: its list starts at the top.
    var shownFilter by remember { mutableStateOf(ui.filter) }
    LaunchedEffect(ui.filter) {
        if (ui.filter == shownFilter) return@LaunchedEffect
        shownFilter = ui.filter
        listState.scrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(0f to live.overlayShade, 0.55f to Color.Transparent))
    )
    LiveTvClock(clock)
    Box(
        modifier = Modifier
            .padding(start = live.overlayStart, top = live.overlayTop, bottom = live.overlayBottom)
            .width(live.overlayWidth)
            .fillMaxHeight()
            .clip(shape)
            .background(live.chromeFill)
            .border(1.dp, live.chromeEdge, shape)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The rows are the filter's channels, so their count is the filter's.
                Text(
                    text = (liveTvFilterLabel(ui.filter, ui.categoryLabels) + FACT_SEPARATOR + ui.rows.size).uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = NuvioPrimitives.white.copy(alpha = 0.6f),
                    maxLines = 1
                )
                Text(
                    text = stringResource(R.string.live_tv_list_hides),
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage3,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Chips select on focus: Up from the list must land on the selected one, not a neighbour.
                    .focusRestorer(chipRequester)
                    .focusGroup()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(live.chipGap)
            ) {
                LiveTvFilter.chips(ui.browse).forEach { filter ->
                    key(filter) {
                        LiveTvChip(
                            label = liveTvFilterLabel(filter, ui.categoryLabels),
                            selected = filter == ui.filter,
                            onSelect = { viewModel.selectFilter(filter) },
                            modifier = if (filter == ui.filter) Modifier.focusRequester(chipRequester) else Modifier
                        )
                    }
                }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberLiveRowBringIntoViewSpec(listState)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(start = live.overlayListInset, end = live.overlayListInset, top = live.overlayListTop)
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = live.fadeHeight),
                verticalArrangement = Arrangement.spacedBy(live.rowGap)
            ) {
                items(rows, key = { it.channel.key }, contentType = { "live_row" }) { row ->
                    val rowKey = row.channel.key
                    LiveTvRow(
                        row = row,
                        selected = rowKey == tunedKey,
                        playing = rowKey == playingKey,
                        muted = rowKey == unavailableKey,
                        timeFormat = timeFormat,
                        sourceName = sourceNames[row.channel.sourceId],
                        // A lost channel's row does nothing (L8-D4).
                        onClick = { if (viewModel.tuneNow(row.channel)) onPicked() },
                        modifier = Modifier
                            .focusRequester(requesterFor(rowKey))
                            .onFocusChanged { if (it.isFocused) viewModel.onRowFocused(row.channel) }
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(live.fadeHeight)
                .background(PANEL_FADE)
        )
    }
}

/** `.lvch` chip: [chipHeight], `chipFill`; selected = glass + a white ring; focused = white, black text. */
@Composable
private fun LiveTvChip(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    Surface(
        onClick = onSelect,
        modifier = modifier
            .height(live.chipHeight)
            .onFocusChanged { if (it.isFocused) onSelect() },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) marquee.glassFill else live.chipFill,
            contentColor = if (selected) white else marquee.onImage2,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(live.chipRing, white), shape = CircleShape) else Border.None,
            focusedBorder = Border.None
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = live.chipPaddingHorizontal),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, style = NuvioTheme.textStyles.panelPill, maxLines = 1)
        }
    }
}
