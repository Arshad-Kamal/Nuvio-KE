package com.nuvio.tv.ui.screens.settings.trackers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingSetupStep
import com.nuvio.tv.core.tracking.TrackingSetupText
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.screens.settings.SettingsPhoneQrBlock
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A tracker's per-TV setup panel (Spec 106 K2/K4/K5; sandbox `spec-106-simkl` v2), generic over its [TrackingSetupText].
 * Nothing saved (K2): the steps, the phone page's QR and Done. Saved (K4): what ends the saved value, what comes next,
 * the QR to replace it, Remove id and Connect (focused); once [connected] (K5, opened from the account panel) Done
 * stands in for Connect. Remove asks first (Spec 104 A4's confirm, Cancel focused). [phoneUrl] null = no QR.
 */
@Composable
internal fun TrackerSetupPanel(
    name: String,
    text: TrackingSetupText,
    savedHint: String?,
    connected: Boolean,
    phoneUrl: String?,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val mainFocus = remember { FocusRequester() }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.trackers_setup_remove_title, stringResource(text.valueName)),
            body = stringResource(text.removeBody),
            confirmLabel = stringResource(R.string.trackers_setup_remove),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
            destructive = true
        )
        return
    }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = mainFocus
    ) {
        val inset = Modifier.padding(horizontal = marquee.settings.panelTextInset)
        if (savedHint == null) {
            PanelEyebrow(text = stringResource(R.string.trackers_setup_eyebrow, name))
            SetupSteps(text.steps, modifier = inset)
        } else {
            PanelEyebrow(text = name)
            SavedHead(stringResource(text.valueName), savedHint, modifier = inset)
            if (!connected) {
                Text(
                    text = stringResource(text.savedBody),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2,
                    modifier = inset.padding(top = 8.dp)
                )
            }
        }
        phoneUrl?.let { url ->
            SettingsPhoneQrBlock(
                url = url,
                divider = stringResource(
                    if (savedHint == null) R.string.trackers_setup_phone_divider else R.string.trackers_setup_replace_divider
                ),
                title = stringResource(if (savedHint == null) text.phoneTitle else text.replaceTitle)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        MarqueePanelActions {
            if (savedHint != null) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.trackers_setup_remove),
                    onClick = { confirmRemove = true },
                    height = marquee.primaryButton.compactHeight,
                    horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall,
                    restingLabelColor = NuvioPrimitives.errorOnImage
                )
            }
            val connectNext = savedHint != null && !connected
            MarqueePrimaryButton(
                label = stringResource(if (connectNext) R.string.trackers_row_connect else R.string.trackers_setup_done),
                icon = null,
                onClick = if (connectNext) onConnect else onDismiss,
                focusRequester = mainFocus,
                compact = true
            )
        }
    }
}

/** `.s52steps`: numbered circles, each step's [TrackingSetupStep.boldArgs] in white semibold. */
@Composable
private fun SetupSteps(steps: List<TrackingSetupStep>, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    Column(modifier = modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(19.dp).background(NuvioPrimitives.white.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = NuvioTheme.textStyles.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                        color = NuvioPrimitives.white,
                        textAlign = TextAlign.Center
                    )
                }
                val args = step.boldArgs.map { stringResource(it) }
                Text(
                    text = withBold(stringResource(step.text, *args.toTypedArray()), args),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
            }
        }
    }
}

/** `.s106id`: "Simkl app id **saved** · ends 7f3a". */
@Composable
private fun SavedHead(valueName: String, hint: String, modifier: Modifier) {
    val saved = stringResource(R.string.trackers_setup_saved_word)
    Text(
        text = withBold(stringResource(R.string.trackers_setup_saved_head, valueName, hint), listOf(saved)),
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = modifier.padding(top = 2.dp)
    )
}

/** [text] with the first occurrence of each of [bold] in white semibold. */
private fun withBold(text: String, bold: List<String>): AnnotatedString = buildAnnotatedString {
    append(text)
    bold.filter(String::isNotEmpty).forEach { part ->
        val start = text.indexOf(part)
        if (start >= 0) addStyle(SpanStyle(color = NuvioPrimitives.white, fontWeight = FontWeight.SemiBold), start, start + part.length)
    }
}
