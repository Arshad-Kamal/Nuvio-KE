package com.nuvio.tv.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.marquee.MarqueeSwitchRow
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.ui.components.PanelSegments
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTitle
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.screens.home.buildCollectionFolderItem
import com.nuvio.tv.ui.screens.home.marqueeImageUrl
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedContentType

/**
 * Stage 11 folder editor: the look rows (Title, Cover, Hide title) and the "On Home" URL rows on
 * the left; the folder as Home draws it and its catalogs (type badges, a missing addon red, a
 * Trakt list amber, Q3) on the right; Cancel / Save top-right. Save needs a catalog (as built).
 * Back (and Cancel) asks before dropping unsaved edits (Q4). OK on Cover opens the Cover panel
 * (stage 12), on a source its actions panel (stage 14), Add catalog the catalog panel (stage 13)
 * and Add TMDB source (or a TMDB source's Edit) the TMDB panel (stage 15): side panels over the
 * form, which stays composed. Focus returns to the row that opened a panel.
 */
@Composable
fun FolderEditorContent(
    viewModel: CollectionEditorViewModel,
    uiState: CollectionEditorUiState
) {
    val folder = uiState.editingFolder ?: return

    var confirmDiscard by remember { mutableStateOf(false) }
    val leaveFolderEditor = {
        if (viewModel.hasUnsavedFolderChanges()) confirmDiscard = true else viewModel.cancelFolderEdit()
    }
    BackHandler(onBack = leaveFolderEditor)

    // Fixed targets and sources by key; a pending target may be a sourceFocusTarget.
    val focusTargets = rememberCollectionFocusTargets()
    fun requester(target: String) = focusTargets.requester(target)
    // Where focus goes when the open VM-flag panel (catalog, TMDB, cover) closes.
    var pickerReturn by remember { mutableStateOf<String?>(null) }
    // The source whose actions panel is open, by collectionSourceKey; null = closed.
    var actionsKey by remember { mutableStateOf<String?>(null) }

    val pickerOpen = uiState.showCatalogPicker || uiState.showTmdbSourcePicker || uiState.showCoverPanel

    LaunchedEffect(Unit) {
        focusTargets.pending = FOLDER_TITLE_TARGET
    }
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen) {
            pickerReturn?.let { focusTargets.pending = it }
            pickerReturn = null
        }
    }
    focusTargets.ServePendingFocus(content = folder.sources, fallback = FOLDER_TITLE_TARGET) { target ->
        resolveFolderFocusTarget(target, folder.sources)
    }
    fun openPicker(returnTo: String, open: () -> Unit) {
        pickerReturn = returnTo
        open()
    }

    CollectionEditorFrame(
        backdropUrl = uiState.backdropImageUrl,
        crumbs = listOf(
            stringResource(R.string.collections_header),
            uiState.title.ifBlank { stringResource(R.string.collections_new) },
            stringResource(R.string.collections_editor_edit_folder)
        ),
        canSave = folder.sources.isNotEmpty(),
        onCancel = leaveFolderEditor,
        onSave = { viewModel.saveFolderEdit() },
        form = {
            FolderFormFields(
                folder = folder,
                viewModel = viewModel,
                titleFocus = requester(FOLDER_TITLE_TARGET),
                coverFocus = requester(FOLDER_COVER_TARGET),
                onOpenCover = { openPicker(FOLDER_COVER_TARGET, viewModel::showCoverPanel) }
            )
        },
        side = {
            val tokens = NuvioTheme.marquee.collections
            EditorSectionLabel(stringResource(R.string.collections_editor_preview), first = true)
            FolderHomePreview(uiState = uiState, folder = folder)
            EditorSectionLabel(stringResource(R.string.collections_editor_catalogs_count, folder.sources.size))
            folder.sources.forEach { source ->
                val sourceKey = collectionSourceKey(source)
                // Keyed so a moved row keeps its composition, and with it its focus.
                key(sourceKey) {
                    SourceRow(
                        display = sourceDisplay(source, uiState),
                        focusRequester = requester(sourceKey),
                        onClick = { actionsKey = sourceKey }
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = tokens.sectionBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(tokens.headerButtonGap)
            ) {
                EditorAddButton(
                    label = stringResource(R.string.collections_editor_add_catalog),
                    onClick = { openPicker(FOLDER_ADD_CATALOG_TARGET, viewModel::showCatalogPicker) },
                    modifier = Modifier.focusRequester(requester(FOLDER_ADD_CATALOG_TARGET))
                )
                EditorAddButton(
                    label = stringResource(R.string.collections_editor_add_tmdb_source),
                    onClick = { openPicker(FOLDER_ADD_TMDB_TARGET, viewModel::showTmdbSourcePicker) },
                    modifier = Modifier.focusRequester(requester(FOLDER_ADD_TMDB_TARGET))
                )
            }
        }
    )

    if (confirmDiscard) {
        DiscardChangesPanel(
            body = stringResource(R.string.collections_discard_folder_body),
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                viewModel.cancelFolderEdit()
            }
        )
    }

    val actionsIndex = actionsKey?.let { folder.sources.indexOfSourceKey(it) } ?: -1
    folder.sources.getOrNull(actionsIndex)?.let { source ->
        SourceActions(
            source = source,
            index = actionsIndex,
            count = folder.sources.size,
            uiState = uiState,
            viewModel = viewModel,
            onKeyChange = { actionsKey = it },
            onClose = { returnTo ->
                actionsKey = null
                focusTargets.pending = returnTo
            },
            onEdit = { key ->
                actionsKey = null
                openPicker(sourceFocusTarget(actionsIndex)) { viewModel.editTmdbSource(key) }
            }
        )
    }

    // Panels compose after the form, so their BackHandlers win.
    when {
        uiState.showCatalogPicker -> AddCatalogPanel(
            catalogs = uiState.availableCatalogs,
            sources = folder.sources,
            onToggle = { viewModel.toggleCatalogSource(it) },
            onDismiss = { viewModel.hideCatalogPicker() }
        )
        uiState.showTmdbSourcePicker -> TmdbSourcePanel(uiState = uiState, viewModel = viewModel)
        uiState.showCoverPanel -> CoverPanel(
            folder = folder,
            landscape = uiState.landscapePosters,
            onClear = viewModel::clearFolderCover,
            onPickEmoji = { emoji ->
                viewModel.updateFolderCoverEmoji(emoji)
                viewModel.hideCoverPanel()
            },
            onImageMode = viewModel::switchToImageMode,
            onImageUrl = viewModel::updateFolderCoverImage,
            onDismiss = viewModel::hideCoverPanel
        )
    }
}

