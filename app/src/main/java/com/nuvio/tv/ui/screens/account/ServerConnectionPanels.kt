package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.screens.collection.CollectionFieldPill
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsPickerPanel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** The QR route's `.l8top` Server pill ("Server · Official"): opens the same [ServerPickerHost] as the Account pane's Server row. */
@Composable
internal fun ServerPill(
    viewModel: ServerConnectionViewModel,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val isCustom = viewModel.uiState.collectAsState().value.activeServer.isCustom
    CollectionsHeaderButton(
        label = stringResource(if (isCustom) R.string.auth_server_pill_custom else R.string.auth_server_pill_official),
        onClick = { showPicker = true },
        modifier = modifier
    )
    ServerPickerHost(viewModel = viewModel, showPicker = showPicker, onPickerDismiss = { showPicker = false })
}

/**
 * The Server picker (Official / Custom) while [showPicker], then the flow the pick starts:
 * Custom always opens the address panel (to change servers too); Official asks first when a
 * custom server is active and does nothing otherwise.
 */
@Composable
internal fun ServerPickerHost(
    viewModel: ServerConnectionViewModel,
    showPicker: Boolean,
    onPickerDismiss: () -> Unit
) {
    val isCustom = viewModel.uiState.collectAsState().value.activeServer.isCustom
    var flow by remember { mutableStateOf<ServerFlow?>(null) }
    if (showPicker) {
        SettingsPickerPanel(
            title = stringResource(R.string.account_server_title),
            caption = stringResource(R.string.account_server_picker_caption),
            options = listOf(
                SettingsPickerOption(
                    value = false,
                    title = stringResource(R.string.account_server_option_official),
                    description = stringResource(R.string.account_server_option_official_desc)
                ),
                SettingsPickerOption(
                    value = true,
                    title = stringResource(R.string.account_server_option_custom),
                    description = stringResource(R.string.account_server_option_custom_desc)
                )
            ),
            selectedValue = isCustom,
            onSelect = { custom ->
                flow = when {
                    custom -> ServerFlow.CONNECT
                    isCustom -> ServerFlow.OFFICIAL
                    else -> null
                }
            },
            onDismiss = onPickerDismiss
        )
    }
    ServerFlowPanels(viewModel = viewModel, flow = flow, onClose = { flow = null })
}

/** The two server switches: connect to a custom server (address, then trust review) or go back to the official one. */
private enum class ServerFlow { CONNECT, OFFICIAL }

/**
 * Shows [flow]'s panels (none when null); [onClose] runs when the user backs out. Every exit
 * resets the discovery state, so the next flow starts clean; nothing closes while a switch runs;
 * a successful switch restarts the app.
 */
@Composable
private fun ServerFlowPanels(
    viewModel: ServerConnectionViewModel,
    flow: ServerFlow?,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val dismiss = {
        if (!uiState.isSwitching) {
            viewModel.resetDiscovery()
            onClose()
        }
    }
    when (flow) {
        ServerFlow.CONNECT -> {
            val discovered = uiState.discoveredServer
            if (discovered == null) {
                ServerAddressPanel(uiState = uiState, onDiscover = viewModel::discover, onDismiss = dismiss)
            } else {
                ServerTrustPanel(
                    server = discovered,
                    isSwitching = uiState.isSwitching,
                    error = uiState.error,
                    onConnect = viewModel::connectDiscoveredServer,
                    onDismiss = dismiss
                )
            }
        }
        ServerFlow.OFFICIAL -> MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.official_server_title),
            body = listOfNotNull(stringResource(R.string.official_server_description), uiState.error)
                .joinToString("\n\n"),
            confirmLabel = stringResource(
                if (uiState.isSwitching) R.string.custom_server_switching else R.string.official_server_action
            ),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = viewModel::useOfficialServer,
            onDismiss = dismiss
        )
        null -> Unit
    }
}

