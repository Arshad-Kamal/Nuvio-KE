package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.AssessmentItem
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.ui.util.findActivity
import com.nuvio.tv.core.assessment.AssessmentResult
import com.nuvio.tv.core.assessment.AssessmentTier
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.assessment.ProfileKind
import com.nuvio.tv.core.assessment.ShieldPreset
import com.nuvio.tv.core.player.DeviceQuirks
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.player.MemoryUsageStatus
import com.nuvio.tv.core.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Device Settings Assessment, Stage 1 UI (REVIEW-ONLY).
 *
 * State lives at the SCREEN level (rememberDeviceAssessmentState in
 * AdvancedSettingsContent) and the results render as MULTIPLE LazyColumn
 * items via deviceAssessmentItems. Both are deliberate: lazy items are
 * disposed when scrolled out of composition, so state held inside a single
 * tall item died on scroll-away (results vanished), and one tall item also
 * cut cards off between focus stops. Per-card items scroll individually and
 * screen-level state survives recycling; the run coroutine launches on the
 * screen scope so a mid-run scroll can't cancel the sweep.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface AssessmentDataStoreEntryPoint {
    fun playerSettingsDataStore(): com.nuvio.tv.data.local.PlayerSettingsDataStore
}

internal fun assessmentDataStore(context: Context): com.nuvio.tv.data.local.PlayerSettingsDataStore =
    dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext,
        AssessmentDataStoreEntryPoint::class.java
    ).playerSettingsDataStore()

internal class DeviceAssessmentState {
    var running by mutableStateOf(false)
    var sweepState by mutableStateOf("")
    var passRows by mutableStateOf(listOf<Pair<String, Double?>>())
    // N3d-a2: why a row has no number, keyed by row label.
    var passNotes by mutableStateOf(mapOf<String, StreamSweepEngine.PassNote>())
    var result by mutableStateOf<AssessmentResult?>(null)
    var selectedProfile by mutableStateOf<ProfileKind?>(null)
    var applyArmed by mutableStateOf(false)
    var applying by mutableStateOf(false)
    var appliedCount by mutableStateOf<Int?>(null)
    var presetExpanded by mutableStateOf(false)
    var presetArmed by mutableStateOf(false)
    var presetApplying by mutableStateOf(false)
    var presetAppliedCount by mutableStateOf<Int?>(null)
}

@Composable
internal fun rememberDeviceAssessmentState(): DeviceAssessmentState =
    remember { DeviceAssessmentState() }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun runDeviceAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState,
    settings: PlayerSettings,
    diagnostics: LastPlaybackDiagnostics
) {
    if (state.running) return
    scope.launch {
        state.running = true
        state.passRows = emptyList()
        state.passNotes = emptyMap()
        state.sweepState = ""
        state.result = null
        state.applyArmed = false
        state.appliedCount = null
        val outcome = DeviceAssessmentEngine.run(
            context = context,
            activity = context.findActivity(),
            settings = settings,
            diagnostics = diagnostics,
            onSweepState = { state.sweepState = it },
            onSweepPassAdded = { label ->
                state.passRows = state.passRows + (label to null)
            },
            onSweepPassResult = { label, mbps, note ->
                state.passRows = state.passRows.map { if (it.first == label) label to mbps else it }
                if (note != null) state.passNotes = state.passNotes + (label to note)
            }
        )
        state.result = outcome
        state.selectedProfile = outcome.suggestedProfile
        state.running = false
    }
}

internal fun runApplyAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState
) {
    val res = state.result ?: return
    if (state.applying || state.presetApplying) return
    scope.launch {
        state.applying = true
        // Shared revert slot: any apply invalidates the other path's "Applied" claim.
        state.presetAppliedCount = null
        state.presetArmed = false
        val profile = res.profiles.firstOrNull { it.kind == state.selectedProfile }
        val outcome = DeviceAssessmentApplier.apply(
            dataStore = assessmentDataStore(context),
            plan = res.applyPlan,
            profile = profile,
            safeLimitMb = res.header.safeLimitMb,
            source = DeviceAssessmentApplier.ChangeSource.DEVICE_ASSESSMENT
        )
        state.appliedCount = outcome.writtenCount
        state.applyArmed = false
        state.applying = false
    }
}

