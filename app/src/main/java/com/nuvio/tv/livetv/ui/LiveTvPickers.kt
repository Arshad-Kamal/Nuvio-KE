package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioMarqueeLiveTvTokens
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeAnchoredPanel
import com.nuvio.tv.ui.components.marquee.MarqueePickerOption
import com.nuvio.tv.ui.components.marquee.MarqueePickerOptionList
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import java.text.NumberFormat

// Live TV pickers (Spec 38 Q1; sandbox u38-filters-search v4 stages 1-4, `.l38p`, `.pk`).

/** The option value of a picker's All row (picker keys are never empty). */
internal const val PICKER_ALL = ""

/** The pill's last layout position; read when the panel opens, never observed. */
private class PillAnchor {
    var coordinates: LayoutCoordinates? = null
}

/**
 * One picker in the pill row: a [LiveTvPickerPill] whose OK calls [onOpen] (the pickers become the
 * shown filter, stage 1) and opens Discover's compact panel under it ([eyebrow], [options], built
 * only while it is open; a tick on [selectedValue]). OK on an option closes it and calls
 * [onSelect]; Back closes unchanged. Focus returns to the pill. Callers put `focusRequester` in
 * [modifier]. [showLabel] false draws the value only ([label] still names it to accessibility; the
 * View picker, Spec 67 D7); [alignEnd] lines the panel up with the pill's end. [footer] draws under the
 * options in the same panel (the View picker's Preview on focus row); OK there leaves the panel open.
 */
@Composable
internal fun LiveTvPicker(
    label: String,
    value: String,
    eyebrow: String,
    active: Boolean,
    all: Boolean,
    options: @Composable () -> List<MarqueePickerOption>,
    selectedValue: String,
    onOpen: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    alignEnd: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val pillRequester = remember { FocusRequester() }
    var openAt by remember { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    val close = {
        openAt = null
        scope.launch { pillRequester.requestFocusAfterFrames() }
        Unit
    }
    LiveTvPickerPill(
        label = label,
        value = value,
        active = active,
        all = all,
        showLabel = showLabel,
        onOpen = { bounds ->
            onOpen()
            openAt = bounds
        },
        modifier = modifier.focusRequester(pillRequester)
    )
    openAt?.let { bounds ->
        val initial = remember { FocusRequester() }
        MarqueeAnchoredPanel(anchor = bounds, onDismissRequest = close, alignEnd = alignEnd, initialFocus = initial) {
            MarqueePickerOptionList(
                title = eyebrow,
                options = options(),
                selectedValue = selectedValue,
                focusRequester = initial,
                onSelect = { picked ->
                    close()
                    onSelect(picked)
                }
            )
            footer?.invoke(this)
        }
    }
}

/**
 * Spec 111 stage 16 (D10): the View picker's last row, under a rule. OK flips Preview on focus in place
 * ([on]: the profile's setting) and the panel stays open; the line under it says what each state does.
 */
@Composable
internal fun ColumnScope.LiveTvPreviewOnFocusRow(on: Boolean, onToggle: () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    PanelRule(below = 6.dp)
    PlayerPanelRow(
        title = stringResource(R.string.live_tv_preview_on_focus),
        selected = false,
        onClick = onToggle,
        titleEnd = stringResource(if (on) R.string.live_tv_on else R.string.live_tv_off)
    )
    Text(
        text = stringResource(R.string.live_tv_preview_on_focus_detail),
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage3,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp)
    )
}

/**
 * `.l38p`: [label] in small type (unless not [showLabel]), the [value], a chevron; [pillHeight] tall like the filter pills.
 * Glass when the pickers are the shown filter ([active]) and it holds a value; at All ([all]) a
 * hairline with dim text; a value while Favorites or Recents show = a hairline with white text.
 * Focused = white, black text.
 */
@Composable
private fun LiveTvPickerPill(
    label: String,
    value: String,
    active: Boolean,
    all: Boolean,
    showLabel: Boolean,
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val anchor = remember { PillAnchor() }
    var focused by remember { mutableStateOf(false) }
    val glass = active && !all
    Surface(
        onClick = { anchor.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let(onOpen) },
        modifier = modifier
            .height(live.pillHeight)
            .onFocusChanged { focused = it.isFocused }
            .onGloballyPositioned { anchor.coordinates = it },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (glass) marquee.glassFill else Color.Transparent,
            contentColor = if (all) marquee.onImage2 else white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (glass) Border.None else Border(BorderStroke(1.dp, live.pillOutline), shape = CircleShape),
            focusedBorder = Border.None
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        val secondary = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = live.pillPaddingHorizontal, end = live.pickerPaddingEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showLabel) {
                Text(text = label, style = NuvioTheme.textStyles.caption, color = secondary, maxLines = 1)
                Spacer(modifier = Modifier.width(live.pickerInnerGap))
            }
            // Category values can be playlist groups of any length; a pill held narrower by the row (D12)
            // cuts its value, never its chevron.
            Text(
                text = value,
                style = NuvioTheme.textStyles.cardLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = live.pickerValueMaxWidth)
            )
            Spacer(modifier = Modifier.width(live.pickerInnerGap))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_expand, label),
                tint = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage2,
                modifier = Modifier.size(live.pillIconSize)
            )
        }
    }
}

