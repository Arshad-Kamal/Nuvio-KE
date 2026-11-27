package com.nuvio.tv.ui.screens.settings.trackers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.data.repository.LibraryTransferMode
import com.nuvio.tv.data.repository.LibraryTransferOutcome
import com.nuvio.tv.data.repository.LibraryTransferPreview
import com.nuvio.tv.data.repository.LibraryTransferResult
import com.nuvio.tv.data.repository.LibraryTransferState
import com.nuvio.tv.data.repository.LibraryTransferStopReason
import com.nuvio.tv.data.repository.transferMoveAllowed
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsPickerPanel
import com.nuvio.tv.ui.screens.settings.SettingsResultLine
import com.nuvio.tv.ui.screens.settings.SettingsResultsPanel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

private enum class EndPicker { FROM, TO }

/**
 * Transfer library side panel (Spec 104 S4d; sandbox `spec-104-trackers` v4): D1 the plan (From, To, counts, Copy,
 * Move only from Nuvio, Cancel), D1b the Move confirm (Cancel focused), D2 progress with Stop, D3/D4 the result with
 * Close. Back or Cancel closes it; a running transfer keeps going and reopening shows it.
 */
@Composable
internal fun LibraryTransferPanel(
    viewModel: LibraryTransferViewModel,
    sourceName: @Composable (TrackingSource) -> String,
    onDismiss: () -> Unit
) {
    val ui = viewModel.ui.collectAsStateWithLifecycle().value
    var picker by remember { mutableStateOf<EndPicker?>(null) }
    LaunchedEffect(viewModel) { viewModel.open() }

    val run = ui.run
    val idle = run is LibraryTransferState.Idle
    // The ends shown: the run's while one runs or shows its result, else the panel's choice.
    val (from, to) = when (run) {
        is LibraryTransferState.Idle -> ui.from to ui.to
        is LibraryTransferState.Running -> run.from to run.to
        is LibraryTransferState.Finished -> run.result.from to run.result.to
    }
    val fromName = sourceName(from)
    val toName = sourceName(to)
    val preview = (ui.plan as? TransferPlanUi.Ready)?.preview?.takeIf { idle }
    // Asked for this plan only: a new read or a run started elsewhere drops the confirm.
    var confirmMove by remember(preview) { mutableStateOf(false) }
    val actionFocus = remember { FocusRequester() }
    val marquee = NuvioTheme.marquee
    val copyEnabled = preview != null && preview.plan.toCopy.isNotEmpty()
    val moveShown = preview != null && transferMoveAllowed(preview.from)
    val moveEnabled = moveShown && preview?.plan?.moveCandidates?.isNotEmpty() == true
    // The focused action: Copy, else Move, else Try again or Cancel (D1); Stop (D2); Close (D3/D4).
    val focusCopy = copyEnabled
    val focusMove = !copyEnabled && moveEnabled
    val focusCancel = idle && !copyEnabled && !moveEnabled && ui.plan !is TransferPlanUi.Failed
    LaunchedEffect(run::class, ui.plan::class, copyEnabled, moveEnabled) { actionFocus.requestFocusAfterFrames() }

    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.settings.pickerWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = actionFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.trackers_transfer_title))
        EndRow(stringResource(R.string.trackers_transfer_from), fromName, idle && ui.sources.size > 1) { picker = EndPicker.FROM }
        EndRow(stringResource(R.string.trackers_transfer_to), toName, idle && ui.destinations.size > 1) { picker = EndPicker.TO }
        Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            when (run) {
                is LibraryTransferState.Idle -> when (val plan = ui.plan) {
                    TransferPlanUi.Loading -> PanelNote(stringResource(R.string.trackers_transfer_reading, toName))
                    is TransferPlanUi.Failed ->
                        PanelError(stringResource(R.string.trackers_transfer_read_failed, sourceName(plan.failedSource)))
                    is TransferPlanUi.Ready -> PlanCounts(plan.preview, fromName, toName)
                }
                is LibraryTransferState.Running -> Progress(run, toName)
                is LibraryTransferState.Finished -> ResultBody(run.result, fromName, toName)
            }
        }
        when (run) {
            is LibraryTransferState.Idle -> {
                when {
                    preview != null -> {
                        val plan = preview.plan
                        PlayerPanelRow(
                            title = pluralStringResource(R.plurals.trackers_transfer_copy, plan.toCopy.size, plan.toCopy.size),
                            subtitle = stringResource(R.string.trackers_transfer_copy_keeps, fromName),
                            selected = false,
                            enabled = copyEnabled,
                            onClick = { viewModel.start(LibraryTransferMode.COPY) },
                            focusRequester = actionFocus.takeIf { focusCopy }
                        )
                        if (moveShown) {
                            val candidates = plan.moveCandidates.size
                            PlayerPanelRow(
                                title = pluralStringResource(R.plurals.trackers_transfer_move, candidates, candidates),
                                subtitle = stringResource(R.string.trackers_transfer_move_subtitle, plan.toCopy.size, candidates),
                                selected = false,
                                enabled = moveEnabled,
                                onClick = { confirmMove = true },
                                focusRequester = actionFocus.takeIf { focusMove }
                            )
                        }
                    }
                    ui.plan is TransferPlanUi.Failed -> PlayerPanelRow(
                        title = stringResource(R.string.trackers_signin_try_again),
                        selected = false,
                        onClick = viewModel::retryPlan,
                        focusRequester = actionFocus
                    )
                }
                PlayerPanelRow(
                    title = stringResource(R.string.action_cancel),
                    selected = false,
                    onClick = onDismiss,
                    focusRequester = actionFocus.takeIf { focusCancel }
                )
            }
            is LibraryTransferState.Running -> PlayerPanelRow(
                title = stringResource(R.string.trackers_transfer_stop),
                selected = false,
                onClick = viewModel::stop,
                focusRequester = actionFocus
            )
            is LibraryTransferState.Finished -> PlayerPanelRow(
                title = stringResource(R.string.trackers_transfer_close),
                selected = false,
                onClick = {
                    viewModel.closeResult()
                    onDismiss()
                },
                focusRequester = actionFocus
            )
        }
    }

    picker?.let { which ->
        val options = if (which == EndPicker.FROM) ui.sources else ui.destinations
        SettingsPickerPanel(
            title = stringResource(if (which == EndPicker.FROM) R.string.trackers_transfer_from else R.string.trackers_transfer_to),
            options = options.map { SettingsPickerOption(value = it, title = sourceName(it)) },
            selectedValue = if (which == EndPicker.FROM) ui.from else ui.to,
            onSelect = { if (which == EndPicker.FROM) viewModel.selectFrom(it) else viewModel.selectTo(it) },
            onDismiss = { picker = null }
        )
    }

    if (confirmMove && preview != null) {
        val plan = preview.plan
        val candidates = plan.moveCandidates.size
        val body = stringResource(
            R.string.trackers_transfer_move_confirm_body, plan.toCopy.size, toName, candidates, plan.alreadyThere.size
        )
        MarqueeConfirmPanel(
            eyebrow = pluralStringResource(R.plurals.trackers_transfer_move_confirm_title, candidates, candidates, toName),
            body = if (ui.nuvioAccount) "$body\n\n${stringResource(R.string.trackers_transfer_move_confirm_devices)}" else body,
            confirmLabel = stringResource(R.string.trackers_transfer_move_action),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmMove = false
                viewModel.start(LibraryTransferMode.MOVE)
            },
            onDismiss = { confirmMove = false },
            destructive = true
        )
    }
}

