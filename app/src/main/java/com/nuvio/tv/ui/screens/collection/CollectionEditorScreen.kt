package com.nuvio.tv.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeSwitchRow
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PanelSegments
import com.nuvio.tv.ui.components.collectionFolderCardImageUrl
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.home.buildCollectionFolderItem
import com.nuvio.tv.ui.screens.home.marqueeImageUrl
import com.nuvio.tv.ui.screens.home.usesLandscapeCards
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Stage 10 collection editor: the form rows on the left, the Home preview and the folders on the
 * right, Cancel / Save top-right. Back (and Cancel) asks before dropping unsaved edits (Q4). The
 * folder editor (stage 11) replaces the form while open; the form's entry focus runs again on return.
 */
@Composable
fun CollectionEditorScreen(
    viewModel: CollectionEditorViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Where focus lands when the form (re)appears: a folder id, ADD_FOLDER_TARGET, else Title.
    var restoreTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // Folders by id, ADD_FOLDER_TARGET and TITLE_TARGET.
    val focusTargets = rememberCollectionFocusTargets()

    var folderToDelete by remember { mutableStateOf<CollectionFolder?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (uiState.showFolderEditor) {
        FolderEditorContent(
            viewModel = viewModel,
            uiState = uiState
        )
        return
    }

    val leave: () -> Unit = {
        when {
            viewModel.isSaving -> Unit
            viewModel.hasUnsavedCollectionChanges() -> confirmDiscard = true
            else -> onBack()
        }
    }
    BackHandler(onBack = leave)

    // Every entry to the form: first open (Title) and back from the folder editor.
    LaunchedEffect(Unit) {
        focusTargets.pending = restoreTarget ?: TITLE_TARGET
    }
    focusTargets.ServePendingFocus(content = uiState.folders, fallback = TITLE_TARGET)

    CollectionEditorForm(
        uiState = uiState,
        viewModel = viewModel,
        requester = focusTargets::requester,
        onOpenFolder = { id ->
            restoreTarget = id
            viewModel.editFolder(id)
        },
        onAddFolder = {
            restoreTarget = ADD_FOLDER_TARGET
            viewModel.addFolder()
        },
        onDeleteFolder = { folderToDelete = it },
        onCancel = leave,
        onSave = { viewModel.save { onBack() } }
    )

    folderToDelete?.let { folder ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.collections_editor_delete_folder_eyebrow),
            body = stringResource(R.string.collections_editor_delete_folder_subtitle, folder.title),
            confirmLabel = stringResource(R.string.cd_delete),
            cancelLabel = stringResource(R.string.collections_cancel),
            onConfirm = {
                val folders = uiState.folders
                val index = folders.indexOfFirst { it.id == folder.id }
                // Focus moves to the folder that takes its place, else the one above, else Add folder.
                focusTargets.pending = (folders.getOrNull(index + 1) ?: folders.getOrNull(index - 1))?.id
                    ?: ADD_FOLDER_TARGET
                viewModel.removeFolder(folder.id)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null },
            destructive = true
        )
    }

    if (confirmDiscard) {
        DiscardChangesPanel(
            body = stringResource(R.string.collections_discard_collection_body),
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onBack()
            }
        )
    }
}

// Focus targets beside folder ids (those come from CollectionsDataStore.generateId).
private const val TITLE_TARGET = "#title"
private const val ADD_FOLDER_TARGET = "#add_folder"

