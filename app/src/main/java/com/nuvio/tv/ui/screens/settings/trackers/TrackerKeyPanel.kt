package com.nuvio.tv.ui.screens.settings.trackers

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.server.PasteKeyPageStrings
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.screens.settings.SettingsPhoneQrBlock
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A key tracker's own words (Spec 105 v4 E1-E4) and its phone page: the generic ones live in strings as `trackers_*`.
 * [phonePagePort] is the first of the page's ten ports (8160-8169 Simkl's setup, 8190-8199 MDBList).
 */
internal data class TrackerKeyText(
    /** E1 row line while not connected. */
    @StringRes val rowLine: Int,
    /** E2: where the key is and where it stays. */
    @StringRes val intro: Int,
    @StringRes val phoneTitle: Int,
    /** E4: the service refused the pasted key. */
    @StringRes val refused: Int,
    /** E3: the account panel's foot note (how to stop the key working anywhere). */
    @StringRes val note: Int,
    val phonePage: PasteKeyPageStrings,
    val phonePagePort: Int
)

/** The key words of a tracker that connects with a pasted key ([com.nuvio.tv.core.tracking.TrackingProvider.keyAuth]). */
internal fun trackerKeyText(id: TrackingProviderId): TrackerKeyText? = when (id) {
    TrackingProviderId.MDBLIST -> MDBLIST_KEY_TEXT
    TrackingProviderId.SIMKL -> null
}

private val MDBLIST_KEY_TEXT = TrackerKeyText(
    rowLine = R.string.mdblist_key_row_line,
    intro = R.string.mdblist_key_intro,
    phoneTitle = R.string.mdblist_key_phone_title,
    refused = R.string.mdblist_key_refused,
    note = R.string.mdblist_key_note,
    phonePage = PasteKeyPageStrings(
        title = R.string.mdblist_key_web_title,
        intro = R.string.mdblist_key_web_intro,
        placeholder = R.string.mdblist_key_web_placeholder,
        hint = R.string.mdblist_key_web_hint,
        hintBold = null,
        savedBody = R.string.mdblist_key_web_saved_body,
        rejectedTitle = R.string.mdblist_key_web_rejected_title,
        rejectedBody = R.string.mdblist_key_web_rejected_body,
        unreachable = R.string.mdblist_key_web_unreachable,
        maxLength = 128
    ),
    phonePagePort = 8190
)

/** [state]'s open key panel, if any (Trackers, and Integrations' "Ratings from", Spec 105 D24). */
@Composable
internal fun TrackerKeyPanelHost(state: TrackersUiState, onDismiss: () -> Unit) {
    val panel = state.keyPanel ?: return
    val row = state.rows.firstOrNull { it.id == panel.providerId }
    val text = row?.keyText ?: return
    TrackerKeyPanel(name = row.name, text = text, phoneUrl = panel.phoneUrl, lastCheck = panel.lastCheck, onDismiss = onDismiss)
}

/**
 * E2/E4: connect a tracker with the profile's own key from the phone. The intro (E2), or in amber what the last send
 * came to (E4 refused; unreachable), then the phone page's QR and Done (focused). A key the service accepts closes the
 * panel (the hub's Connected toast follows). [phoneUrl] null = no QR (no network, no free port, the page expired).
 */
@Composable
internal fun TrackerKeyPanel(
    name: String,
    text: TrackerKeyText,
    phoneUrl: String?,
    lastCheck: PasteCheck?,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val doneFocus = remember { FocusRequester() }
    val failed = lastCheck == PasteCheck.Refused || lastCheck == PasteCheck.Unreachable
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = doneFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.trackers_signin_title, name))
        Text(
            text = when (lastCheck) {
                PasteCheck.Refused -> stringResource(text.refused)
                PasteCheck.Unreachable -> stringResource(R.string.trackers_signin_unreachable, name)
                else -> stringResource(text.intro)
            },
            style = NuvioTheme.textStyles.settingsNote,
            color = if (failed) NuvioPrimitives.warning else marquee.onImage2,
            modifier = Modifier.padding(horizontal = marquee.settings.panelTextInset).padding(top = 2.dp)
        )
        phoneUrl?.let { url ->
            SettingsPhoneQrBlock(
                url = url,
                divider = stringResource(if (failed) R.string.trackers_key_retry_divider else R.string.trackers_setup_phone_divider),
                title = stringResource(text.phoneTitle)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        MarqueePanelActions {
            MarqueePrimaryButton(
                label = stringResource(R.string.trackers_setup_done),
                icon = null,
                onClick = onDismiss,
                focusRequester = doneFocus,
                compact = true
            )
        }
    }
}
