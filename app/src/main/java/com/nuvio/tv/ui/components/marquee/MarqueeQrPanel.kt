package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The QR side panel (approved sandbox `u11-settings-marquee` stage 12, `.l11qp`): [eyebrow], a QR
 * of [url], the [hostLabel] line (the caller passes a host, never a token-bearing path), [body],
 * then an optional [actionLabel] button (focused on open) running [onAction]. The QR is encoded
 * off the main thread, once per [url]; the square stays empty if encoding fails. Back calls
 * [onDismiss].
 */
@Composable
internal fun MarqueeQrPanel(
    eyebrow: String,
    url: String,
    hostLabel: String,
    body: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    val actionFocus = remember { FocusRequester() }
    val hasAction = actionLabel != null && onAction != null
    val qr = rememberQrCode(url)
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = tokens.qrPanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = if (hasAction) actionFocus else null
    ) {
        PanelEyebrow(text = eyebrow)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.qrGap)
        ) {
            Box(modifier = Modifier.size(tokens.qrSize)) {
                qr?.let { bitmap ->
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                text = hostLabel,
                style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.SemiBold),
                color = marquee.onImage2,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = body,
                style = NuvioTheme.textStyles.settingsNote,
                color = marquee.onImage2,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                MarqueeGlassTextButton(
                    label = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.focusRequester(actionFocus),
                    height = tokens.qrActionHeight,
                    horizontalPadding = tokens.qrActionPaddingHorizontal
                )
            }
        }
    }
}

/**
 * The QR of [url] (white card, 1-module margin), encoded off the main thread once per [url]; null
 * while it encodes or when encoding fails (the caller leaves its square empty).
 */
@Composable
internal fun rememberQrCode(url: String): ImageBitmap? {
    val qr by produceState<ImageBitmap?>(initialValue = null, url) {
        value = null // never show the previous url's code while this one encodes
        value = withContext(Dispatchers.Default) {
            runCatching { QrCodeGenerator.generate(url, QR_BITMAP_PX, margin = 1).asImageBitmap() }.getOrNull()
        }
    }
    return qr
}

/** Encoded size; the account QR uses the same (drawn at [qrSize][com.nuvio.tv.ui.theme.NuvioMarqueeSettingsTokens.qrSize]). */
private const val QR_BITMAP_PX = 420