/**
 * The actions panel for [source], at [index] of [count]. Back returns focus to the source's row
 * (by its key, which a genre change updates through [onKeyChange]); Remove closes it with focus
 * on the source that takes its place, else the one above, else Add catalog ([onClose] gets the
 * target); [onEdit] hands a TMDB source to the TMDB panel.
 */
@Composable
private fun SourceActions(
    source: CollectionSource,
    index: Int,
    count: Int,
    uiState: CollectionEditorUiState,
    viewModel: CollectionEditorViewModel,
    onKeyChange: (String) -> Unit,
    onClose: (returnTo: String) -> Unit,
    onEdit: (key: String) -> Unit
) {
    val key = collectionSourceKey(source)
    val display = sourceDisplay(source, uiState)
    val genreCatalog = (source as? AddonCatalogCollectionSource)
        ?.let { uiState.catalogFor(it) }
        ?.takeIf { it.genreOptions.isNotEmpty() }
    SourceActionsPanel(
        eyebrow = "${display.title} · ${display.kind}",
        retired = source is TraktCollectionSource,
        genreCatalog = genreCatalog,
        genre = (source as? AddonCatalogCollectionSource)?.genre,
        canMoveUp = index > 0,
        canMoveDown = index < count - 1,
        onGenre = { genre -> viewModel.updateCatalogSourceGenre(key, genre)?.let(onKeyChange) },
        onEdit = if (source is TmdbCollectionSource) {
            { onEdit(key) }
        } else {
            null
        },
        onMoveUp = { viewModel.moveCatalogSourceUp(key) },
        onMoveDown = { viewModel.moveCatalogSourceDown(key) },
        onRemove = {
            onClose(sourceFocusTarget(index))
            viewModel.removeCatalogSource(key)
        },
        onDismiss = { onClose(key) }
    )
}

// Focus targets beside source keys (collectionSourceKey).
private const val FOLDER_TITLE_TARGET = "#title"

// The Tile shape segments, in order (Poster / Wide / Square).
private val TILE_SHAPES = listOf(PosterShape.POSTER, PosterShape.LANDSCAPE, PosterShape.SQUARE)
private const val FOLDER_COVER_TARGET = "#cover"
internal const val FOLDER_ADD_CATALOG_TARGET = "#add_catalog"
private const val FOLDER_ADD_TMDB_TARGET = "#add_tmdb"
private const val SOURCE_TARGET_PREFIX = "#source:"

