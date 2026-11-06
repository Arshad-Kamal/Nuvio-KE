@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.components.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.core.stream.limitVerdict
import com.nuvio.tv.core.stream.resolutionLabel
import com.nuvio.tv.core.stream.resolutionVerdict
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch as coroutineLaunch
import com.nuvio.tv.ui.components.marquee.MarqueeGlassButton
import com.nuvio.tv.ui.components.marquee.MarqueeSourceChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * The chips after the refresh, in row order (Spec 51 P6): All, the limit chip while the list hides
 * streams, then one per addon of [orderedNames]. Highlight, focus and Left/Right go by these
 * values, never by a fixed position (M7).
 */
internal fun chipRowSelections(orderedNames: List<String>, hiddenCount: Int): List<StreamListSelection> =
    buildList {
        add(StreamListSelection.All)
        if (hiddenCount > 0) add(StreamListSelection.Hidden)
        orderedNames.mapTo(this) { StreamListSelection.Addon(it) }
    }

/** The item [step] places from [item] in this list; null past either end, or when [item] is not in it. */
internal fun <T> List<T>.stepFrom(item: T, step: Int): T? =
    indexOf(item).takeIf { it >= 0 }?.let { getOrNull(it + step) }

/** Left/Right as a step along a row in reading order (mirrored in RTL); null for any other key. */
internal fun horizontalStep(key: Key, isRtl: Boolean): Int? = when (key) {
    Key.DirectionLeft -> if (isRtl) 1 else -1
    Key.DirectionRight -> if (isRtl) -1 else 1
    else -> null
}

/** A focus position in the chip row: the refresh, or one of its chips. */
internal sealed interface ChipSlot {
    data object Refresh : ChipSlot
    data class Chip(val selection: StreamListSelection) : ChipSlot
}

/**
 * Left/Right in the chip row: the slot [step] places from [focused] along the refresh, then
 * [chips]; a focused chip that left the row reads as the [selection]'s chip. Null past either end.
 */
internal fun chipRowStep(
    chips: List<StreamListSelection>,
    focused: ChipSlot,
    selection: StreamListSelection,
    step: Int
): ChipSlot? {
    val slots = listOf(ChipSlot.Refresh) + chips.map { ChipSlot.Chip(it) }
    val from = focused.takeIf { it in slots } ?: ChipSlot.Chip(selection)
    return slots.stepFrom(from, step)
}

/**
 * The Marquee chip row: refresh, then [chips] ([chipRowSelections]): "All", the amber Hidden chip
 * (named by [hiddenChipName]: "Over 72 Mbps · 4", "Over 1080p · 2" or "Hidden · 6"), one chip per addon. The
 * caller owns the focus requesters ([refreshFocusRequester], [chipFocusRequesters] per chip).
 * Left/Right move along the row ([chipRowStep]) and select on focus. [view] gives the effective
 * selection, which every highlight and focus rule reads, and each chip's count; [isStillFetching]
 * spins the "All" chip (the refresh is static; addon chips spin by their own status). The row
 * writes no selection back: [view]'s is already the derivation's fallback (Spec 51 n1).
 */
