@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.AUDIO_AMPLIFICATION_MAX_DB
import com.nuvio.tv.core.player.AUDIO_AMPLIFICATION_MIN_DB
import com.nuvio.tv.core.player.AUDIO_DELAY_HOLD_FAST_AFTER_MS
import com.nuvio.tv.core.player.AUDIO_DELAY_HOLD_FAST_STEP_MS
import com.nuvio.tv.core.player.AUDIO_DELAY_MAX_MS
import com.nuvio.tv.core.player.AUDIO_DELAY_MIN_MS
import com.nuvio.tv.core.player.AUDIO_DELAY_STEP_MS
import com.nuvio.tv.core.player.AudioOutputMode
import com.nuvio.tv.core.player.CENTER_MIX_LEVEL_MAX_DB
import com.nuvio.tv.core.player.CENTER_MIX_LEVEL_MIN_DB
import com.nuvio.tv.core.player.TrackInfo
import com.nuvio.tv.core.util.languageCodeToName
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.PanelStepperRow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Audio panel (approved sandbox `u6-player-panels-marquee` stages 4-5): the compact panel
 * bottom-end over clear video (Spec 25 Q1), eyebrow, the "Delay and gain" / "Back to tracks"
 * action row, then the track rows or the stepper rows (delay, PCM gain, centre mix) + Persist.
 */
