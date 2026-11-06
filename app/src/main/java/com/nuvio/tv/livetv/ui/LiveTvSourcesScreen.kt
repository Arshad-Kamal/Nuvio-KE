package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.screens.addon.RenamePanel
import com.nuvio.tv.ui.screens.addon.StripListRow
import com.nuvio.tv.ui.screens.addon.addonFocusKeyAfterRemoval
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.ServePendingFocus
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Focus targets of the Add source URL and Hidden channels buttons and the suggested guides' Add buttons (not source ids). */
private const val ADD_TARGET = "#add"
private const val HIDDEN_TARGET = "#hidden"
private const val PLUTO_TARGET = "#pluto"
private const val INDIA_TARGET = "#india"

/** Longest source name the rename panel takes. */
private const val SOURCE_NAME_MAX = 40

/** Longest URL the add field takes (a query-configured addon URL can be long). */
private const val URL_MAX = 2048

/**
 * Live TV › Sources (approved sandbox `u34-live-tv-marquee` v3 stages `lv-sources`, `lv-add`; Spec
 * 34 D16, L7e): breadcrumb; title + "n channel sources · n guides · on this TV"; Add source URL,
 * Refresh all, Hidden channels; the suggested Pluto and India guides when they apply (Spec 38
 * stage 6; Spec 49 D27 F5); one
 * row per source (type, name, what it gave, last refresh, health), then the iptv-org channel data
 * row. OK on a source row opens Refresh / Rename / Remove
 * under it (the Addons route's strip). [openAdd]: opened from the empty state's "Add source URL",
 * the Add source dialog shows at once. A suggestion's Add button sits at the row's end, which
 * spatial search from the left-aligned buttons above and the full-width rows below skipped
 * (Shield 2026-11-02, 12/N-2): Down from the buttons and Up from the first source row go to it
 * explicitly.
 */
