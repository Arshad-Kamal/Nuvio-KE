package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowValue
import com.nuvio.tv.ui.components.marquee.MarqueeSwitchRow
import com.nuvio.tv.ui.components.marquee.MarqueeValueRow
import com.nuvio.tv.ui.components.marquee.formRowSurface
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

// Spec 31 S8: the Settings row kit (approved sandbox u11-settings-marquee v6). Rows are `.l8r`
// form rows (MarqueeFormRow) at the pane's 48 dp height. A dimmed row (`enabled = false`) stays
// focusable, ignores OK and carries its reason in the subtitle. A row whose setting never syncs
// (`thisTv`, from isLocalOnlyProfileSetting) ends its subtitle with "This TV".

/** Rail items and section chips select this long after focus lands (a quick pass selects nothing). */
internal const val SETTINGS_SELECT_ON_FOCUS_MS = 140L

@Composable
private fun rowSubtitle(subtitle: String?, thisTv: Boolean): String? {
    if (!thisTv) return subtitle
    val thisTvLabel = stringResource(R.string.settings_this_tv)
    return if (subtitle.isNullOrBlank()) thisTvLabel else "$subtitle · $thisTvLabel"
}

/** Attaches the shell's pane-entry requester when there is one (a pane's first row carries it). */
internal fun Modifier.optionalFocusRequester(requester: FocusRequester?): Modifier =
    if (requester != null) focusRequester(requester) else this

/** `.l11h` + `.l11hs`: the pane title and its category subtitle. */
@Composable
internal fun SettingsPaneHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = NuvioTheme.marquee.settings.headerToChips)) {
        Text(text = title, style = NuvioTheme.textStyles.settingsTitle, color = NuvioPrimitives.white)
        if (!subtitle.isNullOrBlank()) {
            Text(text = subtitle, style = NuvioTheme.textStyles.meta, color = NuvioTheme.marquee.onImage3)
        }
    }
}

/**
 * A Settings pane: header and [chips] pinned, rows in a LazyColumn under them, fading out at the
 * bottom. The pane never takes focus by itself: it focuses only through the requester the shell
 * hands it on explicit entry (rows attach it).
 */
@Composable
internal fun SettingsPane(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    chips: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.settings
    Column(modifier = modifier.fillMaxSize()) {
        SettingsPaneHeader(title = title, subtitle = subtitle)
        chips?.invoke()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(tokens.rowGap),
                contentPadding = PaddingValues(bottom = tokens.fadeHeight),
                content = content
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(tokens.fadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, NuvioTheme.colors.Background)))
            )
        }
    }
}

/** `.l11sec`: an uppercase section label. */
@Composable
internal fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.settings
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.eyebrow,
        color = NuvioTheme.marquee.onImage3,
        modifier = modifier.padding(
            start = NuvioTheme.spacing.xxs,
            end = NuvioTheme.spacing.xxs,
            top = tokens.sectionTopPadding,
            bottom = tokens.sectionBottomPadding
        )
    )
}

/** `.l11note`: a quiet line of explanation between rows ([color] for a warning or a status). */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier, color: Color = NuvioTheme.marquee.onImage3) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote,
        color = color,
        modifier = modifier.padding(horizontal = NuvioTheme.marquee.settings.notePadding)
    )
}

/**
 * A multi-line card drawn like a form row (fill at rest, glass + ring while focus is in it), for
 * Device assessment and Dolby Vision content. Always a focus stop so D-pad scrolling reaches all of
 * it; [onClick] null = read-only (OK does nothing).
 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.formRow
    var lit by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { lit = it.hasFocus }
            .formRowSurface(lit)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick?.invoke() }
            )
            .padding(horizontal = tokens.paddingHorizontal, vertical = tokens.paddingVertical),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
        content = content
    )
}

/** `.l11pass`: a quiet panel of read-only result lines ([SettingsResultLine]) under a row. */
@Composable
internal fun SettingsResultsPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = NuvioTheme.marquee.settings
    val shape = RoundedCornerShape(tokens.resultsRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.resultsFill, shape)
            .padding(horizontal = tokens.resultsPaddingHorizontal, vertical = tokens.resultsPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(tokens.resultsLineGap)
    ) {
        content()
    }
}

