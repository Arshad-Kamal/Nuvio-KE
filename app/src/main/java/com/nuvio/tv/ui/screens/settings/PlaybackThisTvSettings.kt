package com.nuvio.tv.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier.ChangeSource
import com.nuvio.tv.core.assessment.DeviceSetupRecord
import com.nuvio.tv.core.assessment.NetworkRow
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.filmsUpToMbps
import com.nuvio.tv.core.assessment.inAutomaticShape
import com.nuvio.tv.core.stream.NETWORK_TEST_HEADROOM_PERCENT
import com.nuvio.tv.core.stream.ResolutionLimit
import com.nuvio.tv.core.stream.ResolutionLimitMode
import com.nuvio.tv.core.stream.ServiceLimitFigure
import com.nuvio.tv.core.stream.resolutionLabel
import com.nuvio.tv.core.stream.StreamLimitMode
import com.nuvio.tv.core.stream.StreamLimitSettings
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt

/** The This TV group's actions; the ViewModel runs them, the caller adds the toasts. */
internal class ThisTvActions(
    val onSetAutomatic: (Boolean) -> Unit,
    val onRunAgain: () -> Unit,
    val onUndo: () -> Unit,
    val onSetUpAgain: () -> Unit,
    val onSetStreamLimitMode: (StreamLimitMode) -> Unit,
    val onSetStreamLimitManualMbps: (Int) -> Unit,
    val onSetResolutionLimitMode: (ResolutionLimitMode) -> Unit
)

/** Row key of the row Settings' deep entry focuses ([SettingsEntry.NETWORK]). */
internal const val THIS_TV_ENTRY_ROW_KEY = "this_tv_automatic"

/**
 * Buffer & network › This TV (Spec 33 D5, sandbox u33-welcome-setup v3 stage t-settings): the
 * setup summary, Automatic network, one results panel per streaming service, Stream list limit
 * (Spec 51 P7, sandbox spec-51-network-stream-limit stage set-row), Resolution limit (Spec 58 F3,
 * sandbox spec-58-stream-limit-follow-ups stages set-row / set-res-picker), Network test, Undo the last
 * change (the one undo slot, moved here from Advanced › Device assessment), and Set up this TV
 * again. [entryFocus] is the chip's entry requester: the group's first row takes it.
 */