@Composable
internal fun AudioSelectionOverlay(
    visible: Boolean,
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    outputMode: AudioOutputMode?,
    accent: () -> Color,
    onTrackSelected: (Int) -> Unit,
    onAudioDelayChange: (Int) -> Unit,
    onAudioDelayHoldStep: (Int) -> Unit,
    onAudioDelayHoldEnd: () -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tracksFocusRequester = remember { FocusRequester() }
    val delayMinusFocusRequester = remember { FocusRequester() }
    val delayPlusFocusRequester = remember { FocusRequester() }
    val ampMinusFocusRequester = remember { FocusRequester() }
    val ampPlusFocusRequester = remember { FocusRequester() }
    val centerMinusFocusRequester = remember { FocusRequester() }
    val centerPlusFocusRequester = remember { FocusRequester() }
    val persistFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingControlFocusTarget by rememberSaveable {
        mutableStateOf<AudioControlFocusTarget?>(null)
    }

    LaunchedEffect(visible, tracks, selectedIndex) {
        if (!visible) return@LaunchedEffect

        if (tracks.isNotEmpty()) {
            val selectedListIndex = tracks.indexOfFirst { it.index == selectedIndex }.takeIf { it >= 0 } ?: 0
            listState.scrollToItem(selectedListIndex)
            delay(120)
            runCatching { tracksFocusRequester.requestFocus() }
        } else {
            delay(120)
            val initialControlsFocusRequester = when {
                canDecreaseDelay -> delayMinusFocusRequester
                canIncreaseDelay -> delayPlusFocusRequester
                canDecreaseAmp -> ampMinusFocusRequester
                canIncreaseAmp -> ampPlusFocusRequester
                canDecreaseCenterMix -> centerMinusFocusRequester
                canIncreaseCenterMix -> centerPlusFocusRequester
                else -> persistFocusRequester
            }
            runCatching { initialControlsFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(visible, pendingControlFocusTarget, audioAmplificationDb, isAmplificationAvailable) {
        if (!visible) return@LaunchedEffect
        val target = pendingControlFocusTarget ?: return@LaunchedEffect
        val targetCanFocus = when (target) {
            AudioControlFocusTarget.DelayMinus -> canDecreaseDelay
            AudioControlFocusTarget.DelayPlus -> canIncreaseDelay
            AudioControlFocusTarget.AmpMinus -> canDecreaseAmp
            AudioControlFocusTarget.AmpPlus -> canIncreaseAmp
            AudioControlFocusTarget.CenterMinus -> canDecreaseCenterMix
            AudioControlFocusTarget.CenterPlus -> canIncreaseCenterMix
            AudioControlFocusTarget.Persist -> true
        }
        if (!targetCanFocus) return@LaunchedEffect
        val controlFocusRequester = when (target) {
            AudioControlFocusTarget.DelayMinus -> delayMinusFocusRequester
            AudioControlFocusTarget.DelayPlus -> delayPlusFocusRequester
            AudioControlFocusTarget.AmpMinus -> ampMinusFocusRequester
            AudioControlFocusTarget.AmpPlus -> ampPlusFocusRequester
            AudioControlFocusTarget.CenterMinus -> centerMinusFocusRequester
            AudioControlFocusTarget.CenterPlus -> centerPlusFocusRequester
            AudioControlFocusTarget.Persist -> persistFocusRequester
        }
        delay(80)
        controlFocusRequester.requestFocusAfterFrames(frames = 2)
        delay(200)
        runCatching { controlFocusRequester.requestFocus() }
        pendingControlFocusTarget = null
    }

    PlayerCompactPanel(
        visible = visible,
        title = stringResource(R.string.audio_dialog_title),
        modifier = modifier
    ) {
        var editorOpen by remember { mutableStateOf(false) }

        LaunchedEffect(editorOpen) {
            if (editorOpen) {
                runCatching { delayMinusFocusRequester.requestFocus() }
            } else {
                runCatching { tracksFocusRequester.requestFocus() }
            }
        }

        PanelActionRow(
            label = if (editorOpen) {
                stringResource(R.string.panel_back_to_tracks)
            } else {
                stringResource(R.string.panel_audio_adjustments)
            },
            icon = if (editorOpen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Tune,
            onClick = { editorOpen = !editorOpen }
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        if (editorOpen) {
            AudioControlsContent(
                audioDelayMs = audioDelayMs,
                audioAmplificationDb = audioAmplificationDb,
                isAmplificationAvailable = isAmplificationAvailable,
                centerMixLevelDb = centerMixLevelDb,
                isCenterMixAvailable = isCenterMixAvailable,
                persistAmplification = persistAmplification,
                delayMinusFocusRequester = delayMinusFocusRequester,
                delayPlusFocusRequester = delayPlusFocusRequester,
                ampMinusFocusRequester = ampMinusFocusRequester,
                ampPlusFocusRequester = ampPlusFocusRequester,
                centerMinusFocusRequester = centerMinusFocusRequester,
                centerPlusFocusRequester = centerPlusFocusRequester,
                persistFocusRequester = persistFocusRequester,
                leftFocusRequester = FocusRequester.Default,
                onAudioDelayChange = onAudioDelayChange,
                onAudioDelayHoldStep = onAudioDelayHoldStep,
                onAudioDelayHoldEnd = onAudioDelayHoldEnd,
                onAmplificationChange = { nextDb, focusTarget ->
                    pendingControlFocusTarget = focusTarget
                    onAmplificationChange(nextDb)
                },
                onCenterMixLevelChange = onCenterMixLevelChange,
                onPersistAmplificationChange = onPersistAmplificationChange
            )
        } else {
            AudioTracksContent(
                tracks = tracks,
                selectedIndex = selectedIndex,
                outputMode = outputMode,
                accent = accent,
                listState = listState,
                initialFocusRequester = tracksFocusRequester,
                rightFocusRequester = FocusRequester.Default,
                onTrackFocused = { lastFocusedAudioIndex = it },
                onTrackSelected = onTrackSelected
            )
        }
    }
}

@Composable
private fun AudioTracksContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    outputMode: AudioOutputMode?,
    accent: () -> Color,
    listState: LazyListState,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onTrackFocused: (Int) -> Unit,
    onTrackSelected: (Int) -> Unit
) {
    if (tracks.isEmpty()) {
        Text(
            text = stringResource(R.string.audio_lang_default),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.md)
        )
        return
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        modifier = Modifier
            .heightIn(max = 500.dp)
            .fillMaxWidth()
    ) {
        items(items = tracks, key = { track -> track.index }) { track ->
            AudioTrackCard(
                track = track,
                isSelected = track.index == selectedIndex,
                outputMode = outputMode,
                accent = accent,
                onFocused = { onTrackFocused(track.index) },
                onClick = { onTrackSelected(track.index) },
                rightFocusRequester = rightFocusRequester,
                focusRequester = if (
                    track.index == selectedIndex || (selectedIndex < 0 && track == tracks.firstOrNull())
                ) {
                    initialFocusRequester
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun AudioTrackCard(
    track: TrackInfo,
    isSelected: Boolean,
    outputMode: AudioOutputMode?,
    accent: () -> Color,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    rightFocusRequester: FocusRequester,
    focusRequester: FocusRequester?
) {
    val languageLine = track.language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { languageCodeToName(it) }
    val metadata = listOfNotNull(
        track.codec,
        track.channelCount?.let { "$it ch" },
        track.sampleRate?.let { "${it / 1000} kHz" }
    ).joinToString(" · ")
    val subtitle = listOfNotNull(
        languageLine,
        metadata.ifBlank { null }
    ).joinToString(" · ").ifBlank { null }

    PlayerPanelRow(
        title = track.name,
        subtitle = subtitle,
        // How the playing track leaves the box (P1), on the selected row only.
        subtitleMark = outputMode?.label?.takeIf { isSelected },
        markColor = accent,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
        modifier = Modifier.focusProperties { right = rightFocusRequester }
    )
}

@Composable
private fun AudioControlsContent(
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    delayMinusFocusRequester: FocusRequester,
    delayPlusFocusRequester: FocusRequester,
    ampMinusFocusRequester: FocusRequester,
    ampPlusFocusRequester: FocusRequester,
    centerMinusFocusRequester: FocusRequester,
    centerPlusFocusRequester: FocusRequester,
    persistFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onAudioDelayChange: (Int) -> Unit,
    onAudioDelayHoldStep: (Int) -> Unit,
    onAudioDelayHoldEnd: () -> Unit,
    onAmplificationChange: (Int, AudioControlFocusTarget) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB
    // The pressed button disables at the range limit; hand focus to the other one.
    fun focusOtherDelayButtonAtLimit(nextDelayMs: Int) {
        if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
            runCatching { delayPlusFocusRequester.requestFocus() }
        } else if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
            runCatching { delayMinusFocusRequester.requestFocus() }
        }
    }

    val firstDelayFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        delayPlusFocusRequester
    }
    val firstAmpFocusRequester = when {
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val firstCenterFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val persistUpFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseDelay -> delayMinusFocusRequester
        else -> delayPlusFocusRequester
    }
    val delayPlusLeftFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val ampPlusLeftFocusRequester = if (canDecreaseAmp) {
        ampMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val centerPlusLeftFocusRequester = if (canDecreaseCenterMix) {
        centerMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val persistLeftFocusRequester = when {
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        else -> leftFocusRequester
    }
    val centerHelperText = if (isCenterMixAvailable) {
        stringResource(R.string.audio_center_mix_help)
    } else {
        stringResource(R.string.audio_center_mix_unavailable)
    }

    val amplificationHelperText = when {
        !isAmplificationAvailable -> stringResource(R.string.audio_mix_unavailable)
        persistAmplification -> stringResource(
            R.string.audio_mix_range_saved,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
        else -> stringResource(
            R.string.audio_mix_range,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        PanelStepperRow(
            label = stringResource(R.string.audio_delay_label),
            value = formatAudioDelay(currentDelayMs),
            helper = stringResource(
                R.string.audio_delay_range,
                AUDIO_DELAY_MIN_MS / 1000f,
                AUDIO_DELAY_MAX_MS / 1000f
            ),
            canDecrease = canDecreaseDelay,
            canIncrease = canIncreaseDelay,
            minusFocusRequester = delayMinusFocusRequester,
            plusFocusRequester = delayPlusFocusRequester,
            minusLeft = leftFocusRequester,
            plusLeft = delayPlusLeftFocusRequester,
            down = firstAmpFocusRequester,
            onDecrease = {
                onAudioDelayChange(currentDelayMs - AUDIO_DELAY_STEP_MS)
                focusOtherDelayButtonAtLimit(currentDelayMs - AUDIO_DELAY_STEP_MS)
            },
            onIncrease = {
                onAudioDelayChange(currentDelayMs + AUDIO_DELAY_STEP_MS)
                focusOtherDelayButtonAtLimit(currentDelayMs + AUDIO_DELAY_STEP_MS)
            },
            onDecreaseHold = { heldMs ->
                val stepMs = audioDelayHoldStepMs(heldMs)
                onAudioDelayHoldStep(-stepMs)
                focusOtherDelayButtonAtLimit(currentDelayMs - stepMs)
            },
            onIncreaseHold = { heldMs ->
                val stepMs = audioDelayHoldStepMs(heldMs)
                onAudioDelayHoldStep(stepMs)
                focusOtherDelayButtonAtLimit(currentDelayMs + stepMs)
            },
            onHoldEnd = onAudioDelayHoldEnd
        )

        PanelStepperRow(
            label = stringResource(R.string.audio_mix_label),
            value = stringResource(R.string.audio_mix_value_db, currentDb),
            helper = amplificationHelperText,
            enabled = isAmplificationAvailable,
            canDecrease = canDecreaseAmp,
            canIncrease = canIncreaseAmp,
            minusFocusRequester = ampMinusFocusRequester,
            plusFocusRequester = ampPlusFocusRequester,
            minusLeft = leftFocusRequester,
            plusLeft = ampPlusLeftFocusRequester,
            up = firstDelayFocusRequester,
            down = firstCenterFocusRequester,
            onDecrease = {
                val nextDb = currentDb - 1
                val target = if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                    AudioControlFocusTarget.AmpPlus
                } else {
                    AudioControlFocusTarget.AmpMinus
                }
                onAmplificationChange(nextDb, target)
                if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                    runCatching { ampPlusFocusRequester.requestFocus() }
                }
            },
            onIncrease = {
                val nextDb = currentDb + 1
                val target = if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                    AudioControlFocusTarget.AmpMinus
                } else {
                    AudioControlFocusTarget.AmpPlus
                }
                onAmplificationChange(nextDb, target)
                if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                    runCatching { ampMinusFocusRequester.requestFocus() }
                }
            }
        )

        PanelStepperRow(
            label = stringResource(R.string.audio_center_mix_label),
            value = stringResource(R.string.audio_center_mix_value_db, currentCenterMixDb),
            helper = centerHelperText,
            enabled = isCenterMixAvailable,
            canDecrease = canDecreaseCenterMix,
            canIncrease = canIncreaseCenterMix,
            minusFocusRequester = centerMinusFocusRequester,
            plusFocusRequester = centerPlusFocusRequester,
            minusLeft = leftFocusRequester,
            plusLeft = centerPlusLeftFocusRequester,
            up = firstAmpFocusRequester,
            down = persistFocusRequester,
            onDecrease = {
                val nextDb = currentCenterMixDb - 1
                onCenterMixLevelChange(nextDb)
                if (nextDb <= CENTER_MIX_LEVEL_MIN_DB && canIncreaseCenterMix) {
                    runCatching { centerPlusFocusRequester.requestFocus() }
                }
            },
            onIncrease = {
                val nextDb = currentCenterMixDb + 1
                onCenterMixLevelChange(nextDb)
                if (nextDb >= CENTER_MIX_LEVEL_MAX_DB && canDecreaseCenterMix) {
                    runCatching { centerMinusFocusRequester.requestFocus() }
                }
            }
        )

        PanelActionRow(
            label = if (persistAmplification) {
                stringResource(R.string.audio_mix_persist_on)
            } else {
                stringResource(R.string.audio_mix_persist_off)
            },
            onClick = { onPersistAmplificationChange(!persistAmplification) },
            focusRequester = persistFocusRequester,
            modifier = Modifier
                .padding(top = NuvioTheme.marquee.playerPanel.actionTop)
                .focusProperties {
                    left = persistLeftFocusRequester
                    up = persistUpFocusRequester
                }
        )
    }
}

private enum class AudioControlFocusTarget {
    DelayMinus,
    DelayPlus,
    AmpMinus,
    AmpPlus,
    CenterMinus,
    CenterPlus,
    Persist
}

/** Held +/- step: the tap step, then the fast step once held [AUDIO_DELAY_HOLD_FAST_AFTER_MS]. */
internal fun audioDelayHoldStepMs(heldMs: Long): Int =
    if (heldMs >= AUDIO_DELAY_HOLD_FAST_AFTER_MS) AUDIO_DELAY_HOLD_FAST_STEP_MS else AUDIO_DELAY_STEP_MS

private fun formatAudioDelay(delayMs: Int): String {
    return if (delayMs == 0) {
        String.format(Locale.US, "%.3fs", 0f)
    } else {
        String.format(Locale.US, "%+.3fs", delayMs / 1000f)
    }
}
