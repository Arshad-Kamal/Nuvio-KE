package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeAnchoredPanel
import com.nuvio.tv.ui.components.marquee.MarqueePickerOption
import com.nuvio.tv.ui.components.marquee.MarqueePickerOptionList
import com.nuvio.tv.ui.util.localizedGenreLabel

// Library's Sort & filter panel (approved sandbox `u7-library-marquee` stage 5, Q3): F3's compact
// panel under the pill with one row per as-built filter and its current value; OK on a row lists
// its options in the same panel, Show shelves clears every choice.

private enum class LibraryFilterRow(val labelResId: Int) {
    SORT(R.string.library_filter_sort),
    GENRE(R.string.library_filter_genre),
    YEAR(R.string.library_filter_year),
    WATCHED(R.string.library_filter_watched)
}

/** The Genre and Year lists' first option (no filter). */
private const val ALL_VALUE = "__all__"

/**
 * The panel, its end under the pill's end ([anchor]: the pill's window bounds). Rows page: Sort,
 * Genre, Year, Watched with their values, then Show shelves (clears all and closes). OK on a row
 * opens its options (tick on the current one); OK selects and returns to the rows, Back returns.
 * Back on the rows page calls [onDismiss] (the caller returns focus to the pill).
 */
@Composable
internal fun LibraryFilterPanel(
    anchor: Rect,
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    var openRow by remember { mutableStateOf<LibraryFilterRow?>(null) }
    // The row the options page came from: focus returns to it.
    var lastRow by remember { mutableStateOf(LibraryFilterRow.SORT) }
    val rowRequesters = remember { LibraryFilterRow.entries.associateWith { FocusRequester() } }
    val optionRequester = remember(openRow) { FocusRequester() }
    val row = openRow

    MarqueeAnchoredPanel(
        anchor = anchor,
        onDismissRequest = { if (openRow != null) openRow = null else onDismiss() },
        alignEnd = true,
        initialFocus = if (row == null) rowRequesters.getValue(lastRow) else optionRequester
    ) {
        if (row == null) {
            PanelEyebrow(text = stringResource(R.string.library_sort_and_filter))
            LibraryFilterRow.entries.forEach { filterRow ->
                PlayerPanelRow(
                    title = stringResource(filterRow.labelResId),
                    selected = false,
                    onClick = {
                        lastRow = filterRow
                        openRow = filterRow
                    },
                    titleEnd = filterRowValue(filterRow, uiState),
                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    focusRequester = rowRequesters.getValue(filterRow)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            PanelRule()
            PlayerPanelRow(
                title = stringResource(R.string.library_filter_show_shelves),
                selected = false,
                onClick = {
                    viewModel.onShowShelves()
                    onDismiss()
                },
                dimTitle = true
            )
        } else {
            val options = filterRowOptions(row, uiState)
            val selectedValue = filterRowSelectedValue(row, uiState)
            MarqueePickerOptionList(
                title = stringResource(row.labelResId),
                options = options,
                selectedValue = selectedValue,
                focusRequester = optionRequester,
                onSelect = { value ->
                    applyFilterChoice(row, value, viewModel)
                    openRow = null
                }
            )
        }
    }
}

@Composable
private fun filterRowValue(row: LibraryFilterRow, uiState: LibraryUiState): String = when (row) {
    LibraryFilterRow.SORT -> stringResource(uiState.selectedSortOption.labelResId)
    LibraryFilterRow.GENRE -> uiState.selectedGenre?.let { localizedGenreLabel(it) } ?: stringResource(R.string.library_filter_all)
    LibraryFilterRow.YEAR -> uiState.selectedYear ?: stringResource(R.string.library_filter_all)
    LibraryFilterRow.WATCHED -> stringResource(uiState.selectedWatchedFilter.labelResId)
}

private fun filterRowSelectedValue(row: LibraryFilterRow, uiState: LibraryUiState): String = when (row) {
    LibraryFilterRow.SORT -> uiState.selectedSortOption.key
    LibraryFilterRow.GENRE -> uiState.selectedGenre ?: ALL_VALUE
    LibraryFilterRow.YEAR -> uiState.selectedYear ?: ALL_VALUE
    LibraryFilterRow.WATCHED -> uiState.selectedWatchedFilter.key
}

/** A row's options; Genre and Year list their titles' count after each one. */
@Composable
private fun filterRowOptions(row: LibraryFilterRow, uiState: LibraryUiState): List<MarqueePickerOption> {
    val all = MarqueePickerOption(stringResource(R.string.library_filter_all), ALL_VALUE)
    return when (row) {
        LibraryFilterRow.SORT -> LibrarySortOption.entries.map { MarqueePickerOption(stringResource(it.labelResId), it.key) }
        LibraryFilterRow.GENRE -> listOf(all) + uiState.availableGenres.map {
            MarqueePickerOption(localizedGenreLabel(it.label), it.key, detail = it.count.toString())
        }
        LibraryFilterRow.YEAR -> listOf(all) + uiState.availableYears.map {
            MarqueePickerOption(it.label, it.key, detail = it.count.toString())
        }
        LibraryFilterRow.WATCHED -> LibraryWatchedFilter.entries.map { MarqueePickerOption(stringResource(it.labelResId), it.key) }
    }
}

private fun applyFilterChoice(row: LibraryFilterRow, value: String, viewModel: LibraryViewModel) {
    when (row) {
        LibraryFilterRow.SORT -> LibrarySortOption.entries.firstOrNull { it.key == value }?.let(viewModel::onSelectSortOption)
        LibraryFilterRow.GENRE -> viewModel.onSelectGenre(value.takeUnless { it == ALL_VALUE })
        LibraryFilterRow.YEAR -> viewModel.onSelectYear(value.takeUnless { it == ALL_VALUE })
        LibraryFilterRow.WATCHED -> LibraryWatchedFilter.entries.firstOrNull { it.key == value }?.let(viewModel::onSelectWatchedFilter)
    }
}

/**
 * The active filter choices in the panel's order (stage 6: "Animation · Unwatched"): genre, year,
 * watched; with [withSort], a sort other than the shelves' own last.
 */
@Composable
internal fun libraryFilterChoices(uiState: LibraryUiState, withSort: Boolean): List<String> = listOfNotNull(
    uiState.selectedGenre?.let { localizedGenreLabel(it) },
    uiState.selectedYear,
    uiState.selectedWatchedFilter.takeIf { it != LibraryWatchedFilter.ALL }?.let { stringResource(it.labelResId) },
    uiState.selectedSortOption.takeIf { withSort && it != LibrarySortOption.ADDED_DESC }?.let { stringResource(it.labelResId) }
)
