package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fakes shared by DiscoverViewModelTest and SearchViewModelDiscoverTest. A non-null [addonsGate]
 * holds every installed-addons read until it completes.
 */
internal class SingleAddonRepository(private val addon: Addon) : AddonRepository {
    var addonsGate: CompletableDeferred<Unit>? = null

    override fun getInstalledAddons(): Flow<List<Addon>> = flow {
        addonsGate?.await()
        emit(listOf(addon))
    }
    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
    override suspend fun addAddon(url: String) = error("unused")
    override suspend fun removeAddon(url: String) = error("unused")
    override suspend fun spliceAddonOrder(urls: List<String>, removedUrls: Collection<String>) = error("unused")
    override suspend fun moveAddon(url: String, direction: Int) = error("unused")
    override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
}

internal data class DiscoverRequest(val catalogId: String, val skip: Int, val extraArgs: Map<String, String>)

/**
 * Serves [pageSize] items per request and reports nextSkip the way CatalogRepositoryImpl does
 * (skip + received item count). A non-null [gate] holds every response until it completes; items
 * in [unreleasedIds] carry a future release date.
 */
internal class RecordingCatalogRepository(
    private val pageSize: Int,
    private val unreleasedIds: Set<String> = emptySet()
) : CatalogRepository {
    val requests = mutableListOf<DiscoverRequest>()
    var gate: CompletableDeferred<Unit>? = null

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        requests += DiscoverRequest(catalogId, skip, extraArgs)
        emit(NetworkResult.Loading)
        gate?.await()
        emit(
            NetworkResult.Success(
                CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.MOVIE,
                    items = (skip until skip + pageSize).map { meta("m$it") },
                    hasMore = supportsSkip,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip) skip + pageSize else skip,
                    extraArgs = extraArgs
                )
            )
        )
    }

    override suspend fun clearCaches() = Unit

    private fun meta(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = if (id in unreleasedIds) "2099-01-01" else null,
        imdbRating = null,
        genres = emptyList()
    )
}

internal fun testCatalog(
    id: String,
    extra: List<CatalogExtra>,
    type: ContentType = ContentType.MOVIE
) = CatalogDescriptor(
    type = type,
    id = id,
    name = id,
    extra = extra
)

internal fun testAddon(vararg catalogs: CatalogDescriptor) = Addon(
    id = "addon",
    name = "Addon",
    version = "1",
    description = null,
    logo = null,
    baseUrl = "https://example.test",
    catalogs = catalogs.toList(),
    types = catalogs.map { it.type }.distinct(),
    resources = emptyList()
)
