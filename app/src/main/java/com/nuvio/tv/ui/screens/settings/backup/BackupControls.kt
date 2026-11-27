package com.nuvio.tv.ui.screens.settings.backup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.backup.BackupCrypto
import com.nuvio.tv.ui.components.PinKeypad
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.rememberQrCode
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.screens.settings.SettingsPanelErrorLine
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** A panel's quiet button (`.s52btns .btn`). */
@Composable
internal fun BackupSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    CollectionsHeaderButton(label = label, onClick = onClick, modifier = modifier)

/**
 * A panel's white button (`.btn.pri`). While [working] it reads its busy label (the caller passes it as
 * [label]) and dims; dimmed too while not [enabled]; OK does nothing in either state.
 */
@Composable
internal fun BackupPrimaryButton(
    label: String,
    onClick: () -> Unit,
    working: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    MarqueePrimaryButton(
        label = label,
        icon = null,
        onClick = { if (!working && enabled) onClick() },
        modifier = if (working || !enabled) Modifier.alpha(NuvioTheme.marquee.settings.busyAlpha) else Modifier,
        focusRequester = focusRequester,
        compact = true,
    )
}

/**
 * b3 / b10: [title] and [note], the PIN field (dots only, never the digits), the keypad (focus on "5"),
 * then [onSkip] and the [primary] button, which takes a PIN of 6-12 digits. The digits live only in this
 * composition (never saved state) and clear when [resetKey] changes (the second entry, a mismatch, a
 * wrong PIN).
 */
@Composable
internal fun BackupPinEntry(
    eyebrow: String,
    title: String,
    note: String,
    error: String?,
    primary: String,
    working: Boolean,
    resetKey: Any,
    onSkip: () -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyFocus = remember { FocusRequester() }
    var digits by remember(resetKey) { mutableStateOf("") }
    val valid = BackupCrypto.isValidPin(digits)
    BackupPanelFrame(
        eyebrow = eyebrow,
        onDismiss = onDismiss,
        initialFocus = keyFocus,
        actions = {
            BackupSecondaryButton(stringResource(R.string.backup_pin_skip), { if (!working) onSkip() })
            BackupPrimaryButton(label = primary, onClick = { onSubmit(digits) }, working = working, enabled = valid)
        },
    ) {
        BackupPanelTitle(title, note)
        BackupPinField(
            digits = digits.length,
            count = pluralStringResource(R.plurals.backup_pin_digits, digits.length, digits.length),
            error = error != null,
        )
        if (error != null) SettingsPanelErrorLine(error)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            PinKeypad(
                enabled = !working,
                initialFocus = keyFocus,
                onDigit = { if (digits.length < BackupCrypto.PIN_MAX_DIGITS) digits += it },
                onBackspace = { digits = digits.dropLast(1) },
            )
        }
    }
}

/** `.b1qrw`: the QR of [url] (white card), [title] over [body], then the address; the screen stays on. */
@Composable
internal fun BackupQrBlock(url: String, title: String, body: String) {
    val marquee = NuvioTheme.marquee
    val qr = rememberQrCode(url)
    Column(
        modifier = Modifier.fillMaxWidth().keepScreenOn().padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(BackupDims.qrSize)
                .clip(RoundedCornerShape(BackupDims.qrRadius))
                .background(NuvioPrimitives.white),
            contentAlignment = Alignment.Center,
        ) {
            qr?.let {
                Image(bitmap = it, contentDescription = stringResource(R.string.cd_qr_code), modifier = Modifier.size(BackupDims.qrSize * BackupDims.QR_FILL))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = NuvioTheme.textStyles.meta.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center,
            )
            Text(text = body, style = NuvioTheme.textStyles.settingsNote, color = marquee.onImage2, textAlign = TextAlign.Center)
        }
        Text(
            text = url,
            style = NuvioTheme.textStyles.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
            color = NuvioPrimitives.white,
            textAlign = TextAlign.Center,
        )
    }
}