/** From / To (`.l8pk` with the value and a chevron); dimmed while a transfer runs or shows its result. */
@Composable
private fun EndRow(label: String, value: String, editable: Boolean, onClick: () -> Unit) {
    PlayerPanelRow(
        title = label,
        titleEnd = value,
        selected = false,
        enabled = editable,
        trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight.takeIf { editable },
        onClick = onClick
    )
}

/** Stage D1's counts and note. */
@Composable
private fun PlanCounts(preview: LibraryTransferPreview, fromName: String, toName: String) {
    val plan = preview.plan
    SettingsResultsPanel {
        SettingsResultLine(stringResource(R.string.trackers_transfer_in_source, fromName), plan.sourceTitles.toString())
        SettingsResultLine(stringResource(R.string.trackers_transfer_to_copy), plan.toCopy.size.toString())
        if (plan.alreadyThere.isNotEmpty()) {
            SettingsResultLine(stringResource(R.string.trackers_transfer_already_in, toName), plan.alreadyThere.size.toString())
        }
        if (plan.unmatched > 0) {
            SettingsResultLine(stringResource(R.string.trackers_transfer_cant_match), plan.unmatched.toString())
        }
    }
    val unmatched = if (preview.to == TrackingSource.NUVIO) {
        stringResource(R.string.trackers_transfer_note_unmatched_nuvio)
    } else {
        stringResource(R.string.trackers_transfer_note_unmatched_on, toName)
    }
    val copyOnly = stringResource(R.string.trackers_transfer_note_copy_only, fromName).takeIf { preview.from != TrackingSource.NUVIO }
    PanelNote(listOfNotNull(unmatched, copyOnly).joinToString(" "), top = 8.dp)
}

