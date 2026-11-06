package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.SubtitleSyncCue
import com.nuvio.tv.core.player.formatAutoSyncTimestamp
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class SyncStage {
    WAIT_FOR_SYNC,
    PICK_LINE
}

/** Lines shown above the pre-focused one, so the user sees what was said just before the capture. */
private const val CUE_CONTEXT_ROWS_ABOVE = 2
private val ASS_OVERRIDE_TAG_REGEX = Regex("""\{\\[^{}]*\}""")
// As-built status / error colours (stage 9: "status, loading and error copy as built").
private val SyncStatusColor = Color(0xFF9BE2AF)
private val SyncErrorColor = Color(0xFFFFB37A)

/**
 * The cue time the Sync Line list centres and pre-focuses on: the line on screen at video time
 * [videoMs]. A delay D shows a cue starting at S at video S + D (applySubtitleAutoSyncCue sets
 * D = capture - start), so that line starts at video - D (Register A2 P8, NM `6d142b944`).
 */
internal fun syncLineAnchorMs(videoMs: Long, subtitleDelayMs: Int): Long =
    (videoMs - subtitleDelayMs).coerceAtLeast(0L)

/**
 * Sync line (approved sandbox `u6-player-panels-marquee` stage 9): a centred compact shell, eyebrow
 * "Sync Line". Step 1 asks for Sync at a dialog line; step 2 lists the lines around the captured
 * moment (time, line) with the nearest focused; OK applies it.
 */
@Composable
internal fun SubtitleTimingDialog(
    modifier: Modifier = Modifier,
    currentPositionMs: Long,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val syncButtonFocusRequester = remember { FocusRequester() }
    val anchorMs = syncLineAnchorMs(capturedVideoMs ?: currentPositionMs, subtitleDelayMs)
    val visibleCues = remember(cues, anchorMs) {
        selectAutoSyncVisibleCues(
            cues = cues,
            anchorTimeMs = anchorMs
        )
    }
    var stage by remember(capturedVideoMs) {
        mutableStateOf(if (capturedVideoMs != null) SyncStage.PICK_LINE else SyncStage.WAIT_FOR_SYNC)
    }

    LaunchedEffect(capturedVideoMs) {
        stage = if (capturedVideoMs != null) SyncStage.PICK_LINE else SyncStage.WAIT_FOR_SYNC
    }

    LaunchedEffect(stage) {
        if (stage == SyncStage.WAIT_FOR_SYNC) {
            delay(120)
            try {
                syncButtonFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus target may not be attached yet.
            }
        }
    }

    CompactPanel(
        modifier = modifier.width(tokens.syncWidth),
        contentPadding = PaddingValues(
            horizontal = tokens.centredPaddingHorizontal,
            vertical = tokens.centredPaddingVertical
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = tokens.contentInset, bottom = tokens.contentInset),
            horizontalArrangement = Arrangement.spacedBy(tokens.contentInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelEyebrowText(
                text = stringResource(R.string.player_sync_line),
                modifier = Modifier.weight(1f)
            )
            if (stage == SyncStage.PICK_LINE) {
                val captured = if (capturedVideoMs != null) {
                    stringResource(R.string.subtitle_timing_captured_at, formatAutoSyncTimestamp(capturedVideoMs))
                } else {
                    stringResource(R.string.subtitle_timing_capturing)
                }
                val language = selectedAddonSubtitle?.let { Subtitle.languageCodeToName(it.lang) }
                Text(
                    text = listOfNotNull(captured, language).joinToString(" · "),
                    style = NuvioTheme.textStyles.caption,
                    color = NuvioTheme.marquee.onImage3,
                    maxLines = 1
                )
            }
        }
        PanelRule()
        when (stage) {
            SyncStage.WAIT_FOR_SYNC -> SyncPromptPanel(
                onCaptureNow = {
                    stage = SyncStage.PICK_LINE
                    onCaptureNow()
                },
                focusRequester = syncButtonFocusRequester
            )

            SyncStage.PICK_LINE -> CueSelectionPanel(
                anchorMs = anchorMs,
                selectedAddonSubtitle = selectedAddonSubtitle,
                cues = visibleCues,
                capturedVideoMs = capturedVideoMs,
                statusMessage = statusMessage,
                errorMessage = errorMessage,
                isLoadingCues = isLoadingCues,
                onCueSelected = onCueSelected
            )
        }
    }
}

@Composable
private fun SyncPromptPanel(
    onCaptureNow: () -> Unit,
    focusRequester: FocusRequester
) {
    val inset = NuvioTheme.marquee.playerPanel.contentInset
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = inset),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SyncBodyText(text = stringResource(R.string.subtitle_timing_press_sync))
        MarqueePrimaryButton(
            label = stringResource(R.string.subtitle_timing_sync_button),
            icon = null,
            onClick = onCaptureNow,
            focusRequester = focusRequester
        )
    }
}

@Composable
private fun SyncBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NuvioPrimitives.white.copy(alpha = 0.8f)
) {
    Text(text = text, style = NuvioTheme.textStyles.bodyCompact, color = color, modifier = modifier)
}

