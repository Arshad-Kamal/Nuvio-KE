package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.ui.components.PanelPill
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Stage 15, Custom (discover): the 18 as-built filters as text rows, emitted into the panel's
 * column. A blank row clears its filter; numbers parse as built, so an unparsable one clears too.
 * Rows with as-built quick chips show them under the row while focus is in the row or its chips.
 */
@Composable
internal fun TmdbDiscoverFilterRows(
    mediaType: TmdbCollectionMediaType,
    filters: TmdbCollectionFilters,
    onFiltersChange: (TmdbCollectionFilters) -> Unit
) {
    val us = stringResource(R.string.collections_editor_country_us) to "US"
    val uk = stringResource(R.string.collections_editor_country_uk) to "GB"
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_genres),
        value = filters.withGenres,
        placeholder = stringResource(
            if (mediaType == TmdbCollectionMediaType.MOVIE) {
                R.string.collections_editor_tmdb_genres_movie_placeholder
            } else {
                R.string.collections_editor_tmdb_genres_tv_placeholder
            }
        ),
        chips = tmdbGenreQuickChips(mediaType)
    ) { onFiltersChange(filters.copy(withGenres = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_without_genres),
        value = filters.withoutGenres,
        placeholder = stringResource(R.string.collections_editor_tmdb_without_genres_placeholder)
    ) { onFiltersChange(filters.copy(withoutGenres = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_date_from),
        value = filters.releaseDateGte,
        placeholder = stringResource(R.string.collections_editor_tmdb_date_from_placeholder)
    ) { onFiltersChange(filters.copy(releaseDateGte = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_date_to),
        value = filters.releaseDateLte,
        placeholder = stringResource(R.string.collections_editor_tmdb_date_to_placeholder)
    ) { onFiltersChange(filters.copy(releaseDateLte = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_rating_min),
        value = filters.voteAverageGte?.toString(),
        placeholder = stringResource(R.string.collections_editor_tmdb_rating_min_placeholder),
        keyboardType = KeyboardType.Decimal
    ) { onFiltersChange(filters.copy(voteAverageGte = it.toDoubleOrNull())) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_rating_max),
        value = filters.voteAverageLte?.toString(),
        placeholder = stringResource(R.string.collections_editor_tmdb_rating_max_placeholder),
        keyboardType = KeyboardType.Decimal
    ) { onFiltersChange(filters.copy(voteAverageLte = it.toDoubleOrNull())) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_votes_min),
        value = filters.voteCountGte?.toString(),
        placeholder = stringResource(R.string.collections_editor_tmdb_votes_min_placeholder),
        keyboardType = KeyboardType.Number
    ) { onFiltersChange(filters.copy(voteCountGte = it.toIntOrNull())) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_language),
        value = filters.withOriginalLanguage,
        placeholder = stringResource(R.string.collections_editor_tmdb_language_placeholder),
        chips = listOf(
            stringResource(R.string.collections_editor_language_english) to "en",
            stringResource(R.string.collections_editor_language_korean) to "ko",
            stringResource(R.string.collections_editor_language_japanese) to "ja",
            stringResource(R.string.collections_editor_language_hindi) to "hi",
            stringResource(R.string.collections_editor_language_spanish) to "es"
        )
    ) { onFiltersChange(filters.copy(withOriginalLanguage = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_country),
        value = filters.withOriginCountry,
        placeholder = stringResource(R.string.collections_editor_tmdb_country_placeholder),
        chips = listOf(
            us,
            stringResource(R.string.collections_editor_country_korea) to "KR",
            stringResource(R.string.collections_editor_country_japan) to "JP",
            stringResource(R.string.collections_editor_country_india) to "IN",
            uk
        )
    ) { onFiltersChange(filters.copy(withOriginCountry = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_keywords),
        value = filters.withKeywords,
        placeholder = stringResource(R.string.collections_editor_tmdb_keywords_placeholder),
        chips = listOf(
            stringResource(R.string.collections_editor_keyword_superhero) to "9715",
            stringResource(R.string.collections_editor_keyword_based_on_novel) to "818",
            stringResource(R.string.collections_editor_keyword_time_travel) to "4379",
            stringResource(R.string.collections_editor_keyword_space) to "9882"
        )
    ) { onFiltersChange(filters.copy(withKeywords = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_without_keywords),
        value = filters.withoutKeywords,
        placeholder = stringResource(R.string.collections_editor_tmdb_without_keywords_placeholder)
    ) { onFiltersChange(filters.copy(withoutKeywords = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_companies),
        value = filters.withCompanies,
        placeholder = stringResource(R.string.collections_editor_tmdb_companies_placeholder),
        chips = listOf("Marvel" to "420", "Disney" to "2", "Pixar" to "3", "Lucasfilm" to "1", "Warner Bros." to "174")
    ) { onFiltersChange(filters.copy(withCompanies = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_without_companies),
        value = filters.withoutCompanies,
        placeholder = stringResource(R.string.collections_editor_tmdb_without_companies_placeholder)
    ) { onFiltersChange(filters.copy(withoutCompanies = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_networks),
        value = filters.withNetworks,
        placeholder = stringResource(R.string.collections_editor_tmdb_networks_placeholder),
        chips = listOf("Netflix" to "213", "HBO" to "49", "Disney+" to "2739", "Prime Video" to "1024", "Hulu" to "453")
    ) { onFiltersChange(filters.copy(withNetworks = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_year),
        value = filters.year?.toString(),
        placeholder = stringResource(R.string.collections_editor_tmdb_year_placeholder),
        keyboardType = KeyboardType.Number
    ) { onFiltersChange(filters.copy(year = it.toIntOrNull())) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_watch_providers),
        value = filters.withWatchProviders,
        placeholder = stringResource(R.string.collections_editor_tmdb_watch_providers_placeholder),
        chips = listOf("Netflix" to "8", "Prime Video" to "119", "Disney+" to "337", "Apple TV+" to "350", "Hulu" to "15")
    ) { onFiltersChange(filters.copy(withWatchProviders = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_without_watch_providers),
        value = filters.withoutWatchProviders,
        placeholder = stringResource(R.string.collections_editor_tmdb_without_watch_providers_placeholder)
    ) { onFiltersChange(filters.copy(withoutWatchProviders = it.ifBlank { null })) }
    TmdbFilterRow(
        label = stringResource(R.string.collections_editor_tmdb_watch_region),
        value = filters.watchRegion,
        placeholder = "US",
        chips = listOf(us, uk, "Canada" to "CA", "Australia" to "AU", "Germany" to "DE")
    ) { onFiltersChange(filters.copy(watchRegion = it.ifBlank { null })) }
}

/**
 * One filter: a [CollectionTextRow] on [value] (null shows [placeholder]); [onValue] gets the
 * edited text or a picked chip's value. [chips] (label to value) show as pills under the row
 * while focus is within; the chip whose value is the current one is selected.
 */
@Composable
private fun TmdbFilterRow(
    label: String,
    value: String?,
    placeholder: String,
    chips: List<Pair<String, String>> = emptyList(),
    keyboardType: KeyboardType = KeyboardType.Text,
    onValue: (String) -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    var focusWithin by remember { mutableStateOf(false) }
    val text = value.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusWithin = it.hasFocus }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
    ) {
        CollectionTextRow(
            label = label,
            value = text,
            onValueChange = onValue,
            display = text,
            placeholder = placeholder,
            keyboardType = keyboardType
        )
        if (focusWithin && chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(start = tokens.rowPaddingHorizontal),
                horizontalArrangement = Arrangement.spacedBy(tokens.actionGap),
                verticalArrangement = Arrangement.spacedBy(tokens.actionGap)
            ) {
                chips.forEach { (chipLabel, chipValue) ->
                    PanelPill(
                        text = chipLabel,
                        selected = chipValue == value,
                        enabled = true,
                        horizontalPadding = NuvioTheme.marquee.playerPanel.segmentPaddingHorizontal,
                        onClick = { onValue(chipValue) }
                    )
                }
            }
        }
    }
}

@Composable
private fun tmdbGenreQuickChips(mediaType: TmdbCollectionMediaType): List<Pair<String, String>> {
    return when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> listOf(
            stringResource(R.string.collections_editor_tmdb_genre_action) to "28",
            stringResource(R.string.collections_editor_tmdb_genre_adventure) to "12",
            stringResource(R.string.collections_editor_tmdb_genre_animation) to "16",
            stringResource(R.string.collections_editor_tmdb_genre_comedy) to "35",
            stringResource(R.string.collections_editor_tmdb_genre_horror) to "27",
            stringResource(R.string.collections_editor_tmdb_genre_scifi) to "878"
        )
        TmdbCollectionMediaType.TV -> listOf(
            stringResource(R.string.collections_editor_tmdb_genre_drama) to "18",
            stringResource(R.string.collections_editor_tmdb_genre_comedy) to "35",
            stringResource(R.string.collections_editor_tmdb_genre_animation) to "16",
            stringResource(R.string.collections_editor_tmdb_genre_crime) to "80",
            stringResource(R.string.collections_editor_tmdb_genre_scifi) to "10765",
            stringResource(R.string.collections_editor_tmdb_genre_reality) to "10764"
        )
    }
}