internal fun LazyListScope.thisTvItems(
    settings: PlayerSettings,
    thisTv: ThisTvState,
    actions: ThisTvActions,
    entryFocus: FocusRequester?
) {
    val setup = thisTv.setup
    item(key = "this_tv_section") {
        SettingsSectionLabel(text = stringResource(R.string.settings_this_tv))
    }
    if (setup != null) {
        item(key = "this_tv_setup") {
            SettingsValueRow(
                title = stringResource(R.string.this_tv_setup_title, Build.MODEL.orEmpty()),
                subtitle = setupSummary(setup),
                value = null,
                trailingIcon = null,
                onClick = {},
                modifier = Modifier.optionalFocusRequester(entryFocus)
            )
        }
    }
    item(key = THIS_TV_ENTRY_ROW_KEY) {
        val returnRow = rememberSettingsReturnTarget(THIS_TV_ENTRY_ROW_KEY)
        val automatic = thisTv.tables.automatic
        SettingsSwitchRow(
            title = stringResource(R.string.this_tv_automatic),
            subtitle = stringResource(
                when {
                    !automatic -> R.string.this_tv_automatic_off_sub
                    !inAutomaticShape(settings) -> R.string.this_tv_automatic_inert_sub
                    else -> R.string.this_tv_automatic_sub
                }
            ),
            checked = automatic,
            onCheckedChange = actions.onSetAutomatic,
            modifier = Modifier
                .optionalFocusRequester(returnRow.requester)
                .optionalFocusRequester(entryFocus.takeIf { setup == null })
        )
    }
    thisTv.tables.tables.toSortedMap().forEach { (service, table) ->
        item(key = "this_tv_table_$service") {
            SettingsResultsPanel {
                SettingsResultLine(
                    label = stringResource(
                        R.string.this_tv_table_header,
                        service,
                        shortDate(table.testedAtMs),
                        transportLabel(table.transport)
                    ),
                    value = ""
                )
                table.rows.forEach { row ->
                    SettingsResultLine(
                        label = rowLabel(row),
                        value = stringResource(R.string.this_tv_mbps, row.mbps.roundToInt())
                    )
                }
            }
        }
    }
    item(key = "this_tv_stream_limit") {
        var pickerOpen by rememberSaveable { mutableStateOf(false) }
        val limit = thisTv.streamLimit
        val figures = serviceFiguresText(thisTv.limitFigures)
        SettingsValueRow(
            title = stringResource(R.string.stream_limit_setting),
            subtitle = when (limit.mode) {
                StreamLimitMode.NETWORK_TEST -> networkLimitSubtitle(thisTv.limitFigures, figures)
                StreamLimitMode.MANUAL -> stringResource(R.string.stream_limit_sub_manual, limit.manualMbps)
                StreamLimitMode.OFF -> stringResource(R.string.stream_limit_sub_off)
            },
            value = streamLimitModeLabel(limit.mode),
            onClick = { pickerOpen = true }
        )
        if (pickerOpen) {
            StreamLimitPanel(
                limit = limit,
                figures = figures,
                onSetMode = actions.onSetStreamLimitMode,
                onSetManualMbps = actions.onSetStreamLimitManualMbps,
                onDismiss = { pickerOpen = false }
            )
        }
    }
    item(key = "this_tv_resolution_limit") {
        var pickerOpen by rememberSaveable { mutableStateOf(false) }
        val limit = thisTv.resolutionLimit
        SettingsValueRow(
            title = stringResource(R.string.resolution_limit_setting),
            subtitle = resolutionLimitSubtitle(limit),
            value = resolutionLimitValue(limit),
            onClick = { pickerOpen = true }
        )
        if (pickerOpen) {
            ResolutionLimitPanel(
                limit = limit,
                onSetMode = actions.onSetResolutionLimitMode,
                onDismiss = { pickerOpen = false }
            )
        }
    }
    item(key = "this_tv_network_test") {
        val tables = thisTv.tables
        val tested = tables.tables.isNotEmpty()
        SettingsValueRow(
            title = stringResource(R.string.this_tv_test),
            subtitle = stringResource(
                when {
                    !tested -> R.string.this_tv_test_untested_sub
                    tables.retestPending -> R.string.this_tv_test_pending_sub
                    else -> R.string.this_tv_test_sub
                }
            ),
            value = if (tested && !tables.retestPending) stringResource(R.string.this_tv_test_run_again) else null,
            trailingIcon = null,
            // Nothing to re-test yet, or already queued: the first qualifying film runs it.
            enabled = tested && !tables.retestPending,
            onClick = actions.onRunAgain
        )
    }
    thisTv.undo?.let { undo ->
        item(key = "this_tv_undo") {
            val date = undo.atMs?.let { shortDate(it) }
            val change = when (undo.source) {
                ChangeSource.FIRST_LAUNCH -> R.string.this_tv_undo_source_first_launch
                ChangeSource.SETUP_AGAIN -> R.string.this_tv_undo_source_setup_again
                ChangeSource.NETWORK_TEST -> R.string.this_tv_undo_source_network_test
                ChangeSource.DEVICE_ASSESSMENT -> R.string.this_tv_undo_source_device_assessment
                ChangeSource.SHIELD_PRESET -> R.string.this_tv_undo_source_shield_preset
                null -> R.string.this_tv_undo_source_unknown
            }.let { stringResource(it) }
            SettingsValueRow(
                title = stringResource(R.string.this_tv_undo),
                subtitle = if (date != null) {
                    stringResource(R.string.this_tv_undo_sub_dated, change, date)
                } else {
                    stringResource(R.string.this_tv_undo_sub, change)
                },
                value = null,
                trailingIcon = null,
                enabled = !thisTv.busy,
                onClick = actions.onUndo
            )
        }
    }
    item(key = "this_tv_setup_again") {
        // Arm-to-confirm, as the assessment's Apply: one OK must not rewrite the buffer set.
        var armed by remember { mutableStateOf(false) }
        SettingsValueRow(
            title = stringResource(
                if (thisTv.running == ThisTvAction.SET_UP_AGAIN) R.string.this_tv_setup_again_running
                else R.string.this_tv_setup_again
            ),
            subtitle = stringResource(
                if (armed) R.string.this_tv_setup_again_confirm_sub else R.string.this_tv_setup_again_sub
            ),
            value = null,
            trailingIcon = null,
            enabled = !thisTv.busy,
            onClick = {
                if (armed) {
                    armed = false
                    actions.onSetUpAgain()
                } else {
                    armed = true
                }
            }
        )
    }
}

/**
 * The Stream list limit picker (Spec 51 P7, stage set-picker): From the network test, Manual with
 * its ‹ › figure (5 Mbps steps, 5-100; a step saves at once, OK picks Manual), Off, then the note.
 * [figures] = [serviceFiguresText] of the counting services, null when none counts.
 */
