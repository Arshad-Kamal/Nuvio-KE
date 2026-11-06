package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.ui.theme.NuvioTheme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.ui.components.collectionFolderCardImageUrl
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionManagementScreen(
    viewModel: CollectionManagementViewModel = hiltViewModel(),
    onNavigateToEditor: (String?) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Where focus returns when the list reappears (back from the editor): a row id or
    // NEW_TARGET; null = the first row.
    var restoreId by rememberSaveable { mutableStateOf<String?>(null) }
    // Rows by id and NEW_TARGET (New collection, or the empty message's button).
    val focusTargets = rememberCollectionFocusTargets()

    var collectionToDelete by remember { mutableStateOf<Collection?>(null) }

    // The last export's outcome (true = saved), shown on the button for 3 s; null = idle.
    var exportSaved by remember { mutableStateOf<Boolean?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(exportSaved) {
        if (exportSaved != null) {
            kotlinx.coroutines.delay(3000)
            exportSaved = null
        }
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    val collections = uiState.collections
    val tokens = NuvioTheme.marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal

    // On every entry to the list (first open, back from the editor). Empty with no restore
    // target: null, and the message focuses New collection itself. The import panel is a window
    // over the list, so closing it leaves focus on Import.
    LaunchedEffect(Unit) {
        focusTargets.pending = restoreId?.takeIf { id -> id == NEW_TARGET || collections.any { it.id == id } }
            ?: collections.firstOrNull()?.id
    }
    focusTargets.ServePendingFocus(content = collections)

    val openEditor: (String?) -> Unit = { id ->
        restoreId = id ?: NEW_TARGET
        onNavigateToEditor(id)
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        if (collections.isEmpty()) {
            MarqueeMessage(
                eyebrow = stringResource(R.string.collections_header),
                title = stringResource(R.string.collections_empty_title),
                body = AnnotatedString(stringResource(R.string.collections_empty_body)),
                actionLabel = stringResource(R.string.collections_new),
                onAction = { openEditor(null) },
                focusRequester = focusTargets.requester(NEW_TARGET)
            )
        } else {
            MarqueeBackdropImage(
                url = collections.first().backdropImageUrl,
                focal = BACKDROP_FOCAL,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(tokens.veil))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = tokens.listTop)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = safeHorizontal,
                        end = safeHorizontal,
                        bottom = NuvioTheme.layout.tvSafeVertical
                    ),
                verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
            ) {
                collections.forEach { collection ->
                    // Keyed so a moved row keeps its composition, and with it the focused action.
                    key(collection.id) {
                        CollectionRow(
                            collection = collection,
                            focusRequester = focusTargets.requester(collection.id),
                            onEdit = { openEditor(collection.id) },
                            onMoveUp = { viewModel.moveUp(collection.id) },
                            onMoveDown = { viewModel.moveDown(collection.id) },
                            onDelete = { collectionToDelete = collection }
                        )
                    }
                }
            }
        }

        // Opened from Settings › Content & Discovery (Q6); the Addon Manager's card goes with its rebuild.
        MarqueeBreadcrumb(
            listOf(
                stringResource(R.string.nav_settings),
                stringResource(R.string.collections_header)
            )
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = tokens.headerTop, end = safeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(tokens.headerButtonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CollectionsHeaderButton(
                label = stringResource(R.string.collections_import),
                onClick = { viewModel.showImportDialog() }
            )
            if (collections.isNotEmpty()) {
                CollectionsHeaderButton(
                    label = when (exportSaved) {
                        true -> stringResource(R.string.collections_saved_downloads)
                        false -> stringResource(R.string.collections_export_failed)
                        null -> stringResource(R.string.collections_export_file)
                    },
                    onClick = {
                        // One write at a time: an overlapping one would leave MediaStore's
                        // "nuvio-collections (1).json" that import never reads.
                        if (exportJob?.isActive != true) {
                            exportJob = scope.launch { exportSaved = writeExportFile(context, viewModel) }
                        }
                    }
                )
                MarqueePrimaryButton(
                    label = stringResource(R.string.collections_new),
                    icon = null,
                    onClick = { openEditor(null) },
                    focusRequester = focusTargets.requester(NEW_TARGET),
                    compact = true
                )
            }
        }
    }

    collectionToDelete?.let { collection ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.collections_delete_eyebrow),
            body = stringResource(R.string.collections_delete_confirm_subtitle, collection.title),
            confirmLabel = stringResource(R.string.collections_delete_confirm),
            cancelLabel = stringResource(R.string.collections_cancel),
            onConfirm = {
                val index = collections.indexOfFirst { it.id == collection.id }
                // Focus moves to the row that takes its place, else the one above; none = the
                // empty message, which focuses New collection itself.
                focusTargets.pending = (collections.getOrNull(index + 1) ?: collections.getOrNull(index - 1))?.id
                viewModel.deleteCollection(collection.id)
                collectionToDelete = null
            },
            onDismiss = { collectionToDelete = null },
            destructive = true
        )
    }

    if (uiState.showImportDialog) {
        CollectionImportPanel(
            importMode = uiState.importMode,
            importUrl = uiState.importUrl,
            validationResult = uiState.validationResult,
            importError = uiState.importError,
            isLoadingImport = uiState.isLoadingImport,
            onModeChange = { viewModel.setImportMode(it) },
            onUrlChange = { viewModel.updateImportUrl(it) },
            onFetchUrl = { viewModel.fetchUrl() },
            onPickFile = { viewModel.loadFromFile(context) },
            onConfirmImport = { viewModel.confirmImport() },
            onDismiss = { viewModel.hideImportDialog() }
        )
    }
}