internal fun runApplyShieldPreset(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState
) {
    if (state.applying || state.presetApplying) return
    scope.launch {
        state.presetApplying = true
        // Shared revert slot: this apply replaces any assessment snapshot and
        // invalidates the assessment apply card's "Applied" claim.
        state.appliedCount = null
        state.applyArmed = false
        val outcome = DeviceAssessmentApplier.apply(
            dataStore = assessmentDataStore(context),
            plan = ShieldPreset.plan,
            profile = ShieldPreset.profile,
            // Same live safe-limit guard the assessment apply uses: the
            // applier clamps the target down if this device's tier can't
            // fit target + parallel overhead (on the 2 GB reference Shield
            // this fires: 200 → 150, meminfo 2026-08-29).
            safeLimitMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context),
            source = DeviceAssessmentApplier.ChangeSource.SHIELD_PRESET
        )
        state.presetAppliedCount = outcome.writtenCount
        state.presetArmed = false
        state.presetExpanded = false
        state.presetApplying = false
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun LazyListScope.deviceAssessmentItems(
    state: DeviceAssessmentState,
    diagnostics: LastPlaybackDiagnostics,
    /** The Settings shell's entry requester: the section's first row takes it. */
    entryFocusRequester: FocusRequester?,
    onRun: () -> Unit,
    onApply: () -> Unit,
    onApplyPreset: () -> Unit
) {
    // Q12: the hand-built preset is for the reference Shield only. Entry lands on the first row.
    val showPreset = DeviceQuirks.current().isShield
    val presetEntry = entryFocusRequester.takeIf { showPreset }
    val runEntry = entryFocusRequester.takeUnless { showPreset }
    if (showPreset) {
        item(key = "assessment_preset") {
            ShieldPresetCard(
                state = state,
                onApply = onApplyPreset,
                modifier = Modifier.optionalFocusRequester(presetEntry)
            )
        }
    }

    item(key = "assessment_run") {
        val hasStream = !diagnostics.streamUrl.isNullOrBlank()
        SettingsValueRow(
            title = stringResource(
                if (state.running) R.string.assessment_run_running else R.string.assessment_run_title
            ),
            subtitle = if (state.running) {
                null
            } else {
                stringResource(if (hasStream) R.string.assessment_run_subtitle else R.string.assessment_no_stream)
            },
            value = if (state.running && state.sweepState.isNotBlank()) state.sweepState else null,
            enabled = !state.running,
            trailingIcon = null,
            onClick = onRun,
            modifier = Modifier.optionalFocusRequester(runEntry)
        )
    }

    if (state.passRows.isNotEmpty()) {
        item(key = "assessment_passes") {
            SettingsResultsPanel {
                state.passRows.forEach { (label, speed) ->
                    StreamTestResultRow(
                        label = label,
                        speed = speed,
                        note = state.passNotes[label],
                        isRunning = state.running && state.sweepState == label &&
                            speed == null && state.passNotes[label] == null
                    )
                }
            }
        }
    }

    val res = state.result
    if (res != null) {
        assessmentResultItems(res, state)
    }

    // The undo slot this apply fills lives in Playback › Buffer & network › This TV (Spec 33 D5).
    val current = state.result
    if (current != null) {
        item(key = "assessment_apply") {
            val previewCount = current.applyPlan.touchedCount +
                (if (state.selectedProfile != null) 3 else 0)
            SettingsValueRow(
                title = stringResource(
                    if (state.applying) R.string.assessment_apply_applying
                    else R.string.assessment_apply_title
                ),
                subtitle = when {
                    state.appliedCount != null -> stringResource(
                        R.string.assessment_apply_done_sub, state.appliedCount ?: 0
                    )
                    state.applyArmed -> stringResource(
                        R.string.assessment_apply_confirm_sub, previewCount
                    )
                    else -> stringResource(R.string.assessment_apply_arm_sub)
                },
                value = null,
                trailingIcon = null,
                enabled = !state.applying && !state.presetApplying && previewCount > 0,
                onClick = {
                    if (state.applyArmed) onApply() else state.applyArmed = true
                }
            )
        }
    }

    item(key = "assessment_footer") {
        SettingsNote(text = stringResource(R.string.assessment_footer))
    }
}

/** The finished assessment: error, device facts, then the four tiers. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun LazyListScope.assessmentResultItems(res: AssessmentResult, state: DeviceAssessmentState) {
    res.errorText?.let { err ->
        item(key = "assessment_error") {
            Text(
                text = stringResource(R.string.assessment_error_prefix, err),
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioTheme.colors.Error,
                modifier = Modifier.padding(horizontal = NuvioTheme.marquee.settings.notePadding)
            )
        }
    }

    item(key = "assessment_sec_facts") {
        SettingsSectionLabel(stringResource(R.string.assessment_header_title))
    }
    item(key = "assessment_facts") {
        // A focus stop, so D-pad scrolling brings the whole block into view.
        SettingsCard {
            AssessmentFactRow(
                label = stringResource(R.string.assessment_header_device),
                value = stringResource(
                    R.string.assessment_header_device_value,
                    res.header.deviceRamLabel,
                    res.header.safeLimitMb,
                    res.header.warningLimitMb
                )
            )
            AssessmentFactRow(
                label = stringResource(R.string.assessment_header_display),
                value = res.header.displaySummary ?: stringResource(R.string.assessment_header_display_unknown)
            )
            AssessmentFactRow(
                label = stringResource(R.string.assessment_header_stream),
                value = res.header.streamLabel ?: stringResource(R.string.assessment_header_stream_none),
                valueMaxLines = 2
            )
            res.header.streamBitrateMbps?.let { mbps ->
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_bitrate),
                    value = stringResource(R.string.stream_test_unit_mbps, mbps)
                )
            }
            val hdrBits = listOfNotNull(res.header.streamDvProfile, res.header.streamHdrType)
            if (hdrBits.isNotEmpty()) {
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_hdr),
                    value = hdrBits.joinToString(" \u00b7 ")
                )
            }
        }
    }

    item(key = "assessment_sec_measured") {
        SettingsSectionLabel(stringResource(R.string.assessment_section_measured))
    }
    val measured = res.items.filter { it.tier == AssessmentTier.MEASURED }
    if (res.sweepRan && measured.isNotEmpty()) {
        measured.forEach { measuredItem ->
            item(key = "assessment_measured_${measuredItem.key}") { AssessmentItemRow(measuredItem) }
        }
        res.sweepVerdictText?.let { verdict ->
            item(key = "assessment_measured_verdict") { SettingsNote(text = verdict) }
        }
    } else {
        item(key = "assessment_measured_skipped") {
            SettingsNote(text = stringResource(R.string.assessment_measured_skipped))
        }
    }

    item(key = "assessment_sec_calculated") {
        SettingsSectionLabel(stringResource(R.string.assessment_section_calculated))
    }
    res.items.filter { it.tier == AssessmentTier.CALCULATED }.forEach { calcItem ->
        item(key = "assessment_calc_${calcItem.key}") { AssessmentItemRow(calcItem) }
    }

    item(key = "assessment_sec_priority") {
        SettingsSectionLabel(stringResource(R.string.assessment_section_priority))
    }
    res.profiles.forEach { profile ->
        item(key = "assessment_profile_${profile.kind}") {
            val values = if (profile.minCappedFromMs != null) {
                stringResource(
                    R.string.assessment_profile_values_capped,
                    profile.initialBufferMs / 1000,
                    profile.rebufferMs / 1000,
                    profile.minBufferMs / 1000,
                    profile.minCappedFromMs / 1000
                )
            } else {
                stringResource(
                    R.string.assessment_profile_values,
                    profile.initialBufferMs / 1000,
                    profile.rebufferMs / 1000,
                    profile.minBufferMs / 1000
                )
            }
            val suggested = res.suggestedProfile == profile.kind
            SettingsSwitchRow(
                title = if (suggested) {
                    profile.title + " \u00b7 " + stringResource(R.string.assessment_profile_suggested_tag)
                } else {
                    profile.title
                },
                subtitle = profile.subtitle + "\n" + values,
                checked = state.selectedProfile == profile.kind,
                onCheckedChange = {
                    state.selectedProfile = if (state.selectedProfile == profile.kind) null else profile.kind
                }
            )
        }
    }
    item(key = "assessment_stability") {
        val cov = res.stabilityCoV
        SettingsNote(
            text = if (cov != null) {
                stringResource(R.string.assessment_stability_line, "%.2f".format(cov), res.stabilityPassCount)
            } else {
                stringResource(R.string.assessment_stability_insufficient, res.stabilityPassCount)
            }
        )
    }

    item(key = "assessment_sec_verify") {
        SettingsSectionLabel(stringResource(R.string.assessment_section_verify))
    }
    res.items.filter { it.tier == AssessmentTier.VERIFY }.forEach { verifyItem ->
        item(key = "assessment_verify_${verifyItem.key}") { AssessmentItemRow(verifyItem) }
    }
}

/**
 * The hand-built Shield preset card (approved mockup: docs/design/mockups/shield-preset.webp,
 * restyled as a Settings card in Spec 31). Collapsed: title + subtitle + state line. Expanded: the
 * fixed value list stays inside the card; Apply preset and Cancel render as separate rows BELOW
 * the card, so the card itself is one target (expand) and the rows are the next two.
 * Arm-to-confirm guards apply. Rides the same undo slot as the assessment (Undo the last change in
 * Playback › Buffer & network › This TV); the state lines say so. Target / memory figures
 * preview the applier's live safe-limit clamp via ShieldPreset.effective* so advertised values equal written values (the 2017 reference Shield is
 * the 2 GB model: 250 MB rung -> 150/246, see ShieldPreset KDoc).
 */
@Composable
private fun ShieldPresetCard(
    state: DeviceAssessmentState,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val marquee = NuvioTheme.marquee
    val safeLimitMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
    val effectiveTargetMb = ShieldPreset.effectiveTargetMb(safeLimitMb)
    val ramGbLabel = String.format(Locale.US, "%.1f", NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamGb(context))
    Column(verticalArrangement = Arrangement.spacedBy(marquee.settings.rowGap)) {
        SettingsCard(
            modifier = modifier,
            onClick = { if (!state.presetExpanded && !state.presetApplying) state.presetExpanded = true }
        ) {
            Text(
                text = stringResource(R.string.assessment_preset_title),
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white
            )
            if (!state.presetExpanded) {
                Text(
                    text = stringResource(R.string.assessment_preset_sub, ramGbLabel),
                    style = NuvioTheme.textStyles.panelHelper,
                    color = marquee.onImage3
                )
                Text(
                    text = state.presetAppliedCount?.let {
                        stringResource(R.string.assessment_preset_state_applied, it)
                    } ?: stringResource(R.string.assessment_preset_state_unapplied),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage3
                )
            } else {
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_target),
                    stringResource(
                        R.string.assessment_preset_v_target,
                        effectiveTargetMb,
                        ShieldPreset.secondsOfReference4k(effectiveTargetMb)
                    )
                )
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_max),
                    stringResource(R.string.assessment_preset_v_max, ShieldPreset.MAX_BUFFER_MS / 1000)
                )
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_profile),
                    stringResource(R.string.assessment_preset_v_profile)
                )
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_parallel),
                    stringResource(
                        R.string.assessment_preset_v_parallel,
                        ShieldPreset.PARALLEL_CONNECTIONS,
                        ShieldPreset.PARALLEL_CHUNK_KB / 1024
                    )
                )
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_back),
                    stringResource(R.string.assessment_preset_v_back)
                )
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_afr),
                    stringResource(R.string.assessment_preset_v_afr)
                )
                SettingsResultLine(
                    stringResource(R.string.assessment_preset_k_mem),
                    // Live figures, not baked numbers: the safe limit is per-device (the F3
                    // re-tiering proved a hardcoded figure drifts) and the estimate previews the
                    // applier's safe-limit clamp so it matches what apply writes.
                    stringResource(
                        R.string.assessment_preset_v_mem,
                        ShieldPreset.effectiveEstimatedNativeMb(safeLimitMb),
                        safeLimitMb
                    )
                )
                Text(
                    text = stringResource(R.string.assessment_preset_untouched),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage3
                )
            }
        }
        if (state.presetExpanded) {
            // Separate rows BELOW the card (approved mockup), not inside the value block:
            // focus goes card -> Apply -> Cancel -> next row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                SettingsValueRow(
                    modifier = Modifier.weight(1.7f),
                    title = stringResource(
                        if (state.presetApplying) R.string.assessment_preset_apply_applying
                        else R.string.assessment_preset_apply_title
                    ),
                    subtitle = if (!state.presetApplying && state.presetArmed) {
                        stringResource(
                            R.string.assessment_preset_apply_confirm_sub,
                            ShieldPreset.expectedWriteCount(safeLimitMb)
                        )
                    } else {
                        null
                    },
                    value = null,
                    trailingIcon = null,
                    enabled = !state.presetApplying && !state.applying,
                    onClick = {
                        if (state.presetArmed) onApply() else state.presetArmed = true
                    }
                )
                SettingsValueRow(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.assessment_preset_cancel),
                    value = null,
                    trailingIcon = null,
                    enabled = !state.presetApplying && !state.applying,
                    onClick = {
                        state.presetExpanded = false
                        state.presetArmed = false
                    }
                )
            }
        }
    }
}