@Composable
private fun StreamLimitPanel(
    limit: StreamLimitSettings,
    figures: String?,
    onSetMode: (StreamLimitMode) -> Unit,
    onSetManualMbps: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val networkSub = stringResource(R.string.stream_limit_option_network_sub, NETWORK_TEST_HEADROOM_PERCENT)
    val network = SettingsPickerOption(
        value = StreamLimitMode.NETWORK_TEST,
        title = streamLimitModeLabel(StreamLimitMode.NETWORK_TEST),
        description = figures?.let { stringResource(R.string.stream_limit_option_network_now, networkSub, it) } ?: networkSub
    )
    val manual = SettingsPickerOption(
        value = StreamLimitMode.MANUAL,
        title = streamLimitModeLabel(StreamLimitMode.MANUAL),
        description = stringResource(R.string.stream_limit_option_manual_sub)
    )
    val off = SettingsPickerOption(
        value = StreamLimitMode.OFF,
        title = streamLimitModeLabel(StreamLimitMode.OFF),
        description = stringResource(R.string.stream_limit_option_off_sub)
    )
    val options = listOf(network, manual, off)
    val initialIndex = remember { settingsPickerInitialIndex(options, limit.mode) }
    val note = stringResource(R.string.stream_limit_note)
    val manualValue = stringResource(R.string.this_tv_mbps, limit.manualMbps)
    val stepLabel = stringResource(R.string.stream_limit_manual_step)
    fun pick(mode: StreamLimitMode) {
        onSetMode(mode)
        onDismiss()
    }
    SettingsPanelList(
        title = stringResource(R.string.stream_limit_setting),
        initialIndex = initialIndex,
        onDismiss = onDismiss
    ) { focusRequester ->
        fun requesterOf(option: SettingsPickerOption<StreamLimitMode>) =
            focusRequester.takeIf { options.indexOf(option) == initialIndex }
        fun plainRow(option: SettingsPickerOption<StreamLimitMode>) = item(key = option.value.name) {
            SettingsPanelOptionRow(
                option = option,
                selected = option.value == limit.mode,
                onClick = { pick(option.value) },
                focusRequester = requesterOf(option)
            )
        }
        plainRow(network)
        item(key = manual.value.name) {
            SettingsPanelStepperOptionRow(
                option = manual,
                selected = limit.mode == StreamLimitMode.MANUAL,
                stepLabel = stepLabel,
                value = limit.manualMbps,
                min = StreamLimitSettings.MIN_MANUAL_MBPS,
                max = StreamLimitSettings.MAX_MANUAL_MBPS,
                step = StreamLimitSettings.MANUAL_STEP_MBPS,
                valueText = manualValue,
                onValueChange = onSetManualMbps,
                onClick = { pick(StreamLimitMode.MANUAL) },
                focusRequester = requesterOf(manual)
            )
        }
        plainRow(off)
        item(key = "note") {
            SettingsPanelCaption(
                text = note,
                modifier = Modifier.padding(top = NuvioTheme.marquee.settings.panelTextInset)
            )
        }
    }
}

/**
 * The row's NETWORK_TEST subtitle (Spec 58 D1): one service names its limit and the speed it keeps
 * [NETWORK_TEST_HEADROOM_PERCENT] of; several list each limit ([figures], from [serviceFiguresText]); none tested yet says so.
 */
@Composable
private fun networkLimitSubtitle(limitFigures: List<ServiceLimitFigure>, figures: String?): String {
    val only = limitFigures.singleOrNull()
    return when {
        only != null -> stringResource(
            R.string.stream_limit_sub_network_one,
            only.figureMbps,
            NETWORK_TEST_HEADROOM_PERCENT,
            only.measuredMbps,
            only.serviceKey
        )
        figures != null -> stringResource(R.string.stream_limit_sub_network, NETWORK_TEST_HEADROOM_PERCENT, figures)
        else -> stringResource(R.string.stream_limit_sub_untested)
    }
}

