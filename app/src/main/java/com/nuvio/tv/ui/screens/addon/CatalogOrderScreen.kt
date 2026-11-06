package com.nuvio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeSwitchRow
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.ServePendingFocus
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** Focus target of the Follow addons order row (not a catalog key). */
private const val FOLLOW_TARGET = "#follow"

/** Longest custom catalog title the rename panel takes. */
private const val CATALOG_TITLE_MAX = 40

/**
 * Spec 31, Home catalog order route (sandbox u11-settings-marquee v6, stage `catalog-order`; Q9):
 * breadcrumb, header, the Follow addons order switch row, then "Rows · N": one row per Home row
 * ("Name - Type", the addon or "Collection · N folders", renamed / hidden notes; hidden rows dim).
 * OK on a row opens Move up / down (dimmed at the ends and, in follow mode, on addon catalogs:
 * focusable no-ops, so focus stays on the moved row), Rename (addon catalogs only; Home shows
 * custom titles on those alone) and Hide / Show on Home. Moves save at once; the moved row is
 * brought into view.
 */
@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val items = uiState.items
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    val focusTargets = rememberCollectionFocusTargets()
    var renaming by remember { mutableStateOf<CatalogOrderItem?>(null) }

    BackHandler { onBackPress() }

    // Entry focus: the Follow row, static across the catalog load.
    LaunchedEffect(Unit) { focusTargets.pending = FOLLOW_TARGET }
    focusTargets.ServePendingFocus(content = items, fallback = FOLLOW_TARGET)

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.listTop)
                .verticalScroll(rememberScrollState())
                .padding(start = safeHorizontal, end = safeHorizontal, bottom = NuvioTheme.layout.tvSafeVertical),
            verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.settings_content_catalog_order),
                    style = NuvioTheme.textStyles.settingsTitle,
                    color = NuvioPrimitives.white
                )
                Text(
                    text = stringResource(R.string.settings_content_catalog_order_desc),
                    style = NuvioTheme.textStyles.meta,
                    color = marquee.onImage3,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
                )
            }
            MarqueeSwitchRow(
                label = stringResource(R.string.catalog_order_follow_addons),
                helper = stringResource(R.string.catalog_order_follow_hint),
                checked = uiState.followAddonsOrder,
                onCheckedChange = viewModel::toggleFollowAddonsOrder,
                modifier = Modifier.focusRequester(focusTargets.requester(FOLLOW_TARGET))
            )
            if (!uiState.isLoading) {
                Text(
                    text = stringResource(R.string.catalog_order_rows, items.size).uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = marquee.onImage3,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
                )
                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.catalog_order_empty),
                        style = NuvioTheme.textStyles.settingsNote,
                        color = marquee.onImage2
                    )
                }
            }
            items.forEachIndexed { index, item ->
                // Keyed so a moved row keeps its composition, and with it the focused action.
                key(item.key) {
                    CatalogOrderRow(
                        item = item,
                        position = index,
                        focusRequester = focusTargets.requester(item.key)
                    ) {
                        CollectionRowAction(
                            label = stringResource(R.string.cd_move_up),
                            onClick = { viewModel.moveUp(item.key) },
                            dimmed = !item.canMoveUp
                        )
                        CollectionRowAction(
                            label = stringResource(R.string.cd_move_down),
                            onClick = { viewModel.moveDown(item.key) },
                            dimmed = !item.canMoveDown
                        )
                        if (!item.isCollection) {
                            CollectionRowAction(
                                label = stringResource(R.string.addon_action_rename),
                                onClick = { renaming = item }
                            )
                        }
                        CollectionRowAction(
                            label = stringResource(
                                if (item.isDisabled) R.string.catalog_order_show else R.string.catalog_order_hide
                            ),
                            onClick = { viewModel.toggleCatalogEnabled(item.disableKey) }
                        )
                    }
                }
            }
        }

        MarqueeBreadcrumb(listOf(stringResource(R.string.nav_settings), stringResource(R.string.settings_content_catalog_order)))
    }

    renaming?.let { item ->
        val close = {
            renaming = null
            focusTargets.pending = item.key
        }
        RenamePanel(
            eyebrow = stringResource(R.string.catalog_order_rename_eyebrow),
            current = item.catalogName,
            originalName = item.originalName,
            resetLabel = stringResource(R.string.catalog_order_rename_reset),
            note = stringResource(R.string.catalog_order_rename_note),
            maxLength = CATALOG_TITLE_MAX,
            onSave = { title ->
                viewModel.renameCatalog(item.key, title)
                close()
            },
            onDismiss = close
        )
    }
}

/** Stage 15 row: "Name - Type" over its source line, the list icon at the end. */
@Composable
private fun CatalogOrderRow(
    item: CatalogOrderItem,
    position: Int,
    focusRequester: FocusRequester,
    actions: @Composable RowScope.() -> Unit
) {
    val marquee = NuvioTheme.marquee
    StripListRow(
        focusRequester = focusRequester,
        position = position,
        dimmedAtRest = item.isDisabled,
        actions = actions
    ) { focusWithin ->
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.catalog_order_row_title, item.catalogName, item.typeLabel.toDisplayTypeLabel()),
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = catalogOrderSourceLine(item, showPinReason = focusWithin),
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.List)
    }
}

/**
 * "Cinemeta · renamed from "Top"", "Collection · 3 folders", "Kitsu · hidden on Home"; while
 * focused in follow mode an addon catalog adds why its moves are dimmed.
 */
@Composable
private fun catalogOrderSourceLine(item: CatalogOrderItem, showPinReason: Boolean): String {
    val source = item.folderCount?.let { count ->
        stringResource(
            R.string.catalog_order_collection,
            pluralStringResource(R.plurals.collections_row_folders, count, count)
        )
    } ?: item.addonName
    return listOfNotNull(
        source,
        if (item.isRenamed) stringResource(R.string.catalog_order_renamed_from, item.originalName) else null,
        if (item.isDisabled) stringResource(R.string.catalog_order_hidden) else null,
        if (showPinReason && item.pinnedByFollow) stringResource(R.string.catalog_order_follows_addon) else null
    ).joinToString(" \u00B7 ")
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