@Composable
private fun CueSelectionPanel(
    anchorMs: Long,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val nearestCueIndex = remember(cues, anchorMs) {
        cues.indices.minByOrNull { index -> abs(cues[index].startTimeMs - anchorMs) } ?: 0
    }
    val cueListState = rememberLazyListState()
    val nearestCueFocusRequester = remember(capturedVideoMs, nearestCueIndex, cues.size) { FocusRequester() }

    LaunchedEffect(capturedVideoMs, nearestCueIndex, cues.size) {
        if (capturedVideoMs != null && cues.isNotEmpty()) {
            val nearest = nearestCueIndex.coerceIn(0, cues.lastIndex)
            cueListState.scrollToItem((nearest - CUE_CONTEXT_ROWS_ABOVE).coerceAtLeast(0))
            delay(50)
            try {
                nearestCueFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus target may not be attached yet.
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val textModifier = Modifier.padding(start = tokens.contentInset, end = tokens.contentInset, bottom = 8.dp)
        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage,
                style = NuvioTheme.textStyles.caption,
                color = SyncStatusColor,
                modifier = textModifier
            )
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = NuvioTheme.textStyles.caption,
                color = SyncErrorColor,
                modifier = textModifier
            )
            return
        }

        if (selectedAddonSubtitle == null) {
            SyncBodyText(
                text = stringResource(R.string.subtitle_timing_select_addon_first),
                modifier = textModifier,
                color = SyncErrorColor
            )
            return
        }

        if (isLoadingCues) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.cueListMaxHeight),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.xl))
                    SyncBodyText(text = stringResource(R.string.subtitle_timing_loading))
                }
            }
            return
        }

        if (cues.isEmpty()) {
            SyncBodyText(text = stringResource(R.string.subtitle_timing_no_lines_found), modifier = textModifier)
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = tokens.cueListMaxHeight),
            state = cueListState
        ) {
            itemsIndexed(
                items = cues
                // No key: startMs+text collide on duplicated cues in corrupted/OCR'd SRTs
                // and crash LazyColumn; default positional keys are correct here.
            ) { index, cue ->
                CueRow(
                    cue = cue,
                    focusRequester = if (index == nearestCueIndex) nearestCueFocusRequester else null,
                    onClick = { onCueSelected(cue) }
                )
            }
        }

        Text(
            text = stringResource(R.string.subtitle_timing_press_back_cancel),
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage3,
            modifier = Modifier.padding(start = tokens.contentInset, top = tokens.contentInset)
        )
    }
}

/** A cue row (stage 9 `.pr`): time column then the line; focused = white pill, black text. */
@Composable
private fun CueRow(
    cue: SubtitleSyncCue,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val content = if (isFocused) NuvioPrimitives.black else NuvioPrimitives.white
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) NuvioPrimitives.white else Color.Transparent)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatAutoSyncTimestamp(cue.startTimeMs),
            style = NuvioTheme.textStyles.caption,
            color = content.copy(alpha = 0.6f),
            maxLines = 1,
            modifier = Modifier.width(tokens.cueTimeWidth)
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = sanitizeCuePreviewText(cue.text),
                style = NuvioTheme.textStyles.bodyCompact,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun sanitizeCuePreviewText(text: String): String {
    val cleaned = text
        .replace(ASS_OVERRIDE_TAG_REGEX, "")
        .replace("\\N", " ")
        .replace("\\n", " ")
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (cleaned.isNotBlank()) cleaned else text.trim()
}

private fun selectAutoSyncVisibleCues(
    cues: List<SubtitleSyncCue>,
    anchorTimeMs: Long,
    marginMs: Long = 180_000L,
    maxVisible: Int = 90
): List<SubtitleSyncCue> {
    if (cues.isEmpty()) return emptyList()
    val sorted = cues.sortedBy { it.startTimeMs }
    val lower = (anchorTimeMs - marginMs).coerceAtLeast(0L)
    val upper = anchorTimeMs + marginMs
    val inWindow = sorted.filter { cue -> cue.startTimeMs in lower..upper }
    if (inWindow.isNotEmpty()) {
        if (inWindow.size <= maxVisible) return inWindow
        val centerIndex = inWindow.indices.minByOrNull { index ->
            abs(inWindow[index].startTimeMs - anchorTimeMs)
        } ?: 0
        return takeCentered(inWindow, centerIndex, maxVisible)
    }

    val nearestIndex = sorted.indices.minByOrNull { index ->
        abs(sorted[index].startTimeMs - anchorTimeMs)
    } ?: 0
    return takeCentered(sorted, nearestIndex, maxVisible)
}

private fun takeCentered(
    items: List<SubtitleSyncCue>,
    centerIndex: Int,
    maxVisible: Int
): List<SubtitleSyncCue> {
    if (items.size <= maxVisible) return items
    val half = maxVisible / 2
    var start = (centerIndex - half).coerceAtLeast(0)
    val end = (start + maxVisible).coerceAtMost(items.size)
    if (end - start < maxVisible) {
        start = (end - maxVisible).coerceAtLeast(0)
    }
    return items.subList(start, end)
}