/** A source row by position: survives the key change an edit, genre or removal makes. */
internal fun sourceFocusTarget(index: Int) = "$SOURCE_TARGET_PREFIX$index"

/**
 * The focus key for [target]: a [sourceFocusTarget] resolves against the current [sources] to
 * the source now at that index, else the last one, else Add catalog; any other target is itself.
 */
internal fun resolveFolderFocusTarget(target: String, sources: List<CollectionSource>): String {
    if (!target.startsWith(SOURCE_TARGET_PREFIX)) return target
    val index = target.removePrefix(SOURCE_TARGET_PREFIX).toIntOrNull() ?: return FOLDER_ADD_CATALOG_TARGET
    val source = sources.getOrNull(index) ?: sources.lastOrNull() ?: return FOLDER_ADD_CATALOG_TARGET
    return collectionSourceKey(source)
}

/** Title, Cover, Tile shape, Hide title; "On Home": Hero backdrop, Title logo, Hero video, Focus GIF (+ Play GIF). */
@Composable
private fun FolderFormFields(
    folder: CollectionFolder,
    viewModel: CollectionEditorViewModel,
    titleFocus: FocusRequester,
    coverFocus: FocusRequester,
    onOpenCover: () -> Unit
) {
    CollectionTextRow(
        label = stringResource(R.string.collections_editor_title),
        value = folder.title,
        onValueChange = viewModel::updateFolderTitle,
        display = folder.title,
        placeholder = stringResource(R.string.collections_editor_placeholder_folder),
        focusRequester = titleFocus
    )
    CoverRow(folder = folder, onClick = onOpenCover, modifier = Modifier.focusRequester(coverFocus))
    // Spec 41 D5 (u41-addon-card-shapes v2 st6): the folder's own tile shape on Home.
    MarqueeFormRow(label = stringResource(R.string.collections_editor_tile_shape)) {
        PanelSegments(
            options = listOf(
                stringResource(R.string.collections_editor_shape_poster),
                stringResource(R.string.collections_editor_shape_wide),
                stringResource(R.string.collections_editor_shape_square)
            ),
            selectedIndex = TILE_SHAPES.indexOf(folder.tileShape).coerceAtLeast(0),
            onSelect = { index -> viewModel.updateFolderTileShape(TILE_SHAPES[index]) }
        )
    }
    MarqueeSwitchRow(
        label = stringResource(R.string.collections_editor_hide_title),
        checked = folder.hideTitle,
        onCheckedChange = viewModel::updateFolderHideTitle
    )
    EditorSectionLabel(stringResource(R.string.collections_editor_on_home))
    CollectionUrlRow(
        label = stringResource(R.string.collections_editor_folder_hero_backdrop),
        url = folder.heroBackdropUrl,
        onValueChange = viewModel::updateFolderHeroBackdropUrl
    )
    CollectionUrlRow(
        label = stringResource(R.string.collections_editor_folder_title_logo),
        url = folder.titleLogoUrl,
        onValueChange = viewModel::updateFolderTitleLogoUrl
    )
    CollectionUrlRow(
        label = stringResource(R.string.collections_editor_folder_hero_video),
        url = folder.heroVideoUrl,
        onValueChange = viewModel::updateFolderHeroVideoUrl
    )
    CollectionUrlRow(
        label = stringResource(R.string.collections_editor_focus_gif),
        url = folder.focusGifUrl,
        onValueChange = viewModel::updateFolderFocusGifUrl
    )
    // Not in the mockup: the as-built on/off for a set GIF stays reachable (Spec 28 28k).
    if (!folder.focusGifUrl.isNullOrBlank()) {
        MarqueeSwitchRow(
            label = stringResource(R.string.collections_editor_play_gif),
            checked = folder.focusGifEnabled,
            onCheckedChange = viewModel::updateFolderFocusGifEnabled
        )
    }
}

/**
 * "Preview · on Home": the folder as Home's hero and row draw it, in small: the hero backdrop
 * under the left scrim, the collection eyebrow in the backdrop's accent, the title (or logo),
 * and the folder's tile in its Tile shape, or landscape under the Card shape setting (the same
 * item model and image rules).
 */
