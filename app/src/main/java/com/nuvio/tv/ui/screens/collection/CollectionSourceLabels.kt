package com.nuvio.tv.ui.screens.collection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource

fun collectionSourceKey(source: CollectionSource): String {
    return when (source) {
        is AddonCatalogCollectionSource -> "addon_${source.addonId}_${source.type}_${source.catalogId}_${source.genre.orEmpty()}"
        is TmdbCollectionSource -> "tmdb_${source.sourceType}_${source.tmdbId}_${source.mediaType}_${source.sortBy}_${source.filters.hashCode()}_${source.title}"
        is TraktCollectionSource -> "trakt_${source.traktListId}_${source.mediaType}_${source.sortBy}_${source.sortHow}_${source.title}"
    }
}

@Composable
fun tmdbSourceSubtitle(source: TmdbCollectionSource): String {
    val media = when (source.mediaType) {
        TmdbCollectionMediaType.MOVIE -> stringResource(R.string.type_movies)
        TmdbCollectionMediaType.TV -> stringResource(R.string.type_series_plural)
    }
    val sort = when (source.sortBy) {
        TmdbCollectionSort.ORIGINAL.value -> stringResource(R.string.collections_editor_sort_original)
        TmdbCollectionSort.POPULAR_DESC.value -> stringResource(R.string.tmdb_entity_rail_popular)
        TmdbCollectionSort.VOTE_AVERAGE_DESC.value -> stringResource(R.string.tmdb_entity_rail_top_rated)
        TmdbCollectionSort.VOTE_COUNT_DESC.value -> stringResource(R.string.tmdb_entity_rail_most_voted)
        TmdbCollectionSort.RELEASE_DATE_DESC.value,
        TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> stringResource(R.string.tmdb_entity_rail_recent)
        else -> source.sortBy
    }
    return when (source.sourceType) {
        TmdbCollectionSourceType.LIST -> stringResource(R.string.collections_editor_tmdb_default_list)
        TmdbCollectionSourceType.COLLECTION -> listOf(stringResource(R.string.collections_editor_tmdb_movie_collection), sort).joinToString(" · ")
        TmdbCollectionSourceType.COMPANY -> listOf(stringResource(R.string.collections_editor_tmdb_mode_production), media, sort).joinToString(" · ")
        TmdbCollectionSourceType.NETWORK -> listOf(stringResource(R.string.collections_editor_tmdb_mode_network), stringResource(R.string.type_series_plural), sort).joinToString(" · ")
        TmdbCollectionSourceType.PERSON -> listOf(stringResource(R.string.collections_editor_tmdb_person_credits), media, sort).joinToString(" · ")
        TmdbCollectionSourceType.DIRECTOR -> listOf(stringResource(R.string.collections_editor_tmdb_director_credits), media, sort).joinToString(" · ")
        TmdbCollectionSourceType.DISCOVER -> listOf(stringResource(R.string.collections_editor_tmdb_default_discover), media, sort).joinToString(" · ")
    }
}