@Composable
fun LiveTvSourcesScreen(
    openAdd: Boolean,
    viewModel: LiveTvSourcesViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    val ui by viewModel.uiState.collectAsState()
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val tokens = marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    val toast = LocalMarqueeToast.current
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val focusTargets = rememberCollectionFocusTargets()
    // Opened from the empty state: straight to the dialog (a new ViewModel starts with it empty).
    var addOpen by rememberSaveable { mutableStateOf(openAdd) }
    var hiddenOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SourceRow?>(null) }
    var removing by remember { mutableStateOf<SourceRow?>(null) }

    BackHandler { onBackPress() }

    val openAddDialog = {
        viewModel.openAdd()
        addOpen = true
    }
    // First focus once the list has loaded: Add source URL (stage 14); the dialog's dismiss does it
    // when the route opened on the dialog.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(ui.loading) {
        if (!entered && !ui.loading) {
            entered = true
            if (!addOpen) focusTargets.pending = ADD_TARGET
        }
    }
    // No fallback: a just-added source's row may compose a frame after the Added event.
    focusTargets.ServePendingFocus(content = ui.rows)
    // The suggestion rows shown, in composition order; an override names only a composed row.
    val suggestions = listOfNotNull(ui.pluto?.let { PLUTO_TARGET }, ui.india?.let { INDIA_TARGET })
    val firstSuggestion = suggestions.firstOrNull()?.let(focusTargets::requester) ?: FocusRequester.Default
    val lastSuggestion = suggestions.lastOrNull()?.let(focusTargets::requester) ?: FocusRequester.Default
    val downToSuggestion = Modifier.focusProperties { down = firstSuggestion }

    val renameFailed = stringResource(R.string.live_tv_rename_failed)
    val removeFailed = stringResource(R.string.live_tv_remove_failed)
    val unhideFailed = stringResource(R.string.live_tv_unhide_failed)
    val plutoFailed = stringResource(R.string.live_tv_pluto_add_failed)
    val indiaFailed = stringResource(R.string.live_tv_india_add_failed)
    val addedFormat = stringResource(R.string.live_tv_added)
    LaunchedEffect(viewModel) {
        viewModel.event.collect { event ->
            when (event) {
                is SourcesEvent.Added -> {
                    addOpen = false
                    focusTargets.pending = event.id
                    toast?.show(String.format(addedFormat, event.name))
                }
                SourcesEvent.RenameFailed -> toast?.show(renameFailed)
                SourcesEvent.RemoveFailed -> toast?.show(removeFailed)
                SourcesEvent.UnhideFailed -> toast?.show(unhideFailed)
                SourcesEvent.PlutoAddFailed -> toast?.show(plutoFailed)
                SourcesEvent.IndiaAddFailed -> toast?.show(indiaFailed)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.listTop)
                .verticalScroll(rememberScrollState())
                .padding(start = safeHorizontal, end = safeHorizontal, bottom = NuvioTheme.layout.tvSafeVertical),
            verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.live_tv_sources),
                    style = NuvioTheme.textStyles.settingsTitle,
                    color = NuvioPrimitives.white
                )
                if (!ui.loading) {
                    Text(
                        text = listOf(
                            pluralStringResource(R.plurals.live_tv_channel_sources, ui.channelSources, ui.channelSources),
                            pluralStringResource(R.plurals.live_tv_guides, ui.guides, ui.guides),
                            stringResource(R.string.live_tv_on_this_tv)
                        ).joinToString(FACT_SEPARATOR),
                        style = NuvioTheme.textStyles.meta,
                        color = marquee.onImage3,
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
                    )
                }
            }
            Row(
                modifier = Modifier.padding(bottom = tokens.rowGap),
                horizontalArrangement = Arrangement.spacedBy(live.sourcesButtonGap)
            ) {
                SourcesButton(
                    label = stringResource(R.string.live_tv_add_source_url),
                    icon = Icons.Filled.Add,
                    onClick = openAddDialog,
                    modifier = downToSuggestion.focusRequester(focusTargets.requester(ADD_TARGET))
                )
                SourcesButton(
                    label = stringResource(R.string.live_tv_refresh_all),
                    icon = Icons.Filled.Refresh,
                    onClick = viewModel::refreshAll,
                    modifier = downToSuggestion
                )
                SourcesButton(
                    label = if (ui.hidden.isEmpty()) stringResource(R.string.live_tv_hidden_channels)
                    else stringResource(R.string.live_tv_hidden_channels_count, ui.hidden.size),
                    icon = Icons.Filled.Visibility,
                    onClick = { hiddenOpen = true },
                    modifier = downToSuggestion.focusRequester(focusTargets.requester(HIDDEN_TARGET))
                )
            }
            ui.pluto?.let { suggestion ->
                val count = numbers.format(suggestion.channels)
                GuideSuggestionRow(
                    title = stringResource(R.string.live_tv_pluto_title),
                    facts = listOf(
                        suggestion.sourceName?.let {
                            pluralStringResource(R.plurals.live_tv_pluto_in_source, suggestion.channels, count, it)
                        } ?: pluralStringResource(R.plurals.live_tv_pluto_in_sources, suggestion.channels, count),
                        pluralStringResource(R.plurals.live_tv_pluto_covers, suggestion.channels),
                        stringResource(R.string.live_tv_pluto_host)
                    ),
                    addLabel = stringResource(R.string.live_tv_pluto_add),
                    onAdd = viewModel::addPlutoGuide,
                    focusRequester = focusTargets.requester(PLUTO_TARGET)
                )
            }
            ui.india?.let { suggestion ->
                val count = numbers.format(suggestion.channels)
                GuideSuggestionRow(
                    title = stringResource(R.string.live_tv_india_title),
                    facts = listOf(
                        suggestion.sourceName?.let {
                            pluralStringResource(R.plurals.live_tv_india_in_source, suggestion.channels, count, it)
                        } ?: pluralStringResource(R.plurals.live_tv_india_in_sources, suggestion.channels, count),
                        pluralStringResource(R.plurals.live_tv_india_covers, suggestion.channels),
                        stringResource(R.string.live_tv_india_hosts)
                    ),
                    addLabel = stringResource(R.string.live_tv_india_add),
                    onAdd = viewModel::addIndiaGuides,
                    focusRequester = focusTargets.requester(INDIA_TARGET)
                )
            }
            ui.rows.forEachIndexed { index, row ->
                key(row.source.id) {
                    SourceListRow(
                        row = row,
                        position = index,
                        focusRequester = focusTargets.requester(row.source.id),
                        up = if (index == 0) lastSuggestion else FocusRequester.Default,
                        timeFormat = timeFormat,
                        numbers = numbers,
                        onRefresh = { viewModel.refresh(row.source.id) },
                        onRename = { renaming = row },
                        onRemove = { removing = row }
                    )
                }
            }
            ui.metadataFetchedAtMs?.let { fetchedAt ->
                MetadataRow(fetchedAtMs = fetchedAt, position = ui.rows.size, timeFormat = timeFormat)
            }
        }
        MarqueeBreadcrumb(listOf(stringResource(R.string.nav_live_tv), stringResource(R.string.live_tv_sources)))
    }

    if (addOpen) {
        AddSourcePanel(
            viewModel = viewModel,
            numbers = numbers,
            onDismiss = {
                viewModel.closeAdd()
                addOpen = false
                focusTargets.pending = ADD_TARGET
            }
        )
    }

    if (hiddenOpen) {
        HiddenChannelsPanel(
            hidden = ui.hidden,
            onUnhide = viewModel::unhide,
            onDismiss = {
                hiddenOpen = false
                focusTargets.pending = HIDDEN_TARGET
            }
        )
    }

    renaming?.let { row ->
        val close = {
            renaming = null
            focusTargets.pending = row.source.id
        }
        RenamePanel(
            eyebrow = stringResource(R.string.live_tv_src_rename_eyebrow),
            current = row.source.name,
            originalName = row.source.name,
            resetLabel = null,
            note = stringResource(R.string.live_tv_src_rename_note),
            maxLength = SOURCE_NAME_MAX,
            onSave = { name ->
                // A blank name keeps the current one.
                if (name.isNotBlank()) viewModel.rename(row.source.id, name)
                close()
            },
            onDismiss = close
        )
    }

    removing?.let { row ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.live_tv_src_remove_eyebrow, row.source.name),
            body = stringResource(
                if (row.source.kind == SourceKind.XMLTV) R.string.live_tv_src_remove_guide_body
                else R.string.live_tv_src_remove_channels_body
            ),
            confirmLabel = stringResource(R.string.live_tv_src_remove),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                focusTargets.pending = addonFocusKeyAfterRemoval(ui.rows.map { it.source.id }, row.source.id)
                    ?: ADD_TARGET
                viewModel.remove(row.source.id)
                removing = null
            },
            onDismiss = {
                removing = null
                focusTargets.pending = row.source.id
            },
            destructive = true
        )
    }
}