/**
 * A picker's options (stages 2-4): with pinned values, "YOUR CHANNELS" over them and "ALL" over the
 * rest; else no headings. [allLabel] names the All row ("All countries"), [label] a key's value.
 */
@Composable
internal fun liveTvPickerOptions(
    picker: Picker?,
    allLabel: String,
    numbers: NumberFormat,
    label: @Composable (String) -> String
): List<MarqueePickerOption> {
    if (picker == null) return emptyList()
    val sections = picker.pinned.isNotEmpty()
    val pinnedSection = if (sections) stringResource(R.string.live_tv_picker_yours) else null
    val allSection = if (sections) stringResource(R.string.live_tv_picker_all_section) else null
    val out = ArrayList<MarqueePickerOption>(picker.pinned.size + picker.values.size)
    for (v in picker.pinned) {
        out += MarqueePickerOption(label(v.key!!), v.key, count = numbers.format(v.count), section = pinnedSection)
    }
    for (v in picker.values) {
        val key = v.key
        out += MarqueePickerOption(
            label = if (key == null) allLabel else label(key),
            value = key ?: PICKER_ALL,
            count = numbers.format(v.count),
            section = allSection
        )
    }
    return out
}

/**
 * Spec 111 D12 (sandbox `s111-live-search` v2 stage 17): the pill row's one width model. What [pickers] pickers
 * have of [available] once the pills that never shrink ([fixed]: search, Favorites, Recents, View, Sources) and
 * every [gap] between all items are taken. The captions stay when the captioned pickers' sum fits in it (decided
 * before layout, so a row that does not fit never shows a frame with captions); else it is what
 * [liveTvPickerShares] splits.
 */
internal fun liveTvPickerSpace(available: Dp, fixed: List<Dp>, pickers: Int, gap: Dp): Dp =
    available - fixed.fold(0.dp) { a, w -> a + w } - gap * (fixed.size + pickers - 1).coerceAtLeast(0)

/**
 * D12: the widths the three caption-less pickers get from [remaining] (water-fill: one cap, short pickers keep
 * their [naturals], only the longer ones cut down to the cap). Never below [floor] (padding + chevron, so the
 * chevron always shows). All [naturals] when they fit.
 */
internal fun liveTvPickerShares(remaining: Dp, naturals: List<Dp>, floor: Dp): List<Dp> {
    if (naturals.fold(0.dp) { a, w -> a + w } <= remaining) return naturals
    var left = remaining
    var cap = Dp.Infinity // a loop that never cuts (float order vs the sum above) leaves every picker whole
    val sorted = naturals.sorted()
    for ((i, w) in sorted.withIndex()) {
        val n = sorted.size - i
        if (w * n <= left) left -= w else { cap = left / n; break }
    }
    return naturals.map { minOf(it, maxOf(cap, floor)) }
}

/**
 * A picker pill's width as [LiveTvPickerPill] lays it out, from its text widths: [caption] null = no
 * caption (the View picker); the [value] at most `pickerValueMaxWidth`.
 */
internal fun liveTvPickerWidth(caption: Dp?, value: Dp, live: NuvioMarqueeLiveTvTokens): Dp =
    live.pillPaddingHorizontal + (caption?.let { it + live.pickerInnerGap } ?: 0.dp) +
        minOf(value, live.pickerValueMaxWidth) + live.pickerInnerGap + live.pillIconSize + live.pickerPaddingEnd

/** A [LiveTvPill]'s width from its text widths: a [label], an optional [count], an optional icon ([icon]). */
internal fun liveTvPillWidth(label: Dp, count: Dp?, icon: Boolean, live: NuvioMarqueeLiveTvTokens): Dp =
    live.pillPaddingHorizontal * 2 + label + (count?.let { live.pillCountGap + it } ?: 0.dp) +
        (if (icon) live.pillIconSize + live.pillIconGap else 0.dp)

/**
 * Measures one line of [text] in [style] (dp, plus 1 dp: rounding errs towards dropping captions, never
 * towards a row that overflows with them), for the D12 fit. The cache holds the row's dozen texts.
 */
@Composable
internal fun rememberTextWidth(): (String, TextStyle) -> Dp {
    val measurer = rememberTextMeasurer(cacheSize = TEXT_WIDTH_CACHE)
    val density = LocalDensity.current
    return remember(measurer, density) {
        { text, style -> with(density) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp() } + 1.dp }
    }
}

private const val TEXT_WIDTH_CACHE = 16

/**
 * Spec 111 D7: Back from a row goes to the selected filter while a row (list or grid) holds focus, except
 * while searching (Back closes search), in full screen, or with the schedule column or options panel open.
 */
internal fun liveTvBackToFilters(rowFocused: Boolean, searching: Boolean, fullScreen: Boolean, overlayOpen: Boolean): Boolean =
    rowFocused && !searching && !fullScreen && !overlayOpen

/** What the guide band's Back hint names (Spec 67 Q5, Spec 111 stage 7, D7, D16); null = no Back hint. */
internal enum class LiveTvGuideBack { NOW, CLOSE_SEARCH, FILTERS }

internal fun liveTvGuideBack(atNow: Boolean, gridFocused: Boolean, searching: Boolean): LiveTvGuideBack? = when {
    !atNow && gridFocused -> LiveTvGuideBack.NOW
    searching -> LiveTvGuideBack.CLOSE_SEARCH
    gridFocused -> LiveTvGuideBack.FILTERS
    else -> null
}