@Composable
private fun CollectionEditorForm(
    uiState: CollectionEditorUiState,
    viewModel: CollectionEditorViewModel,
    requester: (String) -> FocusRequester,
    onOpenFolder: (String) -> Unit,
    onAddFolder: () -> Unit,
    onDeleteFolder: (CollectionFolder) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    CollectionEditorFrame(
        backdropUrl = uiState.backdropImageUrl,
        crumbs = listOf(
            stringResource(R.string.collections_header),
            uiState.title.ifBlank { stringResource(R.string.collections_new) }
        ),
        // As built: Save needs a title and a folder.
        canSave = uiState.title.isNotBlank() && uiState.folders.isNotEmpty(),
        onCancel = onCancel,
        onSave = onSave,
        form = { CollectionFormFields(uiState = uiState, viewModel = viewModel, titleFocus = requester(TITLE_TARGET)) },
        side = {
            EditorSectionLabel(stringResource(R.string.collections_editor_preview), first = true)
            CollectionHomePreview(uiState)
            EditorSectionLabel(stringResource(R.string.collections_editor_folders_count, uiState.folders.size))
            uiState.folders.forEach { folder ->
                // Keyed so a moved row keeps its composition, and with it the focused action.
                key(folder.id) {
                    FolderRow(
                        folder = folder,
                        landscape = uiState.landscapePosters,
                        focusRequester = requester(folder.id),
                        onEdit = { onOpenFolder(folder.id) },
                        onMoveUp = { viewModel.moveFolderUp(folder.id) },
                        onMoveDown = { viewModel.moveFolderDown(folder.id) },
                        onDelete = { onDeleteFolder(folder) }
                    )
                }
            }
            EditorAddButton(
                label = stringResource(R.string.collections_editor_add_folder),
                onClick = onAddFolder,
                modifier = Modifier
                    .padding(top = NuvioTheme.marquee.collections.sectionBottomPadding)
                    .focusRequester(requester(ADD_FOLDER_TARGET))
            )
        }
    )
}

/**
 * Stages 10-11 editor frame: black + [backdropUrl] under the veil, the [crumbs] breadcrumb,
 * [EditorHeaderActions] Cancel + Save; [form] fills the left column (`editorFormWidth`), [side]
 * the right one; both scroll.
 */
@Composable
internal fun CollectionEditorFrame(
    backdropUrl: String,
    crumbs: List<String>,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    form: @Composable ColumnScope.() -> Unit,
    side: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        MarqueeBackdropImage(url = backdropUrl.ifBlank { null }, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(tokens.veil))
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.listTop, start = safeHorizontal, end = safeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(tokens.editorColumnGap)
        ) {
            Column(
                modifier = Modifier
                    .width(tokens.editorFormWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = NuvioTheme.layout.tvSafeVertical),
                verticalArrangement = Arrangement.spacedBy(tokens.rowGap),
                content = form
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = NuvioTheme.layout.tvSafeVertical),
                verticalArrangement = Arrangement.spacedBy(tokens.rowGap),
                content = side
            )
        }

        MarqueeBreadcrumb(crumbs)
        EditorHeaderActions(
            cancelLabel = stringResource(R.string.collections_cancel),
            saveLabel = stringResource(R.string.collections_editor_save),
            canSave = canSave,
            onCancel = onCancel,
            onSave = onSave,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

/**
 * `.l8top`: Cancel (glass) + the compact white [saveLabel], dimmed and skipped by focus until
 * [canSave]. Shared with the profile editor (u10 stages 7, 8), so a change here shows there too.
 */
@Composable
internal fun EditorHeaderActions(
    cancelLabel: String,
    saveLabel: String,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.collections
    Row(
        modifier = modifier.padding(top = tokens.headerTop, end = NuvioTheme.layout.tvSafeHorizontal),
        horizontalArrangement = Arrangement.spacedBy(tokens.headerButtonGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CollectionsHeaderButton(label = cancelLabel, onClick = onCancel)
        MarqueePrimaryButton(
            label = saveLabel,
            icon = null,
            onClick = { if (canSave) onSave() },
            modifier = Modifier
                .alpha(if (canSave) 1f else NuvioTheme.marquee.playerPanel.disabledAlpha)
                .focusProperties { canFocus = canSave },
            compact = true
        )
    }
}

/** `.l8add`: a compact glass pill with a plus (Add folder, Add catalog, Add TMDB source). */
@Composable
internal fun EditorAddButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MarqueeGlassTextButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        height = NuvioTheme.marquee.primaryButton.compactHeight,
        horizontalPadding = NuvioTheme.marquee.collections.addButtonPaddingHorizontal,
        textStyle = NuvioTheme.textStyles.panelButtonSmall,
        icon = Icons.Default.Add
    )
}