/** Focusable read-only recommendation card: title + recommended value, grounds, current. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AssessmentItemRow(item: AssessmentItem) {
    val marquee = NuvioTheme.marquee
    val valueColor = when (item.memoryStatus) {
        MemoryUsageStatus.DANGER -> NuvioTheme.colors.Error
        MemoryUsageStatus.WARNING -> NuvioTheme.colors.Error.copy(alpha = 0.75f)
        else -> NuvioPrimitives.white
    }
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.title,
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = item.recommendedValue, style = NuvioTheme.textStyles.settingsStepValue, color = valueColor)
        }
        Text(
            text = item.grounds,
            style = NuvioTheme.textStyles.panelHelper,
            color = marquee.onImage3,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        if (item.changeNeeded && !item.currentValue.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.assessment_currently, item.currentValue),
                style = NuvioTheme.textStyles.settingsNote,
                color = marquee.onImage3
            )
        } else if (!item.changeNeeded && !item.currentValue.isNullOrBlank() &&
            item.recommendedValue != stringResource(R.string.assessment_value_your_call) &&
            item.recommendedValue != stringResource(R.string.assessment_value_leave_as_is)
        ) {
            // Endorsement marker: the row asserts a concrete value and the device already holds
            // it. "No change" stays reserved for rows where the assessment declines to recommend.
            Text(
                text = stringResource(R.string.assessment_already_set),
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioTheme.colors.Success
            )
        }
    }
}

/** Unlike SettingsResultLine, the value takes the width and may wrap (the 2-line stream label). */
@Composable
private fun AssessmentFactRow(label: String, value: String, valueMaxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = NuvioTheme.textStyles.settingsResultLabel, color = NuvioTheme.marquee.onImage2)
        Text(
            text = value,
            style = NuvioTheme.textStyles.settingsStepValue,
            color = NuvioPrimitives.white,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