/** "72 Mbps from real-debrid.com, 48 Mbps from premiumize.me" (each service's limit); null for none. */
@Composable
private fun serviceFiguresText(figures: List<ServiceLimitFigure>): String? =
    figures.map { stringResource(R.string.stream_limit_service_figure, it.figureMbps, it.serviceKey) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

/**
 * The Resolution limit picker (Spec 58 F3, sandbox stage set-res-picker): This TV (the default,
 * naming the TV's resolution when it reports one), Up to 1080p, Up to 720p, Off; OK picks and closes.
 */
@Composable
private fun ResolutionLimitPanel(
    limit: ResolutionLimit,
    onSetMode: (ResolutionLimitMode) -> Unit,
    onDismiss: () -> Unit
) {
    val tvSub = stringResource(R.string.resolution_limit_option_this_tv_sub)
    val options = ResolutionLimitMode.entries.map { mode ->
        SettingsPickerOption(
            value = mode,
            title = resolutionLimitModeLabel(mode),
            description = when (mode) {
                ResolutionLimitMode.THIS_TV -> limit.tvMax?.let {
                    stringResource(R.string.resolution_limit_option_this_tv_now, tvSub, resolutionLabel(it))
                } ?: tvSub
                ResolutionLimitMode.UP_TO_1080P -> stringResource(R.string.resolution_limit_sub_1080)
                ResolutionLimitMode.UP_TO_720P -> stringResource(R.string.resolution_limit_sub_720)
                ResolutionLimitMode.OFF -> stringResource(R.string.resolution_limit_option_off_sub)
            }
        )
    }
    val initialIndex = remember { settingsPickerInitialIndex(options, limit.mode) }
    val note = stringResource(R.string.resolution_limit_note)
    SettingsPanelList(
        title = stringResource(R.string.resolution_limit_setting),
        initialIndex = initialIndex,
        onDismiss = onDismiss
    ) { focusRequester ->
        options.forEachIndexed { index, option ->
            item(key = option.value.name) {
                SettingsPanelOptionRow(
                    option = option,
                    selected = option.value == limit.mode,
                    onClick = {
                        onSetMode(option.value)
                        onDismiss()
                    },
                    focusRequester = focusRequester.takeIf { index == initialIndex }
                )
            }
        }
        item(key = "note") {
            SettingsPanelCaption(
                text = note,
                modifier = Modifier.padding(top = NuvioTheme.marquee.settings.panelTextInset)
            )
        }
    }
}

/** The Resolution limit row's subtitle per mode; This TV with no reported resolution says nothing is hidden. */
@Composable
private fun resolutionLimitSubtitle(limit: ResolutionLimit): String = when (limit.mode) {
    ResolutionLimitMode.THIS_TV -> limit.tvMax?.let {
        stringResource(R.string.resolution_limit_sub_this_tv, resolutionLabel(it))
    } ?: stringResource(R.string.resolution_limit_sub_unknown)
    ResolutionLimitMode.UP_TO_1080P -> stringResource(R.string.resolution_limit_sub_1080)
    ResolutionLimitMode.UP_TO_720P -> stringResource(R.string.resolution_limit_sub_720)
    ResolutionLimitMode.OFF -> stringResource(R.string.resolution_limit_sub_off)
}

/** The row's value: "This TV · 4K" (the mode and the TV's resolution), else the mode's label. */
@Composable
private fun resolutionLimitValue(limit: ResolutionLimit): String {
    val tvMax = limit.tvMax
    return if (limit.mode == ResolutionLimitMode.THIS_TV && tvMax != null) {
        stringResource(R.string.resolution_limit_value_this_tv, resolutionLabel(tvMax))
    } else {
        resolutionLimitModeLabel(limit.mode)
    }
}

@Composable
private fun resolutionLimitModeLabel(mode: ResolutionLimitMode): String = stringResource(
    when (mode) {
        ResolutionLimitMode.THIS_TV -> R.string.resolution_limit_mode_this_tv
        ResolutionLimitMode.UP_TO_1080P -> R.string.resolution_limit_mode_1080
        ResolutionLimitMode.UP_TO_720P -> R.string.resolution_limit_mode_720
        ResolutionLimitMode.OFF -> R.string.resolution_limit_mode_off
    }
)

@Composable
private fun streamLimitModeLabel(mode: StreamLimitMode): String = stringResource(
    when (mode) {
        StreamLimitMode.NETWORK_TEST -> R.string.stream_limit_mode_network
        StreamLimitMode.MANUAL -> R.string.stream_limit_mode_manual
        StreamLimitMode.OFF -> R.string.stream_limit_mode_off
    }
)

@Composable
private fun setupSummary(setup: DeviceSetupRecord): String {
    val date = shortDate(setup.atMs)
    return when (setup.kind) {
        DeviceSetupRecord.Kind.FIRST_LAUNCH -> stringResource(R.string.this_tv_setup_first_launch, date)
        DeviceSetupRecord.Kind.AGAIN -> stringResource(R.string.this_tv_setup_again_done, date)
        DeviceSetupRecord.Kind.KEPT -> stringResource(R.string.this_tv_setup_kept, date)
    }
}

/** "1 connection · films up to 30 Mbps" / "4 connections · 16 MB · up to 74 Mbps" (several chunk sizes per count). */
@Composable
private fun rowLabel(row: NetworkRow): String {
    // Floored: a film at the shown figure must clear choose()'s bar for this row.
    val upTo = floor(filmsUpToMbps(row.mbps)).toInt()
    return if (row.parallel) {
        stringResource(R.string.this_tv_row_parallel, row.connections, row.chunkMb, upTo)
    } else {
        stringResource(R.string.this_tv_row_single, upTo)
    }
}

@Composable
private fun transportLabel(transport: NetworkTransport): String = stringResource(
    when (transport) {
        NetworkTransport.WIFI -> R.string.this_tv_transport_wifi
        NetworkTransport.ETHERNET -> R.string.this_tv_transport_ethernet
        NetworkTransport.OTHER -> R.string.this_tv_transport_other
    }
)

private fun shortDate(ms: Long): String =
    formatDayMonth(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())
