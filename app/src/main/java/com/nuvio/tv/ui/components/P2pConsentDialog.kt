package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import kotlinx.coroutines.launch

private const val SCROLL_STEP_PX = 100f

private fun String.isRtl(): Boolean {
    for (char in this) {
        val directionality = Character.getDirectionality(char)
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            return true
        }
        if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return false
        }
    }
    return false
}

/**
 * P2P consent (approved sandbox `u5-stream-screen-marquee` stage 9; the Stream screen and both
 * playback settings screens): a compact panel centred over the veil, the as-built copy (its
 * direction follows the text), the body scrolls with Up/Down when it overflows (only then is it
 * a focus stop); "Enable P2P" and Cancel are both glass text buttons, so only the focused one is
 * white (U5n, stage 9 v4). Cancel takes focus: the Stream screen can open this unprompted
 * (auto-play), and consent must not be one reflexive OK. Back cancels.
 */
@Composable
fun P2pConsentDialog(
    onEnableP2p: () -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val cancelFocus = remember { FocusRequester() }
    val bodyText = stringResource(R.string.p2p_consent_body)
    val isRtl = remember(bodyText) { bodyText.isRtl() }
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = marquee.search.pickerDim,
        width = tokens.consentWidth,
        initialFocus = cancelFocus
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            // Full width, so its children align by the text's direction.
            Column(modifier = Modifier.fillMaxWidth()) {
                PanelEyebrow(text = stringResource(R.string.p2p_consent_title))

                val scrollState = rememberScrollState()
                val coroutineScope = rememberCoroutineScope()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val delta = when (event.key) {
                                Key.DirectionDown -> SCROLL_STEP_PX.takeIf { scrollState.canScrollForward }
                                Key.DirectionUp -> (-SCROLL_STEP_PX).takeIf { scrollState.canScrollBackward }
                                else -> null
                            } ?: return@onPreviewKeyEvent false
                            coroutineScope.launch { scrollState.animateScrollBy(delta) }
                            true
                        }
                        .focusable(enabled = scrollState.maxValue > 0)
                ) {
                    Text(
                        text = bodyText,
                        style = NuvioTheme.textStyles.panelBody.copy(
                            textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        color = tokens.dialogBody,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(horizontal = tokens.dialogTextInset)
                    )
                }

                MarqueePanelActions {
                    MarqueeGlassTextButton(
                        label = stringResource(R.string.p2p_consent_enable),
                        onClick = onEnableP2p
                    )
                    MarqueeGlassTextButton(
                        label = stringResource(R.string.p2p_consent_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocus)
                    )
                }
            }
        }
    }
}
