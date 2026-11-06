package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.screens.addon.AddonManagerViewModel
import com.nuvio.tv.ui.screens.addon.PhoneManageHost
import com.nuvio.tv.ui.util.requestFocusAfterFrames

private const val ROW_ADDONS = "content_addons"
private const val ROW_CATALOG_ORDER = "content_catalog_order"
private const val ROW_COLLECTIONS = "content_collections"
private const val ROW_PHONE = "content_phone"

/**
 * Spec 31, Content & Discovery pane (sandbox u11-settings-marquee v6, stage `content`, Q6): Addons,
 * Home catalog order, Collections (sub-routes) and Manage from phone (the phone QR, hosted here
 * through [PhoneManageHost]). Rows wait for the counts (see [ContentDiscoveryViewModel]).
 */
@Composable
internal fun ContentDiscoverySettingsContent(
    onNavigateToAddons: () -> Unit,
    onNavigateToCatalogOrder: () -> Unit,
    onNavigateToCollections: () -> Unit,
    initialFocusRequester: FocusRequester?,
    viewModel: ContentDiscoveryViewModel = hiltViewModel(),
    phoneViewModel: AddonManagerViewModel = hiltViewModel()
) {
    val addonsRow = rememberSettingsReturnTarget(ROW_ADDONS)
    val catalogOrderRow = rememberSettingsReturnTarget(ROW_CATALOG_ORDER)
    val collectionsRow = rememberSettingsReturnTarget(ROW_COLLECTIONS)
    val phoneRow = remember { FocusRequester() }
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val phoneState by phoneViewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalMarqueeToast.current

    PhoneManageHost(phoneViewModel)

    // A failed QR start (no network, no free port) is the only message this session sends.
    LaunchedEffect(phoneState.transientMessage) {
        val message = phoneState.transientMessage ?: return@LaunchedEffect
        toast?.show(message)
        phoneViewModel.clearTransientMessage()
    }
    // Closing the QR hands focus back to the row that opened it (the overlay is its own window).
    var phoneOpen by remember { mutableStateOf(false) }
    LaunchedEffect(phoneState.isQrModeActive) {
        if (phoneState.isQrModeActive) {
            phoneOpen = true
        } else if (phoneOpen) {
            phoneOpen = false
            phoneRow.requestFocusAfterFrames()
        }
    }

    SettingsPane(
        title = stringResource(R.string.settings_content_discovery),
        subtitle = stringResource(R.string.content_pane_subtitle)
    ) {
        if (state == null) return@SettingsPane
        item(key = ROW_ADDONS) {
            SettingsValueRow(
                title = stringResource(R.string.addon_title),
                subtitle = stringResource(R.string.content_addons_subtitle),
                value = pluralStringResource(R.plurals.content_addons_installed, state.addonCount, state.addonCount),
                onClick = { addonsRow.launch(onNavigateToAddons) },
                modifier = Modifier
                    .focusRequester(addonsRow.requester)
                    .optionalFocusRequester(initialFocusRequester)
            )
        }
        if (state.showCatalogOrder) {
            item(key = ROW_CATALOG_ORDER) {
                SettingsValueRow(
                    title = stringResource(R.string.settings_content_catalog_order),
                    subtitle = stringResource(R.string.settings_content_catalog_order_desc),
                    value = pluralStringResource(R.plurals.content_catalog_rows, state.homeCatalogRows, state.homeCatalogRows),
                    onClick = { catalogOrderRow.launch(onNavigateToCatalogOrder) },
                    modifier = Modifier.focusRequester(catalogOrderRow.requester)
                )
            }
        }
        item(key = ROW_COLLECTIONS) {
            SettingsValueRow(
                title = stringResource(R.string.settings_content_collections),
                subtitle = stringResource(R.string.settings_content_collections_desc),
                value = pluralStringResource(
                    R.plurals.collections_import_collections,
                    state.collectionCount,
                    state.collectionCount
                ),
                onClick = { collectionsRow.launch(onNavigateToCollections) },
                modifier = Modifier.focusRequester(collectionsRow.requester)
            )
        }
        item(key = ROW_PHONE) {
            SettingsValueRow(
                title = stringResource(R.string.addon_manage_from_phone_title),
                subtitle = stringResource(
                    if (state.readOnly) R.string.content_phone_collections_subtitle else R.string.content_phone_subtitle
                ),
                value = null,
                trailingIcon = Icons.Default.QrCode2,
                onClick = { phoneViewModel.startQrMode() },
                modifier = Modifier.focusRequester(phoneRow)
            )
        }
    }
}