/** Title, Backdrop, Pin above catalogs, Focus glow, Folder view (Tabs | Rows, Q1), "All" tab (Tabs only). */
@Composable
private fun CollectionFormFields(
    uiState: CollectionEditorUiState,
    viewModel: CollectionEditorViewModel,
    titleFocus: FocusRequester
) {
    val tabs = uiState.viewMode.displayMode == FolderViewMode.TABBED_GRID
    CollectionTextRow(
        label = stringResource(R.string.collections_editor_title),
        value = uiState.title,
        onValueChange = viewModel::setTitle,
        display = uiState.title,
        placeholder = stringResource(R.string.collections_editor_placeholder_name),
        focusRequester = titleFocus
    )
    CollectionTextRow(
        label = stringResource(R.string.collections_editor_backdrop),
        value = uiState.backdropImageUrl,
        onValueChange = viewModel::setBackdropImageUrl,
        display = urlHostPath(uiState.backdropImageUrl),
        placeholder = stringResource(R.string.action_none),
        keyboardType = KeyboardType.Uri
    )
    MarqueeSwitchRow(
        label = stringResource(R.string.collections_editor_pin_above),
        helper = stringResource(R.string.collections_editor_pin_above_helper),
        checked = uiState.pinToTop,
        onCheckedChange = viewModel::setPinToTop
    )
    MarqueeSwitchRow(
        label = stringResource(R.string.collections_editor_focus_glow),
        checked = uiState.focusGlowEnabled,
        onCheckedChange = viewModel::setFocusGlowEnabled
    )
    MarqueeFormRow(label = stringResource(R.string.collections_editor_folder_view)) {
        PanelSegments(
            options = listOf(
                stringResource(R.string.collections_editor_view_mode_tabs),
                stringResource(R.string.collections_editor_view_mode_rows)
            ),
            selectedIndex = if (tabs) 0 else 1,
            onSelect = { index ->
                viewModel.setViewMode(if (index == 0) FolderViewMode.TABBED_GRID else FolderViewMode.ROWS)
            }
        )
    }
    if (tabs) {
        MarqueeSwitchRow(
            label = stringResource(R.string.collections_editor_show_all_tab),
            helper = stringResource(R.string.collections_editor_show_all_tab_desc),
            checked = uiState.showAllTab,
            onCheckedChange = viewModel::setShowAllTab
        )
    }
}

/** `.l8sec`: an uppercase section label, padded above unless [first]. */
@Composable
internal fun EditorSectionLabel(text: String, first: Boolean = false) {
    val tokens = NuvioTheme.marquee.collections
    PanelEyebrowText(
        text = text,
        modifier = Modifier.padding(
            top = if (first) NuvioTheme.spacing.none else tokens.sectionTopPadding,
            bottom = tokens.sectionBottomPadding
        )
    )
}

/**
 * "Preview · on Home": the collection's row as Home draws it, in small: its backdrop under the
 * hero's left scrim, the title (+ PINNED), then one mini tile per folder with the art Home's
 * folder card picks (the same item model and image rule), each in its own Tile shape, or
 * landscape under the Card shape setting (Spec 41 D5).
 */
