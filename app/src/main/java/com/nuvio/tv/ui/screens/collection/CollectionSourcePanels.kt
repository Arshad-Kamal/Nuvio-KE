package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueePickerOption
import com.nuvio.tv.ui.components.marquee.MarqueePickerOptionList
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedContentType

/** One action in [SourceActionsPanel], in the order the panel lists them. */
private enum class SourceAction { GENRE, EDIT, MOVE_UP, MOVE_DOWN, REMOVE }

/** Value of the genre list's "All genres" option (no genre). */
private const val ALL_GENRES_VALUE = "__all__"

/**
 * Stage 14: a source's actions, [eyebrow] "<title> · <kind>". An addon catalog with genres lists
 * Genre filter (its value + chevron; OK lists the genres in this panel, OK picks one and returns,
 * Back returns), then Move up, Move down, Remove (red); TMDB starts with Edit ([onEdit]); a Trakt
 * list (Q3) explains why it no longer loads and starts with Remove. A move at an edge is dimmed.
 * Moves keep the panel open; Remove and Edit close it (the caller does).
 */
@Composable
internal fun SourceActionsPanel(
    eyebrow: String,
    retired: Boolean,
    genreCatalog: AvailableCatalog?,
    genre: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onGenre: (String?) -> Unit,
    onEdit: (() -> Unit)?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val actions = buildList {
        if (retired) add(SourceAction.REMOVE)
        if (genreCatalog != null) add(SourceAction.GENRE)
        if (onEdit != null) add(SourceAction.EDIT)
        add(SourceAction.MOVE_UP)
        add(SourceAction.MOVE_DOWN)
        if (!retired) add(SourceAction.REMOVE)
    }
    val requesters = remember { SourceAction.entries.associateWith { FocusRequester() } }
    var genreOpen by remember { mutableStateOf(false) }
    val optionRequester = remember(genreOpen) { FocusRequester() }
    val allGenres = stringResource(R.string.collections_editor_all_genres)

    MarqueeSidePanel(
        onDismiss = { if (genreOpen) genreOpen = false else onDismiss() },
        // The genre page returns to Genre filter, which is the first action when present.
        initialFocus = if (genreOpen) optionRequester else requesters.getValue(actions.first())
    ) {
        if (genreOpen && genreCatalog != null) {
            val options = buildList {
                if (!genreCatalog.genreRequired) add(MarqueePickerOption(allGenres, ALL_GENRES_VALUE))
                genreCatalog.genreOptions.forEach { add(MarqueePickerOption(it, it)) }
            }
            MarqueePickerOptionList(
                title = stringResource(R.string.collections_editor_genre_filter),
                options = options,
                selectedValue = genre ?: ALL_GENRES_VALUE,
                focusRequester = optionRequester,
                onSelect = { value ->
                    onGenre(value.takeUnless { it == ALL_GENRES_VALUE })
                    genreOpen = false
                }
            )
        } else {
            SourceActionRows(
                eyebrow = eyebrow,
                retired = retired,
                actions = actions,
                requesters = requesters,
                genreValue = genre ?: allGenres,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onOpenGenre = { genreOpen = true },
                onEdit = { onEdit?.invoke() },
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove
            )
        }
    }
}

@Composable
private fun SourceActionRows(
    eyebrow: String,
    retired: Boolean,
    actions: List<SourceAction>,
    requesters: Map<SourceAction, FocusRequester>,
    genreValue: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpenGenre: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    PanelEyebrow(text = eyebrow)
    if (retired) {
        Text(
            text = stringResource(R.string.collections_editor_trakt_retired_body),
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage2,
            modifier = Modifier.padding(
                start = NuvioTheme.spacing.sm,
                end = NuvioTheme.spacing.sm,
                bottom = NuvioTheme.spacing.sm
            )
        )
    }
    actions.forEach { action ->
        val requester = requesters.getValue(action)
        when (action) {
            SourceAction.GENRE -> PlayerPanelRow(
                title = stringResource(R.string.collections_editor_genre_filter),
                selected = false,
                onClick = onOpenGenre,
                titleEnd = genreValue,
                trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                focusRequester = requester
            )
            SourceAction.EDIT -> PlayerPanelRow(
                title = stringResource(R.string.cd_edit),
                selected = false,
                onClick = onEdit,
                focusRequester = requester
            )
            SourceAction.MOVE_UP -> PlayerPanelRow(
                title = stringResource(R.string.cd_move_up),
                selected = false,
                onClick = onMoveUp,
                focusRequester = requester,
                enabled = canMoveUp
            )
            SourceAction.MOVE_DOWN -> PlayerPanelRow(
                title = stringResource(R.string.cd_move_down),
                selected = false,
                onClick = onMoveDown,
                focusRequester = requester,
                enabled = canMoveDown
            )
            SourceAction.REMOVE -> PlayerPanelRow(
                title = stringResource(R.string.cd_remove),
                selected = false,
                onClick = onRemove,
                focusRequester = requester,
                destructive = true
            )
        }
    }
}

/**
 * Stage 13: the installed catalogs grouped under each addon's name, "<catalog> · <type>". An
 * added one shows "Added" and a green tick, the rest a plus and their as-built genre note; OK
 * adds or removes ([onToggle]) and the panel stays open; Back closes ([onDismiss]).
 */
@Composable
internal fun AddCatalogPanel(
    catalogs: List<AvailableCatalog>,
    sources: List<CollectionSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    val firstRequester = remember { FocusRequester() }
    val groups = remember(catalogs) { catalogs.groupBy { it.addonId }.values.toList() }
    MarqueeSidePanel(onDismiss = onDismiss, initialFocus = firstRequester, fillHeight = true) {
        PanelEyebrow(text = stringResource(R.string.collections_editor_add_catalogs))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (groups.isEmpty()) {
                // Something focusable, so focus leaves the form behind.
                PlayerPanelRow(
                    title = stringResource(R.string.collections_editor_no_catalogs),
                    selected = false,
                    onClick = {},
                    focusRequester = firstRequester,
                    enabled = false
                )
            }
            groups.forEachIndexed { groupIndex, group ->
                PanelEyebrowText(
                    text = group.first().addonName,
                    modifier = Modifier.padding(
                        start = NuvioTheme.spacing.sm,
                        top = if (groupIndex == 0) NuvioTheme.spacing.none else tokens.sectionTopPadding,
                        bottom = tokens.sectionBottomPadding
                    )
                )
                group.forEachIndexed { index, catalog ->
                    val added = sources.any { it is AddonCatalogCollectionSource && it.isFor(catalog) }
                    PlayerPanelRow(
                        title = "${catalog.catalogName.replaceFirstChar { it.uppercase() }} · ${localizedContentType(catalog.type)}",
                        selected = false,
                        onClick = { onToggle(catalog) },
                        subtitle = when {
                            added -> stringResource(R.string.collections_editor_catalog_added)
                            catalog.genreRequired -> stringResource(R.string.collections_editor_genre_required)
                            catalog.genreOptions.isNotEmpty() -> stringResource(R.string.collections_editor_genre_optional)
                            else -> null
                        },
                        focusRequester = if (groupIndex == 0 && index == 0) firstRequester else null,
                        trailingIcon = if (added) Icons.Default.Check else Icons.Default.Add,
                        trailingIconTint = if (added) NuvioTheme.marquee.readyText else null
                    )
                }
            }
        }
    }
}