// Not a collection id (those come from CollectionsDataStore.generateId).
private const val NEW_TARGET = "#new"

/**
 * Writes the export JSON to Downloads/nuvio-collections.json (replacing the old file). False when
 * nothing was written. MediaStore.Downloads is API 29+; below that the press threw
 * NoClassDefFoundError (B7). The import side has the same check.
 */
private suspend fun writeExportFile(context: Context, viewModel: CollectionManagementViewModel): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
    return try {
        val json = viewModel.getExportJson()
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.delete(
                downloads,
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf("nuvio-collections.json")
            )
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "nuvio-collections.json")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            // Either call can return null (no provider / no stream): nothing was written.
            val uri = resolver.insert(downloads, values) ?: return@withContext false
            resolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray())
                true
            } ?: false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/** Stage 6 row: OK opens the editor; its actions are Edit, Move up, Move down, Delete (red). */
@Composable
private fun CollectionRow(
    collection: Collection,
    focusRequester: FocusRequester,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    CollectionListRow(
        title = collection.title,
        summary = collectionSummary(collection),
        focusRequester = focusRequester,
        onClick = onEdit,
        thumb = { CollectionThumb(collection) }
    ) {
        CollectionRowAction(stringResource(R.string.cd_edit), onEdit)
        CollectionRowAction(stringResource(R.string.cd_move_up), onMoveUp)
        CollectionRowAction(stringResource(R.string.cd_move_down), onMoveDown)
        CollectionRowAction(stringResource(R.string.cd_delete), onDelete, destructive = true)
    }
}

/** `.l8th`: the collection backdrop, else its first folder's cover image or emoji. */
@Composable
private fun CollectionThumb(collection: Collection) {
    val tokens = NuvioTheme.marquee.collections
    val firstFolder = collection.folders.firstOrNull()
    val imageUrl = collection.backdropImageUrl?.takeIf { it.isNotBlank() }
        ?: firstFolder?.let { collectionFolderCardImageUrl(it) }
    val emoji = firstFolder?.coverEmoji?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .size(width = tokens.thumbWidth, height = tokens.thumbHeight)
            .clip(RoundedCornerShape(tokens.thumbRadius))
            .background(tokens.thumbFill),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (emoji != null) {
            Text(text = emoji, style = NuvioTheme.textStyles.panelValueLarge)
        }
    }
}

/** "4 folders · Pinned · Tabs": stored FOLLOW_LAYOUT reads as Rows (Q1). */
@Composable
private fun collectionSummary(collection: Collection): String = listOfNotNull(
    pluralStringResource(R.plurals.collections_row_folders, collection.folders.size, collection.folders.size),
    if (collection.pinToTop) stringResource(R.string.collections_row_pinned) else null,
    stringResource(
        if (collection.viewMode.displayMode == FolderViewMode.TABBED_GRID) {
            R.string.collections_editor_view_mode_tabs
        } else {
            R.string.collections_editor_view_mode_rows
        }
    )
).joinToString(" \u00B7 ")
