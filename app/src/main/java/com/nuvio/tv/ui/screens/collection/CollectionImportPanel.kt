package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ValidationResult
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelSegments
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Stage 8 import panel, centred over the list: Source (From file / From URL), the source pill
 * (file: what Load file reads; URL: the address field + Fetch URL), the outcome line (green
 * counts when valid, the error in red), the missing-addon note, then Confirm import (only when
 * valid, and focused then) and Cancel. Opens on Load file; From URL focuses the field (the
 * keyboard opens). Back (and Cancel) call [onDismiss]; Back in the field leaves it for Fetch URL
 * first. All state is CollectionManagementViewModel's.
 */
@Composable
internal fun CollectionImportPanel(
    importMode: ImportMode,
    importUrl: String,
    validationResult: ValidationResult?,
    importError: String?,
    isLoadingImport: Boolean,
    onModeChange: (ImportMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onFetchUrl: () -> Unit,
    onPickFile: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    val stream = NuvioTheme.marquee.stream
    val loadFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val valid = validationResult?.valid == true

    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = tokens.panelVeil,
        width = tokens.importWidth,
        initialFocus = loadFocus
    ) {
        LaunchedEffect(valid) {
            if (valid) confirmFocus.requestFocusAfterFrames()
        }
        PanelEyebrow(text = stringResource(R.string.collections_import_header))
        Column(verticalArrangement = Arrangement.spacedBy(tokens.panelGap)) {
            ImportSourceRow(importMode = importMode, onModeChange = onModeChange)
            ImportSourceField(
                importMode = importMode,
                importUrl = importUrl,
                isLoadingImport = isLoadingImport,
                onUrlChange = onUrlChange,
                onFetchUrl = onFetchUrl,
                onPickFile = onPickFile,
                loadFocus = loadFocus
            )
            // The error wins: Confirm can fail on a file that validated (nothing importable).
            val validText = validationResult?.takeIf { it.valid }?.let { result ->
                stringResource(
                    R.string.collections_import_valid,
                    pluralStringResource(R.plurals.collections_import_collections, result.collectionCount, result.collectionCount),
                    pluralStringResource(R.plurals.collections_row_folders, result.folderCount, result.folderCount)
                )
            }
            val outcome = importError ?: validText
            if (outcome != null) {
                Text(
                    text = outcome,
                    style = NuvioTheme.textStyles.panelPill,
                    color = if (importError == null) NuvioTheme.marquee.readyText else NuvioPrimitives.errorOnImage,
                    modifier = Modifier.padding(horizontal = stream.dialogTextInset)
                )
            }
            Text(
                text = stringResource(R.string.collections_import_warning),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.marquee.onImage2,
                modifier = Modifier.padding(horizontal = stream.dialogTextInset)
            )
        }
        MarqueePanelActions {
            if (valid) {
                MarqueePrimaryButton(
                    label = stringResource(R.string.collections_confirm_import),
                    icon = null,
                    onClick = onConfirmImport,
                    focusRequester = confirmFocus,
                    compact = true
                )
            }
            CollectionsHeaderButton(
                label = stringResource(R.string.collections_cancel),
                onClick = onDismiss
            )
        }
    }
}

/** `.l8r` Source row: the label, then From file / From URL. */
@Composable
private fun ImportSourceRow(importMode: ImportMode, onModeChange: (ImportMode) -> Unit) {
    val tokens = NuvioTheme.marquee.formRow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.minHeight)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.fill)
            .padding(horizontal = tokens.paddingHorizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.collections_import_source),
            style = NuvioTheme.textStyles.button,
            color = NuvioPrimitives.white,
            modifier = Modifier.weight(1f)
        )
        PanelSegments(
            options = listOf(
                stringResource(R.string.collections_mode_file),
                stringResource(R.string.collections_mode_url)
            ),
            selectedIndex = importMode.ordinal,
            onSelect = { onModeChange(ImportMode.entries[it]) }
        )
    }
}

/**
 * The `.l8fld` source pill. URL: the address field (IME Done fetches) + Fetch URL; switching to
 * URL focuses the field. File: the file it reads + Load file ([loadFocus], the panel's first
 * focus).
 */
@Composable
private fun ImportSourceField(
    importMode: ImportMode,
    importUrl: String,
    isLoadingImport: Boolean,
    onUrlChange: (String) -> Unit,
    onFetchUrl: () -> Unit,
    onPickFile: () -> Unit,
    loadFocus: FocusRequester
) {
    val urlFocus = remember { FocusRequester() }
    val fetchFocus = remember { FocusRequester() }
    val isUrl = importMode == ImportMode.URL
    LaunchedEffect(isUrl) {
        if (isUrl) urlFocus.requestFocusAfterFrames()
    }
    CollectionFieldPill(
        icon = if (isUrl) Icons.Default.Search else Icons.Default.Folder,
        value = if (isUrl) importUrl else stringResource(R.string.collections_file_description),
        onValueChange = if (isUrl) onUrlChange else null,
        placeholder = stringResource(R.string.collections_import_url_placeholder),
        actionLabel = when {
            isUrl && isLoadingImport -> stringResource(R.string.collections_fetching)
            isUrl -> stringResource(R.string.collections_fetch_url)
            else -> stringResource(R.string.collections_load_file)
        },
        onAction = if (isUrl) onFetchUrl else onPickFile,
        actionFocus = if (isUrl) fetchFocus else loadFocus,
        fieldFocus = urlFocus,
        keyboardType = KeyboardType.Uri
    )
}