@Composable
internal fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem>,
    view: StreamListView,
    chips: List<StreamListSelection>,
    isStillFetching: Boolean,
    onRefresh: () -> Unit,
    onSelected: (StreamListSelection) -> Unit,
    refreshFocusRequester: FocusRequester,
    chipFocusRequesters: Map<StreamListSelection, FocusRequester>
) {
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val chipMap = sourceChips.associateBy { it.name }
    val selection = view.selection

    var chipRowHasFocus by remember { mutableStateOf(false) }
    var refreshHasFocus by remember { mutableStateOf(false) }
    // The row's focus position. The refresh is focusable only while it is the position, which
    // only Left from the first chip sets, so focus entering the row never lands on it.
    var focusedSlot by remember { mutableStateOf<ChipSlot>(ChipSlot.Chip(selection)) }
    fun requesterOf(slot: ChipSlot): FocusRequester? = when (slot) {
        ChipSlot.Refresh -> refreshFocusRequester
        is ChipSlot.Chip -> chipFocusRequesters[slot.selection]
    }
    // Read in the composition that brings a new selection or chips, before the apply disposes a
    // focused chip that left the row, after which the row reports focus lost (R4-6).
    val rowHeldFocus = remember(selection, chips) { chipRowHasFocus }
    LaunchedEffect(selection, chips) {
        val selected = ChipSlot.Chip(selection)
        if (focusedSlot == ChipSlot.Refresh || focusedSlot == selected) return@LaunchedEffect
        focusedSlot = selected
        // The focused chip left (the limit chip once nothing is hidden, a removed addon) and the
        // selection fell back: the chip it fell back to takes the row's focus.
        if (rowHeldFocus) chipFocusRequesters[selection]?.requestFocusAfterFrames(frames = 1)
    }
    val scope = rememberCoroutineScope()
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    fun moveFocusTo(target: ChipSlot) {
        focusedSlot = target
        refreshHasFocus = target == ChipSlot.Refresh
        if (target is ChipSlot.Chip) onSelected(target.selection)
        scope.coroutineLaunch {
            requesterOf(target)?.requestFocusAfterFrames(frames = 1)
        }
    }

    val chipListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LazyRow(
        state = chipListState,
        // Marquee chips draw no outer focus ring, so the row needs no padding for one.
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.stream.chipGap),
        modifier = Modifier
            .onFocusChanged { focusState ->
                val hasFocus = focusState.hasFocus
                if (hasFocus && !chipRowHasFocus && isRtl) {
                    scope.coroutineLaunch {
                        withFrameNanos {}
                        requesterOf(focusedSlot)?.requestFocus()
                    }
                }
                chipRowHasFocus = hasFocus
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                // Throttle rapid key repeats (long-press)
                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }

                val step = horizontalStep(event.key, isRtl) ?: return@onKeyEvent false
                // Consumed at either end so focus never leaves the row sideways.
                chipRowStep(chips, focusedSlot, selection, step)?.let(::moveFocusTo)
                true
            }
    ) {
        item(key = REFRESH_KEY) {
            MarqueeGlassButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.cd_refresh),
                onClick = onRefresh,
                modifier = Modifier
                    .focusRequester(refreshFocusRequester)
                    .focusProperties { canFocus = focusedSlot == ChipSlot.Refresh }
                    .onFocusChanged {
                        refreshHasFocus = it.isFocused
                        if (it.isFocused) focusedSlot = ChipSlot.Refresh
                    },
                size = NuvioTheme.marquee.stream.refreshSize
            )
        }

        // Keyed by chip, so a chip arriving before the focused one (the limit chip, a new addon)
        // shifts the row without moving focus or the highlight onto another chip.
        items(chips, key = ::chipRowKey) { chip ->
            val requester = chipFocusRequesters.getValue(chip)
            val chipModifier = Modifier
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedSlot = ChipSlot.Chip(chip)
                        refreshHasFocus = false
                    }
                }
            when (chip) {
                StreamListSelection.All -> MarqueeSourceChip(
                    name = stringResource(R.string.stream_filter_all),
                    isSelected = selection == chip && !refreshHasFocus,
                    status = if (isStillFetching) SourceChipStatus.LOADING else SourceChipStatus.SUCCESS,
                    isSelectable = true,
                    count = view.allCount,
                    onClick = { onSelected(chip) },
                    modifier = chipModifier
                )
                StreamListSelection.Hidden -> MarqueeSourceChip(
                    name = hiddenChipName(view),
                    isSelected = selection == chip,
                    status = SourceChipStatus.SUCCESS,
                    isSelectable = true,
                    count = view.hiddenCount,
                    onClick = { onSelected(chip) },
                    modifier = chipModifier,
                    warning = true
                )
                is StreamListSelection.Addon -> {
                    val chipStatus = chipMap[chip.name]?.status ?: SourceChipStatus.SUCCESS
                    val isSelectable = chip.name in addons && chipStatus == SourceChipStatus.SUCCESS
                    MarqueeSourceChip(
                        name = chip.name,
                        isSelected = selection == chip,
                        status = chipStatus,
                        isSelectable = isSelectable,
                        count = view.addonCounts[chip.name],
                        onClick = { if (isSelectable) onSelected(chip) },
                        modifier = chipModifier
                    )
                }
            }
        }
    }
}

private const val REFRESH_KEY = "refresh"

/**
 * A chip's LazyRow key (CMP-LAZY-01): stable per chip, never equal to [REFRESH_KEY]; unique
 * because [com.nuvio.tv.core.stream.sourceChipAddonNames] lists each addon name once.
 */
private fun chipRowKey(chip: StreamListSelection): String = when (chip) {
    StreamListSelection.All -> "all"
    StreamListSelection.Hidden -> "hidden"
    is StreamListSelection.Addon -> "addon:${chip.name}"
}

/**
 * The Hidden chip's name (Spec 58 D3): the one reason present names it ("Over 72 Mbps", "Over 1080p"),
 * both read "Hidden".
 */
@Composable
private fun hiddenChipName(view: StreamListView): String {
    val bitrate = view.limitVerdict
    val resolution = view.resolutionVerdict
    return when {
        bitrate != null && resolution != null -> stringResource(R.string.stream_limit_chip_both)
        resolution != null -> stringResource(R.string.stream_limit_over_resolution, resolutionLabel(resolution.cap))
        else -> stringResource(R.string.stream_limit_chip, bitrate?.figureMbps ?: 0)
    }
}