/** One `.l11pass` line: [label] (and an optional [detail] under it), [value] at the end. */
@Composable
internal fun SettingsResultLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    valueMuted: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = NuvioTheme.textStyles.settingsResultLabel, color = marquee.onImage2)
            if (detail != null) {
                Text(text = detail, style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage3)
            }
        }
        Text(
            text = value,
            style = NuvioTheme.textStyles.settingsStepValue,
            color = if (valueMuted) marquee.onImage3 else NuvioPrimitives.white,
            maxLines = 1
        )
    }
}

/**
 * A row that opens something (or shows a value when [trailingIcon] is null). [emphasizedValue]:
 * the value stays white at rest (a set value, Spec 52 `.vl.set`); [leadingIcon] precedes the title.
 */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    thisTv: Boolean = false,
    destructive: Boolean = false,
    dimmedValue: Boolean = false,
    emphasizedValue: Boolean = false,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    leadingIcon: ImageVector? = null
) {
    MarqueeValueRow(
        label = title,
        value = value,
        onClick = onClick,
        modifier = modifier,
        helper = rowSubtitle(subtitle, thisTv),
        dimmed = dimmedValue,
        emphasized = emphasizedValue,
        enabled = enabled,
        destructive = destructive,
        trailingIcon = trailingIcon,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight,
        leadingIcon = leadingIcon
    )
}

/** `.l11cs` row: a chip of [color], then [value] (the colour's name) and a chevron; OK opens its panel. */
@Composable
internal fun SettingsColorRow(
    title: String,
    color: Color,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val tokens = NuvioTheme.marquee.settings
    MarqueeFormRow(
        label = title,
        modifier = modifier,
        helper = subtitle,
        onClick = onClick,
        enabled = enabled,
        minHeight = tokens.rowMinHeight
    ) { lit ->
        Row(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(tokens.colorChipGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsColorChip(color)
            MarqueeFormRowValue(text = value, dimmed = false, lit = lit)
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/** The `.l11cs` chip: [color] (stored alpha included) in a rounded square with a quiet inset ring. */
@Composable
internal fun SettingsColorChip(color: Color, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.settings
    val shape = RoundedCornerShape(tokens.colorChipRadius)
    Box(
        modifier = modifier
            .size(tokens.colorChipSize)
            .clip(shape)
            .background(color, shape)
            .border(tokens.colorChipRingWidth, tokens.colorChipRing, shape)
    )
}

/** A switch row: OK flips [checked]. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    thisTv: Boolean = false
) {
    MarqueeSwitchRow(
        label = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        helper = rowSubtitle(subtitle, thisTv),
        enabled = enabled,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight
    )
}

/**
 * `.l8seg` row: Left / Right move the selection (consumed even at the ends, so focus never
 * leaves sideways), OK moves to the next option and wraps.
 */
@Composable
internal fun SettingsSegmentRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    thisTv: Boolean = false
) {
    val tokens = NuvioTheme.marquee.settings
    MarqueeFormRow(
        label = title,
        modifier = modifier.sideStepKeys(enabled) { delta ->
            val next = stepIndex(options.size, selectedIndex, delta)
            if (next != selectedIndex) onSelect(next)
        },
        helper = rowSubtitle(subtitle, thisTv),
        onClick = { if (options.isNotEmpty()) onSelect((selectedIndex + 1) % options.size) },
        enabled = enabled,
        minHeight = tokens.rowMinHeight
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.segmentGap)) {
            options.forEachIndexed { index, option ->
                SettingsPill(
                    text = option,
                    selected = index == selectedIndex,
                    focused = false,
                    height = tokens.segmentHeight,
                    paddingHorizontal = tokens.segmentPaddingHorizontal,
                    restingFill = tokens.segmentFill
                )
            }
        }
    }
}

/** `.l11st` stepper over a value list (ascending): Left / Right step, consumed even at the ends. */
@Composable
internal fun SettingsStepperRow(
    title: String,
    values: List<Int>,
    selected: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    thisTv: Boolean = false
) {
    SettingsStepperLayout(
        title = title,
        valueText = valueText,
        fraction = stepFraction(values, selected),
        onStep = { delta ->
            val next = stepValue(values, selected, delta)
            if (next != selected) onValueChange(next)
        },
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        thisTv = thisTv
    )
}

/** `.l11st` stepper over [min]..[max] in [step]s. */
@Composable
internal fun SettingsStepperRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    thisTv: Boolean = false
) {
    SettingsStepperLayout(
        title = title,
        valueText = valueText,
        fraction = stepFraction(min, max, value),
        onStep = { delta ->
            val next = stepValue(min, max, step, value, delta)
            if (next != value) onValueChange(next)
        },
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        thisTv = thisTv
    )
}

