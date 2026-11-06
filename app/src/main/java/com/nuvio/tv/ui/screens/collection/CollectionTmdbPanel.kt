package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PanelPill
import com.nuvio.tv.ui.components.PanelSegments
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Stage 15, the TMDB panel (520 wide): the eight as-built modes as chips (OK picks one), then the
 * mode's fields as rows. Presets lists the ready-made sources (OK adds one). Production and
 * Collection search by name in the `.l8fld` pill and list the results (OK adds one); they and
 * Public list, Network, Person, Director also add from an ID or URL (Add source), Custom from its
 * filter rows ([TmdbDiscoverFilterRows]). Display title, Type (where the mode
 * has a choice) and Sort are rows; the error line is red. Editing a source ([CollectionEditorUiState.editingTmdbSourceIndex])
 * replaces it. Opens on the current mode's chip; Back closes. All state and logic is the VM's.
 */
@Composable
internal fun TmdbSourcePanel(uiState: CollectionEditorUiState, viewModel: CollectionEditorViewModel) {
    val tokens = NuvioTheme.marquee.collections
    val mode = uiState.tmdbBuilderMode
    val editing = uiState.editingTmdbSourceIndex != null
    val modeFocus = remember { FocusRequester() }

    MarqueeSidePanel(
        onDismiss = viewModel::hideTmdbSourcePicker,
        initialFocus = modeFocus,
        fillHeight = true,
        width = tokens.tmdbPanelWidth
    ) {
        PanelEyebrow(
            text = stringResource(
                if (editing) R.string.collections_editor_edit_tmdb_source else R.string.collections_editor_add_tmdb_source
            )
        )
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.panelGap)
        ) {
            TmdbModeChips(mode = mode, modeFocus = modeFocus, onMode = viewModel::setTmdbBuilderMode)
            if (mode == TmdbBuilderMode.PRESETS) {
                TmdbPresetRows(presets = viewModel.tmdbPresets(), onAdd = viewModel::addTmdbSource)
            } else {
                TmdbSourceFields(uiState = uiState, viewModel = viewModel, editing = editing)
            }
        }
    }
}

/** `.l8chips`: the eight modes; the current one in glass holds [modeFocus]. */
@Composable
private fun TmdbModeChips(mode: TmdbBuilderMode, modeFocus: FocusRequester, onMode: (TmdbBuilderMode) -> Unit) {
    val tokens = NuvioTheme.marquee.collections
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(tokens.panelGap),
        verticalArrangement = Arrangement.spacedBy(tokens.panelGap)
    ) {
        TmdbBuilderMode.entries.forEach { entry ->
            PanelPill(
                text = tmdbModeLabel(entry),
                selected = entry == mode,
                enabled = true,
                horizontalPadding = NuvioTheme.marquee.playerPanel.segmentPaddingHorizontal,
                onClick = { if (entry != mode) onMode(entry) },
                modifier = if (entry == mode) Modifier.focusRequester(modeFocus) else Modifier
            )
        }
    }
}

@Composable
private fun TmdbPresetRows(presets: List<TmdbPresetSource>, onAdd: (TmdbCollectionSource) -> Unit) {
    Column {
        presets.forEach { preset ->
            PlayerPanelRow(
                title = preset.title,
                selected = false,
                onClick = { onAdd(preset.source) },
                subtitle = tmdbSourceSubtitle(preset.source),
                trailingIcon = Icons.Default.Add
            )
        }
    }
}

/**
 * Every mode but Presets: the search pill (Production, Collection) or the ID / URL row (none for
 * Custom), Display title, Type, Sort, Custom's filter rows, Add source (Save source when
 * editing), the error, then the search results.
 */