/** `.lvsbt .btn`: a glass pill with its icon. */
@Composable
private fun SourcesButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val live = NuvioTheme.marquee.liveTv
    MarqueeGlassTextButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        height = live.buttonHeight,
        horizontalPadding = live.sourcesButtonPaddingHorizontal,
        textStyle = NuvioTheme.textStyles.panelButtonSmall,
        icon = icon
    )
}

/**
 * Stage 14 row (`.lvsr`): type, name over "what it gave · last refresh", health, chevron. While
 * focus is in the row a failed or partial refresh shows its reason under the line.
 */
@Composable
private fun SourceListRow(
    row: SourceRow,
    position: Int,
    focusRequester: FocusRequester,
    up: FocusRequester,
    timeFormat: DateFormat,
    numbers: NumberFormat,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val source = row.source
    StripListRow(
        focusRequester = focusRequester,
        position = position,
        dimmedAtRest = false,
        up = up,
        actions = {
            CollectionRowAction(label = stringResource(R.string.live_tv_src_refresh), onClick = onRefresh)
            CollectionRowAction(label = stringResource(R.string.live_tv_src_rename), onClick = onRename)
            CollectionRowAction(label = stringResource(R.string.live_tv_src_remove), onClick = onRemove, destructive = true)
        }
    ) { focusWithin ->
        SourceType(
            stringResource(
                when (source.kind) {
                    SourceKind.M3U -> R.string.live_tv_type_playlist
                    SourceKind.STREMIO -> R.string.live_tv_type_addon
                    SourceKind.XMLTV -> R.string.live_tv_type_guide
                }
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            SourceName(source.name)
            SourceLine(sourceLine(row, timeFormat, numbers))
            val error = source.lastError
            if (focusWithin && error != null) {
                Text(
                    text = stringResource(R.string.live_tv_src_last_error, error),
                    style = NuvioTheme.textStyles.caption,
                    color = healthColor(row.health),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HealthLabel(row.health)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = marquee.onImage3,
            modifier = Modifier.size(NuvioTheme.marquee.liveTv.sourcesIconSize)
        )
    }
}

/**
 * Spec 38 stage 6 (`.lvsr.l38sug`; Spec 49 D27 F5 reuses it for the India guides): SUGGESTED, the
 * guide's name over who holds the channels it covers, then the add button (the row's one focus
 * stop, [focusRequester]). Gone once a source has the guide's URL; adding moves focus to the new
 * guide row (the Added event).
 */
@Composable
private fun GuideSuggestionRow(
    title: String,
    facts: List<String>,
    addLabel: String,
    onAdd: () -> Unit,
    focusRequester: FocusRequester
) {
    val live = NuvioTheme.marquee.liveTv
    val tokens = NuvioTheme.marquee.collections
    val shape = RoundedCornerShape(tokens.rowRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(live.suggestFill)
            .border(1.dp, live.suggestEdge, shape)
            .padding(horizontal = tokens.rowPaddingHorizontal, vertical = tokens.rowPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(tokens.rowInnerGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceType(stringResource(R.string.live_tv_type_suggested), NuvioTheme.marquee.readyText)
        Column(modifier = Modifier.weight(1f)) {
            SourceName(title)
            SourceLine(facts.joinToString(FACT_SEPARATOR))
        }
        MarqueePrimaryButton(
            label = addLabel,
            icon = null,
            onClick = onAdd,
            focusRequester = focusRequester,
            compact = true
        )
    }
}

/** The DATA row: iptv-org channel data, refreshed on its own every 3 days (view only). */
@Composable
private fun MetadataRow(fetchedAtMs: Long, position: Int, timeFormat: DateFormat) {
    val focusRequester = remember { FocusRequester() }
    StripListRow(focusRequester = focusRequester, position = position, dimmedAtRest = false, actions = null) {
        SourceType(stringResource(R.string.live_tv_type_data))
        Column(modifier = Modifier.weight(1f)) {
            SourceName(stringResource(R.string.live_tv_src_metadata_name))
            SourceLine(
                listOf(
                    stringResource(R.string.live_tv_src_metadata_line),
                    refreshedPhrase(fetchedAtMs, timeFormat)
                ).joinToString(FACT_SEPARATOR)
            )
        }
    }
}

/**
 * `.lvsr .ty`: the type label in its fixed column, on one line. A label wider than the column
 * (SUGGESTED draws ~70 dp on the Shield) runs into the gap after it, as the mockup's unwrapped
 * `width:66px` box does, instead of breaking the word (12/N-1); the column stays put so
 * every row's name lines up.
 */
@Composable
private fun SourceType(text: String, color: Color = NuvioTheme.marquee.onImage3) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.techBadge,
        color = color,
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = 1,
        modifier = Modifier.width(NuvioTheme.marquee.liveTv.sourcesTypeWidth)
    )
}

@Composable
private fun SourceName(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.button,
        color = NuvioPrimitives.white,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SourceLine(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * "Built in · 7 channels · DW English, ...", "M3U · 103 channels · refreshed today 06:10",
 * "Stremio TV addon · 38 channels · ...", "XMLTV · 2,289 channels · 41 matched · ...".
 */
@Composable
private fun sourceLine(row: SourceRow, timeFormat: DateFormat, numbers: NumberFormat): String {
    val source = row.source
    val channels = row.listed?.let { stringResource(R.string.live_tv_src_channels, numbers.format(it)) }
    val refreshed = refreshedPhrase(source.lastRefreshMs, timeFormat)
    val parts = when {
        source.id == LiveSourceManager.PRESET_ID ->
            listOf(stringResource(R.string.live_tv_src_built_in), channels, row.preview.joinToString(", ").ifEmpty { null })
        source.kind == SourceKind.M3U -> listOf(stringResource(R.string.live_tv_src_m3u), channels, refreshed)
        source.kind == SourceKind.STREMIO -> listOf(stringResource(R.string.live_tv_src_addon), channels, refreshed)
        else -> listOf(
            stringResource(R.string.live_tv_src_xmltv),
            channels,
            row.matched?.takeIf { row.listed != null }?.let { stringResource(R.string.live_tv_src_matched, numbers.format(it)) },
            refreshed
        )
    }
    return parts.filterNotNull().joinToString(FACT_SEPARATOR)
}

@Composable
private fun refreshedPhrase(lastMs: Long?, timeFormat: DateFormat): String =
    when (val age = LiveTvSourcesModel.refreshAge(lastMs, System.currentTimeMillis())) {
        RefreshAge.Never -> stringResource(R.string.live_tv_refreshed_never)
        else -> stringResource(R.string.live_tv_refreshed, liveTvAgePhrase(age, timeFormat))
    }

/** An age in words: "just now", "12 min ago", "today 14:02", "yesterday", "3 days ago" (Never reads as just now). */
@Composable
internal fun liveTvAgePhrase(age: RefreshAge, timeFormat: DateFormat): String = when (age) {
    RefreshAge.Never, RefreshAge.JustNow -> stringResource(R.string.live_tv_ago_just_now)
    is RefreshAge.Minutes -> stringResource(R.string.live_tv_ago_minutes, age.count)
    is RefreshAge.Today -> stringResource(R.string.live_tv_ago_today, timeFormat.format(Date(age.atMs)))
    is RefreshAge.Days -> if (age.count == 1) stringResource(R.string.live_tv_ago_yesterday)
    else stringResource(R.string.live_tv_ago_days, age.count)
}

@Composable
private fun healthColor(health: SourceHealth) = when (health) {
    SourceHealth.Ok -> NuvioTheme.marquee.readyText
    is SourceHealth.Lost, SourceHealth.Partial -> NuvioTheme.marquee.liveTv.warn
    SourceHealth.Failed -> NuvioPrimitives.errorOnImage
    SourceHealth.Waiting, SourceHealth.Refreshing -> NuvioTheme.marquee.onImage3
}

@Composable
private fun HealthLabel(health: SourceHealth) {
    Text(
        text = when (health) {
            SourceHealth.Ok -> stringResource(R.string.live_tv_health_ok)
            is SourceHealth.Lost -> stringResource(R.string.live_tv_health_lost, health.count)
            SourceHealth.Partial -> stringResource(R.string.live_tv_health_partial)
            SourceHealth.Failed -> stringResource(R.string.live_tv_health_failed)
            SourceHealth.Waiting -> stringResource(R.string.live_tv_health_waiting)
            SourceHealth.Refreshing -> stringResource(R.string.live_tv_health_refreshing)
        },
        style = NuvioTheme.textStyles.techBadge,
        color = healthColor(health),
        maxLines = 1
    )
}

/**
 * Stage 15: one URL field (OK opens the keyboard; its Done runs the check and moves to Add), the
 * check's line (type and count, or the refusal reason), Cancel / Add. Add stays dimmed until the
 * check passes. Back or Cancel calls [onDismiss].
 */
@Composable
private fun AddSourcePanel(viewModel: LiveTvSourcesViewModel, numbers: NumberFormat, onDismiss: () -> Unit) {
    val live = NuvioTheme.marquee.liveTv
    val stream = NuvioTheme.marquee.stream
    val url by viewModel.addUrl.collectAsState()
    val check by viewModel.addCheck.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = live.addVeil,
        width = live.addPanelWidth,
        initialFocus = fieldFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.live_tv_add_eyebrow))
        Text(
            text = stringResource(R.string.live_tv_add_body),
            style = NuvioTheme.textStyles.panelBody,
            color = stream.dialogBody,
            modifier = Modifier.padding(horizontal = stream.dialogTextInset)
        )
        // A URL reads left to right in RTL locales too.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.padding(horizontal = stream.dialogTextInset)) {
                MarqueeNameField(
                    value = url,
                    onValueChange = viewModel::onAddUrlChange,
                    maxLength = URL_MAX,
                    placeholder = stringResource(R.string.live_tv_add_placeholder),
                    focusRequester = fieldFocus,
                    onDone = {
                        viewModel.checkAdd()
                        addFocus.requestFocus()
                    },
                    keyboardType = KeyboardType.Uri,
                    showCounter = false
                )
            }
        }
        AddCheckLine(check, numbers)
        MarqueePanelActions {
            MarqueeGlassTextButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            val ready = check is AddCheck.Ready
            MarqueeGlassTextButton(
                label = stringResource(if (check == AddCheck.Adding) R.string.live_tv_adding else R.string.live_tv_add),
                // Dimmed but focusable until the check passes, so Done can land here.
                onClick = { if (ready) viewModel.confirmAdd() },
                restingLabelColor = if (ready) NuvioPrimitives.white else NuvioTheme.marquee.onImage3,
                modifier = Modifier.focusRequester(addFocus)
            )
        }
    }
}