@Composable
private fun CollectionHomePreview(uiState: CollectionEditorUiState) {
    val tokens = NuvioTheme.marquee.collections
    val collection = uiState.draftCollection()
    val tiles = remember(collection) {
        collection.folders.take(PREVIEW_TILE_LIMIT).map { it to buildCollectionFolderItem(collection, it) }
    }
    val home = NuvioTheme.marquee.home
    // Home's row rule: landscape under the setting or when every folder is Wide.
    val landscapeRow = remember(tiles, uiState.landscapePosters) {
        tiles.map { it.second }.usesLandscapeCards(uiState.landscapePosters)
    }
    CollectionPreviewCard(height = tokens.previewHeight, backdropUrl = collection.backdropImageUrl) {
        Row(
            modifier = Modifier.padding(start = tokens.previewInset, top = tokens.previewInset),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = collection.title.ifBlank { stringResource(R.string.collections_new) },
                style = NuvioTheme.textStyles.cardLabel,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (collection.pinToTop) {
                CollectionBadge(text = stringResource(R.string.collections_row_pinned), color = NuvioPrimitives.white)
            }
        }
        Row(
            modifier = Modifier
                .padding(start = tokens.previewInset, top = tokens.previewTilesTop)
                // Unbounded width: tiles past the card's edge are clipped, never squeezed.
                .horizontalScroll(rememberScrollState(), enabled = false),
            horizontalArrangement = Arrangement.spacedBy(tokens.previewTileGap)
        ) {
            tiles.forEach { (folder, item) ->
                val tile = folderPreviewTileSize(
                    folder.tileShape, landscapeRow, tokens.previewTileWidth, tokens.previewTileHeight,
                    tokens.previewLandscapeTileWidth, tokens.previewLandscapeTileHeight, home
                )
                FolderMiniTile(
                    imageUrl = item.marqueeImageUrl(landscapeRow = landscapeRow),
                    emoji = folder.coverEmoji,
                    width = tile.width,
                    height = tile.height,
                    radius = tokens.previewTileRadius,
                    emojiStyle = NuvioTheme.textStyles.panelValueLarge
                )
            }
        }
    }
}

/** A preview card [height] tall: [backdropUrl] under the hero's left scrim, then [content]. */
@Composable
internal fun CollectionPreviewCard(
    height: Dp,
    backdropUrl: String?,
    content: @Composable BoxScope.() -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    val scrim = remember(marquee.scrimLeftStops) {
        Brush.horizontalGradient(
            *marquee.scrimLeftStops.map { (at, a) -> at to NuvioPrimitives.black.copy(alpha = a) }.toTypedArray()
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(tokens.previewRadius))
            .background(tokens.rowFill)
    ) {
        MarqueeBackdropImage(url = backdropUrl, focal = PREVIEW_FOCAL, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(scrim))
        content()
    }
}

/** A folder in the list: its own cover art (cover image, else emoji), title, catalog count. */
@Composable
private fun FolderRow(
    folder: CollectionFolder,
    landscape: Boolean,
    focusRequester: FocusRequester,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    CollectionListRow(
        title = folder.title,
        summary = pluralStringResource(R.plurals.collections_editor_folder_catalogs, folder.sources.size, folder.sources.size),
        focusRequester = focusRequester,
        onClick = onEdit,
        thumb = {
            FolderMiniTile(
                imageUrl = collectionFolderCardImageUrl(folder),
                emoji = folder.coverEmoji,
                width = if (landscape) tokens.thumbWidth else tokens.thumbPosterWidth,
                height = tokens.thumbHeight,
                radius = tokens.thumbRadius,
                emojiStyle = NuvioTheme.textStyles.panelValueLarge
            )
        }
    ) {
        CollectionRowAction(stringResource(R.string.cd_move_up), onMoveUp)
        CollectionRowAction(stringResource(R.string.cd_move_down), onMoveDown)
        CollectionRowAction(stringResource(R.string.cd_delete), onDelete, destructive = true)
    }
}

/** Spec 28 Q4: Back from an editor with unsaved edits; Keep editing is focused. Also the profile editor's. */
@Composable
internal fun DiscardChangesPanel(
    body: String,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit
) {
    MarqueeConfirmPanel(
        eyebrow = stringResource(R.string.collections_discard_eyebrow),
        body = body,
        confirmLabel = stringResource(R.string.collections_discard_confirm),
        cancelLabel = stringResource(R.string.collections_discard_keep),
        onConfirm = onDiscard,
        onDismiss = onKeepEditing,
        destructive = true
    )
}

// More tiles than the preview card can show (7 posters at 380 dp) cost loads for nothing.
private const val PREVIEW_TILE_LIMIT = 8

// The mockup's object-position 50% 30%.
private val PREVIEW_FOCAL = BiasAlignment(horizontalBias = 0f, verticalBias = -0.4f)
