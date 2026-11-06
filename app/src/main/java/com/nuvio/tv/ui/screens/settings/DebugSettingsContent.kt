package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Spec 31, Debug pane (debug / benchmark builds only; no mockup stage, copy as built, restyled on
 * the S8 row kit): the simulated playback-error popup, the loading-indicator preview and two
 * feature toggles.
 */
@Composable
fun DebugSettingsContent(
    viewModel: DebugSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    // Null until loaded (see the ViewModel); rows wait for it.
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    var showErrorDialog by remember { mutableStateOf(false) }

    SettingsPane(title = stringResource(R.string.debug_title), subtitle = stringResource(R.string.debug_subtitle)) {
        if (uiState == null) return@SettingsPane
        item(key = "debug_popup_section") {
            SettingsSectionLabel(text = stringResource(R.string.debug_section_popup))
        }
        item(key = "debug_playback_error") {
            SettingsValueRow(
                title = stringResource(R.string.debug_playback_error_title),
                subtitle = stringResource(R.string.debug_playback_error_subtitle),
                value = null,
                onClick = { showErrorDialog = true },
                modifier = Modifier.optionalFocusRequester(initialFocusRequester)
            )
        }
        item(key = "debug_progress_section") {
            SettingsSectionLabel(text = stringResource(R.string.debug_section_progress))
        }
        item(key = "debug_progress_indicator") {
            // Focusable so the preview scrolls into view; OK has nothing to do.
            MarqueeFormRow(
                label = stringResource(R.string.debug_progress_indicator_title),
                helper = stringResource(R.string.debug_progress_indicator_subtitle),
                onClick = {},
                minHeight = NuvioTheme.marquee.settings.rowMinHeight
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DebugProgressIndicatorPreview(stringResource(R.string.debug_progress_indicator_small), 18.dp)
                    DebugProgressIndicatorPreview(
                        stringResource(R.string.debug_progress_indicator_default),
                        NuvioTheme.spacing.xxxl
                    )
                    DebugProgressIndicatorPreview(stringResource(R.string.debug_progress_indicator_large), 56.dp)
                }
            }
        }
        item(key = "debug_features_section") {
            SettingsSectionLabel(text = stringResource(R.string.debug_section_features))
        }
        item(key = "debug_toggle_compose_highlighter") {
            SettingsSwitchRow(
                title = stringResource(R.string.advanced_compose_highlighter),
                subtitle = stringResource(R.string.advanced_compose_highlighter_subtitle),
                checked = uiState.composeHighlighterEnabled,
                onCheckedChange = { viewModel.onEvent(DebugSettingsEvent.ToggleComposeHighlighter(it)) }
            )
        }
        item(key = "debug_toggle_buffer_logs") {
            SettingsSwitchRow(
                title = stringResource(R.string.debug_buffer_logs_title),
                subtitle = stringResource(R.string.debug_buffer_logs_subtitle),
                checked = uiState.bufferLogsEnabled,
                onCheckedChange = { viewModel.onEvent(DebugSettingsEvent.ToggleBufferLogs(it)) }
            )
        }
    }

    if (showErrorDialog) {
        NuvioDialog(
            onDismiss = { showErrorDialog = false },
            title = stringResource(R.string.debug_error_dialog_title),
            subtitle = stringResource(R.string.debug_error_dialog_subtitle)
        ) {
            DebugDialogButton(
                text = stringResource(R.string.debug_dismiss),
                onClick = { showErrorDialog = false }
            )
        }
    }
}

@Composable
private fun DebugProgressIndicatorPreview(
    label: String,
    size: Dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        LoadingIndicator(modifier = Modifier.size(size))
        Text(
            text = label,
            style = NuvioTheme.textStyles.settingsNote,
            color = NuvioTheme.marquee.onImage3
        )
    }
}

@Composable
private fun DebugDialogButton(
    text: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.85f),
            focusedContainerColor = Color.White
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1.0f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NuvioTheme.spacing.md, horizontal = NuvioTheme.spacing.lg),
            textAlign = TextAlign.Center
        )
    }
}
