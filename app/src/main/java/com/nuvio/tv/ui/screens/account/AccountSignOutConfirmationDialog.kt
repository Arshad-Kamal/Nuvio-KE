package com.nuvio.tv.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel

/** Sign out? (sandbox u11-settings-marquee stage `signout`): Cancel focused, Sign out in red. */
@Composable
internal fun AccountSignOutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    MarqueeConfirmPanel(
        eyebrow = stringResource(R.string.account_sign_out_confirm_title),
        body = stringResource(R.string.account_sign_out_confirm_body),
        confirmLabel = stringResource(R.string.account_sign_out_action),
        cancelLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true
    )
}