@Composable
private fun FolderHomePreview(uiState: CollectionEditorUiState, folder: CollectionFolder) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    val collection = uiState.draftCollection()
    val item = remember(collection, folder) { buildCollectionFolderItem(collection, folder) }
    val hero = item.heroPreview
    val accent = rememberAmbientAccentProvider(hero.backdrop)
    val landscape = uiState.landscapePosters
    val tile = folderPreviewTileSize(
        folder.tileShape, landscape, tokens.folderPreviewTileWidth, tokens.folderPreviewTileHeight,
        tokens.previewLandscapeTileWidth, tokens.previewLandscapeTileHeight, marquee.home
    )
    val tileWidth = tile.width
    val tileHeight = tile.height
    CollectionPreviewCard(height = tokens.folderPreviewHeight, backdropUrl = hero.backdrop) {
        Column(
            modifier = Modifier.padding(
                start = tokens.previewInset,
                top = tokens.previewInset,
                end = tokens.folderPreviewTileEnd + tileWidth + tokens.previewInset
            )
        ) {
            item.subtitle?.takeIf { it.isNotBlank() }?.let { eyebrow ->
                Text(
                    text = eyebrow.uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = accent(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MarqueeLogoTitle(
                title = hero.title,
                logo = hero.logo,
                logoHeight = tokens.folderPreviewLogoHeight,
                logoMaxWidth = tokens.folderPreviewLogoMaxWidth,
                textStyle = NuvioTheme.textStyles.panelStateTitle
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = tokens.folderPreviewTileTop, end = tokens.folderPreviewTileEnd)
        ) {
            FolderMiniTile(
                imageUrl = item.marqueeImageUrl(landscapeRow = landscape),
                emoji = folder.coverEmoji,
                width = tileWidth,
                height = tileHeight,
                radius = tokens.previewTileRadius,
                emojiStyle = NuvioTheme.textStyles.panelValueLarge
            )
        }
    }
}

/** How a source reads: its row, and its actions panel's eyebrow "<[title]> · <[kind]>". */
private class SourceDisplay(
    val title: String,
    val kind: String,
    val summary: String,
    val summaryColor: Color,
    val outline: Color?,
    val badge: String
)

/**
 * A missing addon's summary is red and a Trakt list's amber (Q3), each with an outline; only the
 * id is known for an uninstalled addon (Code plan deviation).
 */
@Composable
private fun sourceDisplay(source: CollectionSource, uiState: CollectionEditorUiState): SourceDisplay {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    return when (source) {
        is AddonCatalogCollectionSource -> {
            val catalog = uiState.catalogFor(source)
            val info = uiState.addonCatalogInfoFor(source)
            val typeLabel = localizedContentType(source.type)
            val missing = catalog == null && info == null
            SourceDisplay(
                title = (catalog?.catalogName ?: info?.catalogName)?.replaceFirstChar { it.uppercase() }
                    ?: source.catalogId,
                kind = typeLabel,
                summary = if (missing) {
                    stringResource(R.string.collections_editor_addon_missing, source.addonId)
                } else {
                    listOfNotNull(typeLabel, catalog?.addonName ?: info?.addonName, source.genre).joinToString(" · ")
                },
                summaryColor = if (missing) NuvioPrimitives.errorOnImage else marquee.onImage3,
                outline = if (missing) tokens.sourceMissingOutline else null,
                badge = stringResource(R.string.collections_editor_badge_addon)
            )
        }
        is TmdbCollectionSource -> SourceDisplay(
            title = source.title,
            kind = TMDB_BADGE,
            summary = tmdbSourceSubtitle(source),
            summaryColor = marquee.onImage3,
            outline = null,
            badge = TMDB_BADGE
        )
        // Q3: kept so stored folders round-trip; it no longer loads and cannot be edited.
        is TraktCollectionSource -> SourceDisplay(
            title = source.title,
            kind = stringResource(R.string.collections_editor_trakt_list),
            summary = stringResource(R.string.collections_editor_trakt_retired),
            summaryColor = NuvioPrimitives.warning,
            outline = tokens.sourceRetiredOutline,
            badge = TRAKT_BADGE
        )
    }
}

/** A catalog row: title, summary, type badge, chevron; OK opens its actions panel. */
@Composable
private fun SourceRow(display: SourceDisplay, focusRequester: FocusRequester, onClick: () -> Unit) {
    CollectionListRow(
        title = display.title,
        summary = display.summary,
        focusRequester = focusRequester,
        onClick = onClick,
        summaryColor = display.summaryColor,
        outline = display.outline,
        badge = display.badge
    )
}

private fun CollectionEditorUiState.addonCatalogInfoFor(source: AddonCatalogCollectionSource) =
    addonCatalogInfoByKey["${source.addonId}|${source.type}|${source.catalogId}"]
        ?: addonCatalogInfoByKey["${source.addonId}|${source.type}|${source.catalogId.substringBefore(",")}"]

private const val TMDB_BADGE = "TMDB"
private const val TRAKT_BADGE = "Trakt"
