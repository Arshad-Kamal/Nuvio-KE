package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Stage 13 rename side panel (Q8, U10's field): [eyebrow], the name field (starts on [current],
 * focused, at most [maxLength]), the [resetLabel] row with [originalName] under it (OK puts the
 * original back in the field), [note], then Cancel / Save. Save hands the field's text to [onSave]
 * (blank or equal to [originalName] means "use the original"; the store decides); the reset text
 * goes as the whole [originalName] even past [maxLength], and an unedited field just closes, so a
 * truncated or " (2)"-suffixed display name never becomes a stored name. A null [resetLabel]
 * drops the reset row (a Live TV source has no original name to go back to). Back or Cancel calls
 * [onDismiss].
 */
@Composable
internal fun RenamePanel(
    eyebrow: String,
    current: String,
    originalName: String,
    resetLabel: String?,
    note: String,
    maxLength: Int,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val settings = NuvioTheme.marquee.settings
    val stream = NuvioTheme.marquee.stream
    val initial = current.take(maxLength)
    val originalInField = originalName.take(maxLength)
    var draft by rememberSaveable { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = settings.pickerWidth,
        insetVertical = settings.pickerInsetVertical,
        initialFocus = fieldFocus
    ) {
        PanelEyebrow(text = eyebrow)
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.collections.panelGap)) {
            MarqueeNameField(
                value = draft,
                onValueChange = { draft = it },
                maxLength = maxLength,
                placeholder = originalName,
                focusRequester = fieldFocus,
                onDone = { saveFocus.requestFocus() }
            )
            if (resetLabel != null) {
                PlayerPanelRow(
                    title = resetLabel,
                    subtitle = originalName,
                    selected = false,
                    trailingIcon = Icons.Default.Refresh,
                    onClick = { draft = originalInField }
                )
            }
            Text(
                text = note,
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioTheme.marquee.onImage2,
                modifier = Modifier.padding(horizontal = stream.dialogTextInset)
            )
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_save),
                onClick = {
                    when (draft) {
                        initial -> onDismiss()
                        originalInField -> onSave(originalName)
                        else -> onSave(draft)
                    }
                },
                modifier = Modifier.focusRequester(saveFocus)
            )
        }
    }
}