@Composable
private fun SettingsStepperLayout(
    title: String,
    valueText: String,
    fraction: Float,
    onStep: (delta: Int) -> Unit,
    modifier: Modifier,
    subtitle: String?,
    enabled: Boolean,
    thisTv: Boolean
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    MarqueeFormRow(
        label = title,
        modifier = modifier.sideStepKeys(enabled, onStep),
        helper = rowSubtitle(subtitle, thisTv),
        // Focusable for Left / Right; OK has nothing to do.
        onClick = {},
        enabled = enabled,
        minHeight = tokens.rowMinHeight
    ) { lit ->
        val chevronTint = if (lit) NuvioPrimitives.white else marquee.onImage3
        Row(
            horizontalArrangement = Arrangement.spacedBy(tokens.stepperGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.cd_decrease),
                tint = chevronTint,
                modifier = Modifier.size(marquee.formRow.iconSize)
            )
            Box(
                modifier = Modifier
                    .size(width = tokens.stepperTrackWidth, height = tokens.stepperTrackHeight)
                    .clip(CircleShape)
                    .background(tokens.stepperTrackFill)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(NuvioPrimitives.white)
                )
            }
            Text(
                text = valueText,
                style = NuvioTheme.textStyles.settingsStepValue,
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = tokens.stepperValueMinWidth)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_increase),
                tint = chevronTint,
                modifier = Modifier.size(marquee.formRow.iconSize)
            )
        }
    }
}

/**
 * Left / Right as -1 / +1 steps (mirrored in RTL), consumed even when the step changes nothing so
 * focus stays on the row. A dimmed row lets them through like a plain row.
 */
@Composable
internal fun Modifier.sideStepKeys(enabled: Boolean, onStep: (delta: Int) -> Unit): Modifier {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestOnStep by rememberUpdatedState(onStep)
    return onKeyEvent { event ->
        if (!enabled) return@onKeyEvent false
        val delta = when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isRtl) 1 else -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isRtl) -1 else 1
            else -> return@onKeyEvent false
        }
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) latestOnStep(delta)
        true
    }
}

/** A `.l8chips` / `.l8seg` pill: resting fill, selected glass + white inset ring, focused white. */
@Composable
internal fun SettingsPill(
    text: String,
    selected: Boolean,
    focused: Boolean,
    height: Dp,
    paddingHorizontal: Dp,
    restingFill: Color,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(
                when {
                    focused -> NuvioPrimitives.white
                    selected -> marquee.glassFill
                    else -> restingFill
                }
            )
            .then(
                if (selected && !focused) {
                    Modifier.border(marquee.settings.selectedRingWidth, NuvioPrimitives.white, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = paddingHorizontal),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = NuvioTheme.textStyles.panelPill,
            color = when {
                focused -> NuvioPrimitives.black
                selected -> NuvioPrimitives.white
                else -> marquee.onImage2
            },
            maxLines = 1
        )
    }
}

/**
 * `.l8chips`: a pane's sections, pinned under the header. A chip selects
 * [SETTINGS_SELECT_ON_FOCUS_MS] after focus lands on it (OK selects at once); entering the row
 * from above or below lands on the selected chip.
 */