/** Connect to custom server: the address field (IME Done checks) + Check server, the error, Cancel. */
@Composable
private fun ServerAddressPanel(
    uiState: ServerConnectionUiState,
    onDiscover: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val collections = NuvioTheme.marquee.collections
    var serverUrl by rememberSaveable {
        mutableStateOf(uiState.activeServer.discoveryUrl?.substringBefore("/.well-known/nuvio").orEmpty())
    }
    val fieldFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = collections.panelVeil,
        width = collections.importWidth,
        initialFocus = fieldFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.custom_server_title))
        Column(verticalArrangement = Arrangement.spacedBy(collections.panelGap)) {
            PanelBodyText(stringResource(R.string.custom_server_description))
            CollectionFieldPill(
                icon = Icons.Default.Dns,
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = stringResource(R.string.custom_server_url_placeholder),
                actionLabel = stringResource(
                    if (uiState.isDiscovering) R.string.custom_server_checking else R.string.custom_server_check
                ),
                onAction = { if (serverUrl.isNotBlank()) onDiscover(serverUrl) },
                actionFocus = checkFocus,
                fieldFocus = fieldFocus,
                keyboardType = KeyboardType.Uri
            )
            uiState.error?.let { PanelErrorText(it) }
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
        }
    }
}

/** Review custom server: what was discovered, the trust warning, the error; Cancel focused, then I trust this server. */
@Composable
private fun ServerTrustPanel(
    server: ServerConfiguration,
    isSwitching: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val collections = NuvioTheme.marquee.collections
    val cancelFocus = remember { FocusRequester() }
    val warning = when {
        !server.isSecure && server.isPublicHost -> R.string.custom_server_warning_public_http
        !server.isSecure -> R.string.custom_server_warning_http
        server.isPublicHost -> R.string.custom_server_warning_public
        else -> R.string.custom_server_warning_private
    }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = collections.panelVeil,
        width = collections.importWidth,
        initialFocus = cancelFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.custom_server_review_title))
        Column(verticalArrangement = Arrangement.spacedBy(collections.panelGap)) {
            PanelBodyText(stringResource(R.string.custom_server_review_description))
            PanelDetail(label = stringResource(R.string.custom_server_verified_label), value = server.backendUrl)
            PanelDetail(
                label = stringResource(R.string.custom_server_key_label),
                value = stringResource(R.string.custom_server_key_discovered)
            )
            Column(modifier = Modifier.padding(horizontal = NuvioTheme.marquee.stream.dialogTextInset)) {
                Text(
                    text = stringResource(R.string.custom_server_warning_title),
                    style = NuvioTheme.textStyles.panelPill,
                    color = NuvioPrimitives.warning
                )
                Text(
                    text = stringResource(warning) + " " + stringResource(R.string.custom_server_warning_credentials),
                    style = NuvioTheme.textStyles.panelBody,
                    color = NuvioTheme.marquee.stream.dialogBody
                )
            }
            error?.let { PanelErrorText(it) }
        }
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus)
            )
            MarqueeGlassTextButton(
                label = stringResource(
                    if (isSwitching) R.string.custom_server_switching else R.string.custom_server_trust_action
                ),
                onClick = onConnect
            )
        }
    }
}

@Composable
private fun PanelBodyText(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.panelBody,
        color = NuvioTheme.marquee.stream.dialogBody,
        modifier = Modifier.padding(horizontal = NuvioTheme.marquee.stream.dialogTextInset)
    )
}

@Composable
private fun PanelErrorText(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.panelPill,
        color = NuvioPrimitives.errorOnImage,
        modifier = Modifier.padding(horizontal = NuvioTheme.marquee.stream.dialogTextInset)
    )
}

/** A label over its value (what discovery found). */
@Composable
private fun PanelDetail(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = NuvioTheme.marquee.stream.dialogTextInset)) {
        Text(text = label, style = NuvioTheme.textStyles.meta, color = NuvioTheme.marquee.onImage3)
        Text(
            text = value,
            style = NuvioTheme.textStyles.panelBody,
            color = NuvioPrimitives.white,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