@Composable
private fun TmdbSourceFields(
    uiState: CollectionEditorUiState,
    viewModel: CollectionEditorViewModel,
    editing: Boolean
) {
    val mode = uiState.tmdbBuilderMode
    val searchFocus = remember { FocusRequester() }
    when (mode) {
        TmdbBuilderMode.PRODUCTION, TmdbBuilderMode.COLLECTION -> CollectionFieldPill(
            icon = Icons.Default.Search,
            value = uiState.tmdbInput,
            onValueChange = viewModel::setTmdbInput,
            placeholder = stringResource(
                if (mode == TmdbBuilderMode.PRODUCTION) {
                    R.string.collections_editor_tmdb_company_placeholder_example
                } else {
                    R.string.collections_editor_tmdb_collection_placeholder_example
                }
            ),
            actionLabel = stringResource(R.string.collections_editor_tmdb_search),
            onAction = if (mode == TmdbBuilderMode.PRODUCTION) {
                viewModel::searchTmdbCompanies
            } else {
                viewModel::searchTmdbCollections
            },
            actionFocus = searchFocus
        )
        TmdbBuilderMode.LIST, TmdbBuilderMode.NETWORK, TmdbBuilderMode.PERSON, TmdbBuilderMode.DIRECTOR -> {
            val (label, placeholder) = when (mode) {
                TmdbBuilderMode.LIST -> R.string.collections_editor_tmdb_public_list to
                    R.string.collections_editor_tmdb_placeholder_list
                TmdbBuilderMode.NETWORK -> R.string.collections_editor_tmdb_network_id to
                    R.string.collections_editor_tmdb_network_placeholder_example
                else -> R.string.collections_editor_tmdb_person_id to R.string.collections_editor_tmdb_person_placeholder
            }
            CollectionTextRow(
                label = stringResource(label),
                value = uiState.tmdbInput,
                onValueChange = viewModel::setTmdbInput,
                display = uiState.tmdbInput,
                placeholder = stringResource(placeholder),
                keyboardType = KeyboardType.Uri
            )
        }
        // Custom has no ID; Presets never reaches here.
        TmdbBuilderMode.DISCOVER, TmdbBuilderMode.PRESETS -> Unit
    }
    CollectionTextRow(
        label = stringResource(R.string.collections_editor_tmdb_display_title),
        value = uiState.tmdbTitleInput,
        onValueChange = viewModel::setTmdbTitleInput,
        display = uiState.tmdbTitleInput,
        placeholder = stringResource(R.string.collections_editor_tmdb_title_auto)
    )
    if (mode.hasMediaChoice) {
        TmdbTypeRow(
            mediaType = uiState.tmdbMediaType,
            both = uiState.tmdbMediaBoth,
            onMediaType = viewModel::setTmdbMediaType,
            onBoth = { viewModel.setTmdbMediaBoth(true) }
        )
    }
    TmdbSortRow(
        mode = mode,
        sortBy = uiState.tmdbSortBy,
        tvOnly = uiState.tmdbMediaType == TmdbCollectionMediaType.TV && !uiState.tmdbMediaBoth,
        onSort = viewModel::setTmdbSortBy
    )
    if (mode == TmdbBuilderMode.DISCOVER) {
        TmdbDiscoverFilterRows(
            mediaType = uiState.tmdbMediaType,
            filters = uiState.tmdbFilters,
            onFiltersChange = viewModel::setTmdbFilters
        )
    }
    PlayerPanelRow(
        title = stringResource(if (editing) R.string.collections_editor_save_source else R.string.collections_editor_add_source),
        selected = false,
        onClick = if (mode == TmdbBuilderMode.DISCOVER) viewModel::addDiscoverSource else viewModel::addTmdbSourceFromInput,
        trailingIcon = if (editing) Icons.Default.Check else Icons.Default.Add
    )
    uiState.tmdbSearchError?.takeIf { it.isNotBlank() }?.let { error ->
        Text(
            text = error,
            style = NuvioTheme.textStyles.panelPill,
            color = NuvioPrimitives.errorOnImage,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
        )
    }
    when (mode) {
        TmdbBuilderMode.PRODUCTION -> TmdbCompanyResults(uiState = uiState, onAdd = viewModel::addTmdbCompany)
        TmdbBuilderMode.COLLECTION -> TmdbCollectionResults(uiState = uiState, onAdd = viewModel::addTmdbCollection)
        else -> Unit
    }
}

/** Modes whose sources take a media type (as built: the others are fixed). */
private val TmdbBuilderMode.hasMediaChoice: Boolean
    get() = this == TmdbBuilderMode.PRODUCTION || this == TmdbBuilderMode.PERSON ||
        this == TmdbBuilderMode.DIRECTOR || this == TmdbBuilderMode.DISCOVER

/** Type: Movie / Series / Both. */
@Composable
private fun TmdbTypeRow(
    mediaType: TmdbCollectionMediaType,
    both: Boolean,
    onMediaType: (TmdbCollectionMediaType) -> Unit,
    onBoth: () -> Unit
) {
    MarqueeFormRow(label = stringResource(R.string.library_filter_type)) {
        PanelSegments(
            options = listOf(
                stringResource(R.string.type_movie),
                stringResource(R.string.type_series),
                stringResource(R.string.collection_editor_choice_both)
            ),
            selectedIndex = when {
                both -> 2
                mediaType == TmdbCollectionMediaType.TV -> 1
                else -> 0
            },
            onSelect = { index ->
                when (index) {
                    0 -> onMediaType(TmdbCollectionMediaType.MOVIE)
                    1 -> onMediaType(TmdbCollectionMediaType.TV)
                    else -> onBoth()
                }
            }
        )
    }
}