@Composable
internal fun <T> SettingsSectionChips(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.settings
    val requesters = remember(items) { items.associateWith { FocusRequester() } }
    var focusedItem by remember { mutableStateOf<T?>(null) }
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    LaunchedEffect(focusedItem) {
        val item = focusedItem ?: return@LaunchedEffect
        if (item == latestSelected) return@LaunchedEffect
        delay(SETTINGS_SELECT_ON_FOCUS_MS)
        // OK during the dwell already selected it.
        if (item != latestSelected) latestOnSelect(item)
    }
    Row(
        modifier = modifier
            .padding(bottom = tokens.chipsBottomPadding)
            // Entering the row always lands on the selected chip (focusRestorer would return to
            // the last focused one, whose dwell may never have selected it).
            .focusProperties { onEnter = { requesters[latestSelected]?.requestFocus() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(tokens.chipGap)
    ) {
        items.forEach { item ->
            key(item) {
                var focused by remember { mutableStateOf(false) }
                SettingsPill(
                    text = label(item),
                    selected = item == selected,
                    focused = focused,
                    height = tokens.chipHeight,
                    paddingHorizontal = tokens.chipPaddingHorizontal,
                    restingFill = tokens.chipFill,
                    modifier = Modifier
                        .focusRequester(requesters.getValue(item))
                        .onFocusChanged { state ->
                            focused = state.isFocused
                            if (state.isFocused) {
                                focusedItem = item
                            } else if (focusedItem == item) {
                                focusedItem = null
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(item) }
                        )
                )
            }
        }
    }
}

/**
 * Which panes have their "Advanced options" group open. Saved with the Settings back-stack entry:
 * a group stays open while Settings (or a sub-route over it) is on the stack and closes once
 * Settings is left.
 */
internal class SettingsAdvancedGroups(private val openKeys: SnapshotStateList<String>) {
    fun isOpen(paneKey: String): Boolean = paneKey in openKeys
    fun toggle(paneKey: String) {
        if (!openKeys.remove(paneKey)) openKeys.add(paneKey)
    }
}

@Composable
internal fun rememberSettingsAdvancedGroups(): SettingsAdvancedGroups {
    val openKeys = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf<String>() }
    return remember(openKeys) { SettingsAdvancedGroups(openKeys) }
}

/** Provided by the Settings shell; null outside it (a pane then keeps its own state). */
internal val LocalSettingsAdvancedGroups = compositionLocalOf<SettingsAdvancedGroups?> { null }

/** The shell's group state, or a pane-local one outside the shell. */
@Composable
internal fun settingsAdvancedGroups(): SettingsAdvancedGroups =
    LocalSettingsAdvancedGroups.current ?: rememberSettingsAdvancedGroups()

/**
 * A pane's collapsed "Advanced options" group (D2, v4 "divider pill"): the pill line, then
 * [rows] while [open]. OK flips it in place; focus stays on the pill.
 */
internal fun LazyListScope.settingsAdvancedGroup(
    key: String,
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    rows: LazyListScope.() -> Unit
) {
    item(key = "advanced-group-$key") {
        SettingsAdvancedPill(count = count, open = open, onToggle = onToggle)
    }
    if (open) rows()
}

/** `.l11rl`: a hairline each side of the "Advanced options · N" / "Hide advanced options" pill. */
@Composable
internal fun SettingsAdvancedPill(
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    var focused by remember { mutableStateOf(false) }
    val content = if (focused) NuvioPrimitives.black else marquee.onImage2
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(tokens.groupGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f).height(NuvioTheme.spacing.hairline).background(tokens.groupRule))
        Row(
            modifier = Modifier
                .height(tokens.groupPillHeight)
                .clip(CircleShape)
                .background(if (focused) NuvioPrimitives.white else tokens.chipFill)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                )
                .padding(horizontal = tokens.groupPillPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (open) {
                    stringResource(R.string.settings_advanced_options_hide)
                } else {
                    stringResource(R.string.settings_advanced_options_count, count)
                },
                style = NuvioTheme.textStyles.panelPill,
                color = content,
                maxLines = 1
            )
            Icon(
                imageVector = if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(tokens.groupIconSize)
            )
        }
        Spacer(modifier = Modifier.weight(1f).height(NuvioTheme.spacing.hairline).background(tokens.groupRule))
    }
}