/** `.lvdet`: what the check found (green with a tick), the refusal reason, or "Checking…". */
@Composable
private fun AddCheckLine(check: AddCheck, numbers: NumberFormat) {
    val marquee = NuvioTheme.marquee
    val (text, color) = when (check) {
        AddCheck.Idle, AddCheck.Adding -> return
        AddCheck.Checking -> stringResource(R.string.live_tv_add_checking) to marquee.onImage2
        is AddCheck.Refused -> check.reason to NuvioPrimitives.errorOnImage
        is AddCheck.Ready -> {
            val listed = numbers.format(check.checked.listed)
            when (check.checked.source.kind) {
                SourceKind.M3U -> stringResource(R.string.live_tv_add_ready_m3u, listed)
                // A guide's channelCount is the channels it matched (LiveSource).
                SourceKind.XMLTV -> stringResource(
                    R.string.live_tv_add_ready_xmltv,
                    listed,
                    numbers.format(check.checked.source.channelCount)
                )
                SourceKind.STREMIO -> stringResource(R.string.live_tv_add_ready_addon, listed)
            } to marquee.readyText
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = NuvioTheme.marquee.stream.dialogTextInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        if (check is AddCheck.Ready) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(NuvioTheme.marquee.liveTv.pillIconSize)
            )
        }
        Text(text = text, style = NuvioTheme.textStyles.caption, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Hidden channels (no v3 stage; the hold-OK panel's shape): one row per hidden channel (name, its
 * source); OK shows it again and focus moves to its neighbour. Back calls [onDismiss].
 */
@Composable
private fun HiddenChannelsPanel(hidden: List<HiddenRow>, onUnhide: (String) -> Unit, onDismiss: () -> Unit) {
    val live = NuvioTheme.marquee.liveTv
    val targets = rememberCollectionFocusTargets()
    val initial = remember { targets.requester(hidden.firstOrNull()?.key ?: EMPTY_KEY) }
    // After an unhide: its neighbour, else the empty row once the list has emptied.
    targets.ServePendingFocus(content = hidden)
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = live.optionsPanelWidth,
        insetVertical = live.panelInsetVertical,
        initialFocus = initial,
        fillHeight = false
    ) {
        PanelEyebrow(text = stringResource(R.string.live_tv_hidden_channels))
        if (hidden.isEmpty()) {
            PlayerPanelRow(
                title = stringResource(R.string.live_tv_hidden_empty),
                selected = false,
                enabled = false,
                focusRequester = targets.requester(EMPTY_KEY),
                onClick = {}
            )
        } else {
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                hidden.forEach { row ->
                    key(row.key) {
                        PlayerPanelRow(
                            title = row.name,
                            subtitle = row.sourceName,
                            selected = false,
                            trailingIcon = Icons.Filled.Visibility,
                            focusRequester = targets.requester(row.key),
                            onClick = {
                                targets.pending = addonFocusKeyAfterRemoval(hidden.map { it.key }, row.key) ?: EMPTY_KEY
                                onUnhide(row.key)
                            }
                        )
                    }
                }
            }
        }
        PanelNote(stringResource(R.string.live_tv_hidden_note))
    }
}

private const val EMPTY_KEY = "#empty"
