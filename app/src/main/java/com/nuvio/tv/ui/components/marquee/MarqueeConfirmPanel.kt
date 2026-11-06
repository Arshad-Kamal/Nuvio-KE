package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A confirm (approved sandbox `u8-collections-marquee` stage 7, the U5 consent panel's shape): a
 * compact panel centred over the veil with [eyebrow], [body], then Cancel (focused, so a
 * reflexive OK never confirms) and [confirmLabel], red at rest when [destructive]. Back and
 * Cancel call [onDismiss]; [onConfirm] runs the action (the caller closes the panel).
 */
@Composable
internal fun MarqueeConfirmPanel(
    eyebrow: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    val tokens = NuvioTheme.marquee.stream
    val cancelFocus = remember { FocusRequester() }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = tokens.confirmDim,
        width = tokens.confirmWidth,
        initialFocus = cancelFocus
    ) {
        PanelEyebrow(text = eyebrow)
        Text(
            text = body,
            style = NuvioTheme.textStyles.panelBody,
            color = tokens.dialogBody,
            modifier = Modifier.padding(horizontal = tokens.dialogTextInset)
        )
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = cancelLabel,
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus)
            )
            MarqueeGlassTextButton(
                label = confirmLabel,
                onClick = onConfirm,
                restingLabelColor = if (destructive) NuvioPrimitives.errorOnImage else NuvioPrimitives.white
            )
        }
    }
}