/** Stage D2: "Copying to MDBList", the bar, "58 of 146". */
@Composable
private fun Progress(running: LibraryTransferState.Running, toName: String) {
    Headline(stringResource(R.string.trackers_transfer_copying, toName))
    val fraction = if (running.total == 0) 1f else (running.done.toFloat() / running.total).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(NuvioPrimitives.white.copy(alpha = 0.2f), shape)
    ) {
        Box(modifier = Modifier.fillMaxWidth(fraction).height(4.dp).background(NuvioPrimitives.white, shape))
    }
    PanelNote(stringResource(R.string.trackers_transfer_progress, running.done, running.total))
}

/** Stages D3/D4: the headline, the reason of a stop, the rows (0 left out) and the note. */
@Composable
private fun ResultBody(result: LibraryTransferResult, fromName: String, toName: String) {
    val move = result.mode == LibraryTransferMode.MOVE
    Headline(
        when (result.outcome) {
            LibraryTransferOutcome.DONE -> stringResource(
                if (move) R.string.trackers_transfer_moved_to else R.string.trackers_transfer_copied_to, toName
            )
            LibraryTransferOutcome.STOPPED -> stringResource(R.string.trackers_transfer_stopped)
            LibraryTransferOutcome.STOPPED_AFTER_ERRORS -> stringResource(R.string.trackers_transfer_stopped_errors)
        }
    )
    if (result.outcome == LibraryTransferOutcome.STOPPED_AFTER_ERRORS) {
        val endName = when (result.failedEnd) {
            null -> toName
            result.from -> fromName
            else -> toName
        }
        PanelError(stopReason(result.reason, endName))
    }
    val counts = result.counts ?: return
    Spacer(modifier = Modifier.height(6.dp))
    SettingsResultsPanel {
        listOf(
            R.string.trackers_transfer_row_copied to counts.copied,
            R.string.trackers_transfer_already_in to counts.alreadyThere,
            R.string.trackers_transfer_row_couldnt_copy to counts.couldNotCopy,
            R.string.trackers_transfer_row_not_copied_yet to counts.notCopiedYet,
            R.string.trackers_transfer_row_removed to counts.removed,
            R.string.trackers_transfer_row_not_removed to counts.notRemoved
        ).filter { (_, count) -> count > 0 }.forEach { (label, count) ->
            SettingsResultLine(stringResource(label, toName), count.toString())
        }
    }
    val notes = buildList {
        when {
            result.checkFailed -> add(
                stringResource(
                    if (move) R.string.trackers_transfer_check_failed_move else R.string.trackers_transfer_check_failed_copy,
                    toName
                )
            )
            move && result.outcome != LibraryTransferOutcome.DONE ->
                add(stringResource(R.string.trackers_transfer_note_move_stopped, result.sourceTitles, toName))
            result.outcome != LibraryTransferOutcome.DONE -> add(stringResource(R.string.trackers_transfer_note_stopped, toName))
            counts.notRemoved > 0 -> add(stringResource(R.string.trackers_transfer_note_not_removed, counts.notRemoved, toName))
        }
        if (result.outcome == LibraryTransferOutcome.DONE && result.from != TrackingSource.NUVIO) {
            add(stringResource(R.string.trackers_transfer_note_from_tracker_done, fromName, result.sourceTitles))
            if (result.unmatched > 0) {
                add(pluralStringResource(R.plurals.trackers_transfer_note_unmatched_stay, result.unmatched, result.unmatched, fromName))
            }
        }
    }
    if (notes.isNotEmpty()) PanelNote(notes.joinToString(" "), top = 8.dp)
}

@Composable
private fun stopReason(reason: LibraryTransferStopReason?, trackerName: String): String = when (reason) {
    LibraryTransferStopReason.RATE_LIMIT -> stringResource(R.string.trackers_transfer_reason_rate_limit, trackerName)
    LibraryTransferStopReason.FAILURES -> stringResource(R.string.trackers_transfer_reason_failures, trackerName)
    LibraryTransferStopReason.ACCOUNT_CHANGED -> stringResource(R.string.trackers_transfer_reason_account_changed, trackerName)
    LibraryTransferStopReason.ACCOUNT_UNCONFIRMED ->
        stringResource(R.string.trackers_transfer_reason_account_unconfirmed, trackerName)
    LibraryTransferStopReason.READ_FAILED -> stringResource(R.string.trackers_transfer_read_failed, trackerName)
    LibraryTransferStopReason.REMOVE_FAILED -> stringResource(R.string.trackers_transfer_reason_remove_failed)
    null -> stringResource(R.string.trackers_transfer_reason_unknown)
}

/** `.s104big`. */
@Composable
private fun Headline(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.button,
        color = NuvioPrimitives.white,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

/** `.s104err`. */
@Composable
private fun PanelError(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioPrimitives.errorOnImage,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** `.l8p .note`. */
@Composable
private fun PanelNote(text: String, top: Dp = 0.dp) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = top)
    )
}
