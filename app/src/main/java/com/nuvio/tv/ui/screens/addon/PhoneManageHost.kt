package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Manage from phone (Spec 31, Q6): the phone-QR session's overlays, shared by the Content &
 * Discovery pane and the Addon Manager. While [viewModel] serves the phone page it shows the QR,
 * with the pending-changes confirm on top; the server stops when the host leaves composition.
 * Start a session with [AddonManagerViewModel.startQrMode]; a failed start arrives as a transient
 * error message, which the caller shows.
 */
@Composable
internal fun PhoneManageHost(viewModel: AddonManagerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        onDispose { viewModel.stopQrMode() }
    }

    if (uiState.isQrModeActive) {
        val instruction = stringResource(
            if (viewModel.webConfigMode == AddonWebConfigMode.COLLECTIONS_ONLY) {
                R.string.addon_qr_collections_scan_instruction
            } else {
                R.string.addon_qr_scan_instruction
            }
        )
        Popup(popupPositionProvider = WindowTopLeft, properties = PopupProperties(focusable = true)) {
            QrCodeOverlay(
                qrBitmap = uiState.qrCodeBitmap,
                serverUrl = uiState.serverUrl,
                instruction = instruction,
                onClose = viewModel::stopQrMode,
                hasPendingChange = uiState.pendingChange != null
            )
        }
    }

    uiState.pendingChange?.let { pending ->
        Popup(popupPositionProvider = WindowTopLeft, properties = PopupProperties(focusable = true)) {
            ConfirmAddonChangesDialog(
                pendingChange = pending,
                onConfirm = viewModel::confirmPendingChange,
                onReject = viewModel::rejectPendingChange
            )
        }
    }
}

/**
 * The overlays fill the screen, so they anchor at the window's corner, not the host's (a Settings pane
 * sits inset). Also the profile editor's Add from phone cards (Spec 44).
 */
internal object WindowTopLeft : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    instruction: String,
    onClose: () -> Unit,
    hasPendingChange: Boolean = false,
    qrSize: Dp = 220.dp
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(hasPendingChange) {
        if (!hasPendingChange) {
            focusRequester.requestFocus()
        }
    }

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(qrSize),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

            if (serverUrl != null) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

            Surface(
                onClick = onClose,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioTheme.colors.Surface,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = NuvioTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.addon_qr_close),
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ConfirmAddonChangesDialog(
    pendingChange: PendingChangeInfo,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BackHandler { onReject() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = { },
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 640.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl))
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.addon_confirm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Text(
                    text = stringResource(R.string.addon_confirm_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(
                            color = NuvioTheme.colors.Surface,
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(NuvioTheme.spacing.md)
                            .verticalScroll(scrollState)
                    ) {
                        if (pendingChange.addedUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_added),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Success,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.addedUrls.forEach { url ->
                                val displayName = pendingChange.addedNames[url] ?: url
                                Text(
                                    text = "+ $displayName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Success,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.removedUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_removed),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.removedUrls.forEach { url ->
                                val displayName = pendingChange.removedNames[url] ?: url
                                Text(
                                    text = "- $displayName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.catalogsReordered) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalog_reordered),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }

                        if (pendingChange.disabledCatalogNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalogs_disabled),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.disabledCatalogNames.forEach { name ->
                                Text(
                                    text = "- $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.enabledCatalogNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalogs_enabled),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Success,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.enabledCatalogNames.forEach { name ->
                                Text(
                                    text = "+ $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Success,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.collectionsChanged) {
                            Text(
                                text = stringResource(R.string.addon_pending_collections_updated),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            Text(
                                text = stringResource(R.string.addon_pending_collections_replace, pendingChange.proposedCollectionCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (
                            pendingChange.addedUrls.isEmpty() &&
                            pendingChange.removedUrls.isEmpty() &&
                            !pendingChange.catalogsReordered &&
                            pendingChange.disabledCatalogNames.isEmpty() &&
                            pendingChange.enabledCatalogNames.isEmpty() &&
                            !pendingChange.collectionsChanged
                        ) {
                            Text(
                                text = stringResource(R.string.addon_confirm_no_changes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.addon_confirm_total_addons, pendingChange.proposedUrls.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.addon_confirm_total_catalogs, pendingChange.proposedCatalogOrderKeys.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                if (pendingChange.isApplying) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    ) {
                        Surface(
                            onClick = onReject,
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioTheme.colors.Surface,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = NuvioTheme.colors.TextPrimary
                                )
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                                Text(
                                    text = stringResource(R.string.addon_confirm_reject),
                                    color = NuvioTheme.colors.TextPrimary
                                )
                            }
                        }

                        Surface(
                            onClick = onConfirm,
                            modifier = Modifier.focusRequester(focusRequester),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioTheme.colors.Secondary,
                                focusedContainerColor = NuvioTheme.colors.SecondaryVariant
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = stringResource(R.string.addon_confirm_confirm),
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md),
                                color = NuvioTheme.colors.OnSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
