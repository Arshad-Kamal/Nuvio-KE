package com.nuvio.tv.core.server

import java.util.UUID
import kotlinx.serialization.Serializable

enum class AddonWebConfigMode(
    val allowAddonManagement: Boolean,
    val allowCatalogManagement: Boolean,
    val allowCollectionManagement: Boolean
) {
    FULL(
        allowAddonManagement = true,
        allowCatalogManagement = true,
        allowCollectionManagement = true
    ),
    COLLECTIONS_ONLY(
        allowAddonManagement = false,
        allowCatalogManagement = false,
        allowCollectionManagement = true
    )
}

@Serializable
data class AddonInfo(
    val url: String,
    val name: String,
    val description: String?
)

@Serializable
data class CatalogInfo(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val type: String,
    val isDisabled: Boolean
)

@Serializable
data class CollectionInfo(
    val id: String,
    val title: String,
    val backdropImageUrl: String? = null,
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    val viewMode: String = "TABBED_GRID",
    val showAllTab: Boolean = true,
    val folders: List<FolderInfo>
)

@Serializable
data class FolderInfo(
    val id: String,
    val title: String,
    val coverImageUrl: String?,
    val focusGifUrl: String?,
    val focusGifEnabled: Boolean = true,
    val coverEmoji: String?,
    val tileShape: String,
    val hideTitle: Boolean,
    val heroBackdropUrl: String? = null,
    val heroVideoUrl: String? = null,
    val titleLogoUrl: String? = null,
    val catalogSources: List<CatalogSourceInfo> = emptyList(),
    val sources: List<CollectionSourceInfo> = catalogSources.map { it.toCollectionSourceInfo() }
)

@Serializable
data class CatalogSourceInfo(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
)

@Serializable
data class CollectionSourceInfo(
    val provider: String = "addon",
    val addonId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    val tmdbSourceType: String? = null,
    val title: String? = null,
    val tmdbId: Int? = null,
    val traktListId: Long? = null,
    val mediaType: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val filters: TmdbFiltersInfo? = null
)

@Serializable
data class TmdbFiltersInfo(
    val withGenres: String? = null,
    val releaseDateGte: String? = null,
    val releaseDateLte: String? = null,
    val voteAverageGte: Double? = null,
    val voteAverageLte: Double? = null,
    val voteCountGte: Int? = null,
    val withOriginalLanguage: String? = null,
    val withOriginCountry: String? = null,
    val withKeywords: String? = null,
    val withCompanies: String? = null,
    val withNetworks: String? = null,
    val year: Int? = null,
    val watchRegion: String? = null,
    val withWatchProviders: String? = null
)

data class TmdbSourceMetadataRequest(
    val sourceType: String,
    val tmdbId: Int
)

@Serializable
data class TmdbSourceMetadataInfo(
    val title: String? = null,
    val coverImageUrl: String? = null
)

data class TmdbSourceSearchRequest(
    val sourceType: String,
    val query: String
)

@Serializable
data class TmdbSourceSearchResultInfo(
    val id: Int,
    val title: String,
    val subtitle: String? = null,
    val coverImageUrl: String? = null
)

@Serializable
data class PageState(
    val addons: List<AddonInfo>,
    val catalogs: List<CatalogInfo>,
    /** Addon-catalog keys in manifest order — the web client's true manifest
     *  reference for its follow-addons toggle (mirrors HomeCatalogOrdering). */
    val manifestAddonOrderKeys: List<String> = emptyList(),
    val collections: List<CollectionInfo> = emptyList(),
    val disabledCollectionKeys: List<String> = emptyList(),
    val followAddonsOrder: Boolean = false
)

data class PendingAddonChange(
    val id: String = UUID.randomUUID().toString(),
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val proposedCollectionsJson: String? = null,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    val proposedFollowAddonsOrder: Boolean? = null,
    // Written from the main thread (confirm/reject) and NanoHTTPD worker
    // threads (reject-then-insert), read from poll requests: needs visibility.
    @Volatile var status: AddonChangeStatus = AddonChangeStatus.PENDING
)

enum class AddonChangeStatus { PENDING, CONFIRMED, REJECTED }

private fun CatalogSourceInfo.toCollectionSourceInfo() = CollectionSourceInfo(
    provider = "addon",
    addonId = addonId,
    type = type,
    catalogId = catalogId,
    genre = genre
)