/**
 * Sort, as built: lists and collections offer their original order, networks and the credit /
 * discover modes Popular; all offer Top rated, Most voted and Recent (first-air date for a
 * series-only source). A stored sort not offered selects nothing.
 */
@Composable
private fun TmdbSortRow(mode: TmdbBuilderMode, sortBy: String, tvOnly: Boolean, onSort: (String) -> Unit) {
    val sorts = buildList {
        if (mode.usesOriginalOrder) {
            add(TmdbCollectionSort.ORIGINAL.value to stringResource(R.string.collections_editor_sort_original))
        } else {
            add(TmdbCollectionSort.POPULAR_DESC.value to stringResource(R.string.tmdb_entity_rail_popular))
        }
        add(TmdbCollectionSort.VOTE_AVERAGE_DESC.value to stringResource(R.string.tmdb_entity_rail_top_rated))
        add(TmdbCollectionSort.VOTE_COUNT_DESC.value to stringResource(R.string.tmdb_entity_rail_most_voted))
        val recent = if (tvOnly) TmdbCollectionSort.FIRST_AIR_DATE_DESC else TmdbCollectionSort.RELEASE_DATE_DESC
        add(recent.value to stringResource(R.string.tmdb_entity_rail_recent))
    }
    MarqueeFormRow(label = stringResource(R.string.library_filter_sort)) {
        PanelSegments(
            options = sorts.map { it.second },
            selectedIndex = sorts.indexOfFirst { it.first == sortBy },
            onSelect = { onSort(sorts[it].first) }
        )
    }
}

/** Production results, "Production · US · ID 3"; OK adds the company ([onAdd] id, title). */
@Composable
private fun TmdbCompanyResults(uiState: CollectionEditorUiState, onAdd: (Int, String) -> Unit) {
    if (uiState.tmdbCompanyResults.isEmpty()) return
    val productionLabel = stringResource(R.string.collections_editor_tmdb_mode_production)
    TmdbResultsLabel()
    Column {
        uiState.tmdbCompanyResults.forEach { result ->
            val title = result.name ?: stringResource(R.string.collections_editor_tmdb_company_fallback, result.id)
            PlayerPanelRow(
                title = title,
                selected = false,
                onClick = { onAdd(result.id, title) },
                subtitle = listOfNotNull(
                    productionLabel,
                    result.originCountry?.takeIf { it.isNotBlank() },
                    stringResource(R.string.collections_editor_tmdb_result_id, result.id)
                ).joinToString(" · "),
                trailingIcon = Icons.Default.Add
            )
        }
    }
}

/** Collection results, "TMDB Collection · ID 10"; OK adds it ([onAdd] id, title). */
@Composable
private fun TmdbCollectionResults(uiState: CollectionEditorUiState, onAdd: (Int, String) -> Unit) {
    if (uiState.tmdbCollectionResults.isEmpty()) return
    val collectionLabel = stringResource(R.string.collections_editor_tmdb_collection)
    TmdbResultsLabel()
    Column {
        uiState.tmdbCollectionResults.forEach { result ->
            val title = result.name ?: stringResource(R.string.collections_editor_tmdb_collection_fallback, result.id)
            PlayerPanelRow(
                title = title,
                selected = false,
                onClick = { onAdd(result.id, title) },
                subtitle = "$collectionLabel · ${stringResource(R.string.collections_editor_tmdb_result_id, result.id)}",
                trailingIcon = Icons.Default.Add
            )
        }
    }
}

/** `.l8sec` "Results", inset to the panel rows' text. */
@Composable
private fun TmdbResultsLabel() {
    PanelEyebrowText(
        text = stringResource(R.string.collections_editor_tmdb_results),
        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.marquee.collections.sectionTopPadding)
    )
}

@Composable
private fun tmdbModeLabel(mode: TmdbBuilderMode): String = stringResource(
    when (mode) {
        TmdbBuilderMode.PRESETS -> R.string.collections_editor_tmdb_mode_presets
        TmdbBuilderMode.LIST -> R.string.collections_editor_tmdb_mode_public_list
        TmdbBuilderMode.PRODUCTION -> R.string.collections_editor_tmdb_mode_production
        TmdbBuilderMode.NETWORK -> R.string.collections_editor_tmdb_mode_network
        TmdbBuilderMode.COLLECTION -> R.string.collections_editor_tmdb_mode_collection
        TmdbBuilderMode.PERSON -> R.string.collections_editor_tmdb_mode_person
        TmdbBuilderMode.DIRECTOR -> R.string.collections_editor_tmdb_mode_director
        TmdbBuilderMode.DISCOVER -> R.string.collections_editor_tmdb_mode_custom
    }
)
