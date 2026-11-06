package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview

@Immutable
data class DiscoverUiState(
    val discoverLocation: DiscoverLocation = DiscoverLocation.IN_SEARCH,
    val discoverInitialized: Boolean = false,
    val discoverLoading: Boolean = false,
    val discoverLoadingMore: Boolean = false,
    val discoverCatalogs: List<DiscoverCatalog> = emptyList(),
    val selectedDiscoverType: String = "movie",
    val selectedDiscoverCatalogKey: String? = null,
    val selectedDiscoverGenre: String? = null,
    val discoverResults: List<MetaPreview> = emptyList(),
    val pendingDiscoverResults: List<MetaPreview> = emptyList(),
    val discoverHasMore: Boolean = true,
    /** Skip for the next Discover page: the last page's CatalogRow.nextSkip (skip + received count). */
    val discoverNextSkip: Int = 0,
    val catalogTypeSuffixEnabled: Boolean = true,
    /** The Card shape setting: Landscape = true overrides every card, else each card its addon's shape (Spec 41 D1/D2). */
    val landscapePostersEnabled: Boolean = false
)

@Immutable
data class DiscoverCatalog(
    val key: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val genres: List<String>,
    val supportsSkip: Boolean,
    val skipStep: Int,
    /** Required-extra values sent when the user picked none (requiredExtraDefaults). */
    val defaultExtraArgs: Map<String, String> = emptyMap()
)
