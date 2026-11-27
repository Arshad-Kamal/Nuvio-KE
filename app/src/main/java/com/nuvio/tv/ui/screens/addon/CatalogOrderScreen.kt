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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeSwitchRow
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.ServePendingFocus
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.settings.trackers.TrackerLogo
import com.nuvio.tv.ui.screens.settings.trackers.TrackerLogoSize
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** Focus target of the Follow addons order row (not a catalog key). */
private const val FOLLOW_TARGET = "#follow"

/** Longest custom catalog title the rename panel takes. */
private const val CATALOG_TITLE_MAX = 40

/** MDBList's name (a brand, untranslated), its tag at an MDBList row's end (Spec 105 mockup v3 D2 `.s105tag`). */
private const val MDBLIST_TAG = "MDBLIST"

/** D2's extra space after the glyph and before the list icon (`.s104g` / `.s105tag` margins), on top of the row gap. */
private val MDBLIST_GLYPH_END = 12.dp
private val MDBLIST_TAG_END = 14.dp

/**
 * Spec 31, Home catalog order route (sandbox u11-settings-marquee v6, stage `catalog-order`; Q9):
 * breadcrumb, header, the Follow addons order switch row, then "Rows · N": one row per Home row
 * ("Name - Type", the addon or "Collection · N folders", renamed / hidden notes; hidden rows dim).
 * MDBList rows (Spec 105 S5b2, mockup v3 D2) lead with the MDBList glyph, show the name alone and
 * carry an MDBLIST tag. OK on a row opens Move up / down (dimmed at the ends and, in follow mode, on
 * addon catalogs: focusable no-ops, so focus stays on the moved row), Rename (addon catalogs and
 * MDBList rows; Home shows custom titles on those alone) and Hide / Show on Home. Moves save at
 * once; the moved row is brought into view.
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
            // D34: an MDBList row's name stays on this TV (never in the synced payload).
            note = stringResource(
                if (item.isMdbList) R.string.catalog_order_rename_note_mdblist else R.string.catalog_order_rename_note
            ),
            maxLength = CATALOG_TITLE_MAX,
            onSave = { title ->
                viewModel.renameCatalog(item.key, title)
                close()
            },
            onDismiss = close
        )
    }
}

/** Stage 15 row: "Name - Type" over its source line, the list icon at the end; an MDBList row per D2. */
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
        if (item.isMdbList) {
            TrackerLogo(
                logoRes = R.drawable.ic_tracker_mdblist,
                name = MDBLIST_TAG,
                size = TrackerLogoSize.LIST,
                modifier = Modifier.padding(end = MDBLIST_GLYPH_END)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (item.isMdbList) {
                    item.catalogName
                } else {
                    stringResource(R.string.catalog_order_row_title, item.catalogName, item.typeLabel.toDisplayTypeLabel())
                },
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
        if (item.isMdbList) {
            Text(
                text = MDBLIST_TAG,
                style = NuvioTheme.textStyles.eyebrow.copy(letterSpacing = 0.6.sp),
                color = marquee.onImage3,
                modifier = Modifier.padding(end = MDBLIST_TAG_END)
            )
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.List)
    }
}

/**
 * "Cinemeta · renamed from "Top"", "Collection · 3 folders", "Kitsu · hidden on Home"; while
 * focused in follow mode an addon catalog adds why its moves are dimmed. An MDBList row (D2,
 * D30-D32): "Your list · 41 titles", hidden "Dynamic list · hidden on Home" (no count).
 */
@Composable
private fun catalogOrderSourceLine(item: CatalogOrderItem, showPinReason: Boolean): String {
    val mdbList = item.mdbList
    val source = when {
        item.folderCount != null -> stringResource(
            R.string.catalog_order_collection,
            pluralStringResource(R.plurals.collections_row_folders, item.folderCount, item.folderCount)
        )
        mdbList != null -> listOfNotNull(
            stringResource(mdbList.kind.label),
            mdbList.itemCount?.takeUnless { item.isDisabled }?.let { pluralStringResource(R.plurals.detail_mdblist_titles, it, it) }
        ).joinToString(" \u00B7 ")
        else -> item.addonName
    }
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

private val MdbListRowKind.label: Int
    get() = when (this) {
        MdbListRowKind.WATCHLIST -> R.string.catalog_order_mdblist_watchlist
        MdbListRowKind.OWN -> R.string.catalog_order_mdblist_own
        MdbListRowKind.DYNAMIC -> R.string.catalog_order_mdblist_dynamic
        MdbListRowKind.LIKED -> R.string.catalog_order_mdblist_liked
        MdbListRowKind.IMPORTED -> R.string.catalog_order_mdblist_imported
    }
