package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.ui.util.asStable
import java.util.Locale
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX

@Immutable
internal data class ModernHomePresentationInput(
    val homeRows: List<HomeRow>,
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val upcomingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    /** The addon name after catalog row titles (B6; Search and See All honour the same setting). */
    val showAddonName: Boolean,
    val showFullReleaseDate: Boolean,
    val localeTag: String,
    /** Home's hero items, shown as one "Featured" row when there is no other row. */
    val heroItems: List<MetaPreview> = emptyList()
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
    val visibleHomeRows = resolveVisibleHomeRows(input)
    val localizedContext = getLocalizedContext(context)
    val strContinueWatching = localizedContext.getString(R.string.continue_watching)
    val strAirsDate = localizedContext.getString(R.string.cw_airs_date)
    val strUpcoming = localizedContext.getString(R.string.cw_upcoming)
    val strTypeMovie = localizedContext.getString(R.string.type_movie)
    val strTypeSeries = localizedContext.getString(R.string.type_series)

    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>()
        val activeCollectionKeys = LinkedHashSet<String>()
        val catalogRowLimit = maxCatalogRows?.coerceAtLeast(0)
        var renderedCatalogRows = 0

        cachedContinueWatchingRow(
            slot = cache.continueWatching,
            key = MODERN_CONTINUE_WATCHING_ROW_KEY,
            inputs = ContinueWatchingRowInputs(
                items = input.continueWatchingItems,
                title = strContinueWatching,
                airsDateTemplate = strAirsDate,
                upcomingLabel = strUpcoming,
                useLandscapePosters = input.useLandscapePosters
            ),
            context = localizedContext
        )?.let { add(it) }

        // Upcoming row (SPLIT_UPCOMING mode)
        cachedContinueWatchingRow(
            slot = cache.upcoming,
            key = MODERN_UPCOMING_ROW_KEY,
            inputs = ContinueWatchingRowInputs(
                items = input.upcomingItems,
                title = localizedContext.getString(R.string.upcoming_section_title),
                airsDateTemplate = strAirsDate,
                upcomingLabel = strUpcoming,
                useLandscapePosters = input.useLandscapePosters
            ),
            context = localizedContext
        )?.let { add(it) }

        visibleHomeRows.forEachIndexed { index, homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val row = homeRow.row
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val rowKey = row.key()
                    activeCatalogKeys += rowKey
                    val cached = cache.catalogRows[rowKey]
                    val currentLocaleTag = LocaleCache.localeTag
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == row &&
                            cached.useLandscapePosters == input.useLandscapePosters &&
                            cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                            cached.showAddonName == input.showAddonName &&
                            cached.localeTag == currentLocaleTag

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                        val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                        HeroCarouselRow(
                            key = rowKey,
                            title = catalogRowTitle(
                                catalogName = row.catalogName,
                                apiType = row.apiType,
                                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                                strTypeMovie = strTypeMovie,
                                strTypeSeries = strTypeSeries
                            ),
                            titleSuffix = catalogRowTitleSuffix(row.addonName, input.showAddonName),
                            globalRowIndex = index,
                            catalogId = row.catalogId,
                            addonId = row.addonId,
                            apiType = row.apiType,
                            supportsSkip = row.supportsSkip,
                            hasMore = row.hasMore,
                            isLoading = row.isLoading,
                            items = row.items.mapIndexed { itemIndex, item ->
                                val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                                rowItemOccurrenceCounts[item.id] = occurrence + 1
                                val cacheKey = "${item.id}_$occurrence"
                                val cachedItem = rowItemCache[cacheKey]
                                if (cachedItem != null &&
                                    cachedItem.source == item &&
                                    cachedItem.useLandscapePosters == input.useLandscapePosters &&
                                    cachedItem.showFullReleaseDate == input.showFullReleaseDate
                                ) {
                                    cachedItem.carouselItem.let { cached ->
                                        val stableItemKey = row.stableItemKey(itemIndex)
                                        if (cached.key == stableItemKey) cached
                                        else cached.copy(key = stableItemKey)
                                    }
                                } else {
                                    val built = buildCatalogItem(
                                        item = item,
                                        row = row,
                                        useLandscapePosters = input.useLandscapePosters,
                                        occurrence = occurrence,
                                        showFullReleaseDate = input.showFullReleaseDate,
                                        previousCachedItem = cachedItem?.carouselItem
                                    ).copy(key = row.stableItemKey(itemIndex))
                                    rowItemCache[cacheKey] = CachedCarouselItem(
                                        source = item,
                                        useLandscapePosters = input.useLandscapePosters,
                                        showFullReleaseDate = input.showFullReleaseDate,
                                        carouselItem = built
                                    )
                                    built
                                }
                            }.asStable()
                        )
                    }

                    cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                        source = row,
                        useLandscapePosters = input.useLandscapePosters,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                        showAddonName = input.showAddonName,
                        localeTag = currentLocaleTag,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.CollectionRow -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    val collection = homeRow.collection
                    val rowKey = collectionRowKey(collection)
                    activeCollectionKeys += rowKey
                    val cached = cache.collectionRows[rowKey]
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == collection

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val occurrenceCounts = mutableMapOf<String, Int>()
                        HeroCarouselRow(
                            key = rowKey,
                            title = collection.title,
                            globalRowIndex = index,
                            items = collection.folders.map { folder ->
                                val occurrence = occurrenceCounts.getOrDefault(folder.id, 0)
                                occurrenceCounts[folder.id] = occurrence + 1
                                buildCollectionFolderItem(
                                    collection = collection,
                                    folder = folder,
                                    occurrence = occurrence
                                )
                            }.asStable()
                        )
                    }

                    cache.collectionRows[rowKey] = ModernCollectionRowBuildCacheEntry(
                        source = collection,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.PlaceholderCatalog -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val stableRowKey = homeRow.stableCatalogKey
                    val fakeItemCount = 8
                    val fakeItems = (0 until fakeItemCount).map { i ->
                        val fakeId = "${PLACEHOLDER_ITEM_ID_PREFIX}${homeRow.catalogKey}_$i"
                        ModernCarouselItem(
                            key = "${stableRowKey}_$i",
                            title = "",
                            subtitle = null,
                            // Dummy URL triggers shimmer instead of MonochromePosterPlaceholder
                            imageUrl = PLACEHOLDER_IMAGE_URL,
                            heroPreview = HeroPreview(
                                title = "", logo = null, description = null,
                                episodeLabel = null, yearText = null, imdbText = null,
                                genres = com.nuvio.tv.ui.util.StableList(emptyList()), poster = null, backdrop = null,
                                imageUrl = PLACEHOLDER_IMAGE_URL
                            ),
                            payload = ModernPayload.Catalog(
                                focusKey = "placeholder_${homeRow.catalogKey}_$i",
                                itemId = fakeId,
                                itemType = homeRow.apiType,
                                addonBaseUrl = homeRow.addonBaseUrl,
                                trailerTitle = "",
                                trailerReleaseInfo = null,
                                trailerApiType = homeRow.apiType
                            )
                        )
                    }.asStable()
                    val placeholderTitle = if (input.showCatalogTypeSuffix) {
                        homeRow.displayTitle
                    } else {
                        homeRow.catalogName.replaceFirstChar { it.uppercase() }
                    }
                    val placeholderRow = HeroCarouselRow(
                        key = stableRowKey,
                        title = placeholderTitle,
                        titleSuffix = catalogRowTitleSuffix(homeRow.addonName, input.showAddonName),
                        globalRowIndex = index,
                        catalogId = homeRow.catalogId,
                        addonId = homeRow.addonId,
                        apiType = homeRow.apiType,
                        items = fakeItems,
                        isLoading = true
                    )
                    add(placeholderRow)
                }
            }
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        cache.collectionRows.keys.retainAll(activeCollectionKeys)

        if (isEmpty() && input.heroItems.isNotEmpty()) {
            add(featuredRow(input, localizedContext.getString(R.string.home_featured_row)))
        }
    }

    val lookups = buildCarouselRowLookups(rows)
    return ModernHomePresentationState(
        rows = rows.asStable(),
        lookups = lookups
    )
}

private fun resolveVisibleHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    if (input.homeRows.isNotEmpty()) {
        val latestCatalogByKey = input.catalogRows.associateBy { it.key() }
        return input.homeRows.mapNotNull { homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val latest = latestCatalogByKey[homeRow.row.key()] ?: homeRow.row
                    latest.takeIf { it.items.isNotEmpty() }?.let(HomeRow::Catalog)
                }
                is HomeRow.CollectionRow -> {
                    homeRow.collection.takeIf(Collection::hasVisibleFolders)?.let(HomeRow::CollectionRow)
                }
                is HomeRow.PlaceholderCatalog -> {
                    // Keep placeholder rows as-is — they'll be rendered as shimmer skeletons
                    homeRow
                }
            }
        }
    }

    return input.catalogRows
        .filter { it.items.isNotEmpty() }
        .map(HomeRow::Catalog)
}

/**
 * Returns the Continue Watching-style row for [inputs], reusing the slot's row while its
 * inputs are unchanged; null (and an emptied slot) when there are no items.
 */
private fun cachedContinueWatchingRow(
    slot: ContinueWatchingRowCacheSlot,
    key: String,
    inputs: ContinueWatchingRowInputs,
    context: Context
): HeroCarouselRow? {
    if (inputs.items.isEmpty()) {
        slot.inputs = null
        slot.row = null
        return null
    }
    val cachedRow = slot.row
    if (cachedRow != null && slot.inputs == inputs) return cachedRow
    val row = HeroCarouselRow(
        key = key,
        title = inputs.title,
        globalRowIndex = -1,
        items = inputs.items.map { item ->
            buildContinueWatchingItem(
                item = item,
                useLandscapePosters = inputs.useLandscapePosters,
                airsDateTemplate = inputs.airsDateTemplate,
                upcomingLabel = inputs.upcomingLabel,
                context = context
            )
        }.asStable()
    )
    slot.inputs = inputs
    slot.row = row
    return row
}

/** Hero items as one row, for a Home with nothing else to show (Marquee spec, Home states). */
private fun featuredRow(input: ModernHomePresentationInput, title: String): HeroCarouselRow {
    val source = CatalogRow(
        addonId = MODERN_FEATURED_ROW_KEY,
        addonName = "",
        addonBaseUrl = "",
        catalogId = MODERN_FEATURED_ROW_KEY,
        catalogName = title,
        type = ContentType.UNKNOWN,
        items = input.heroItems,
        hasMore = false
    )
    val occurrences = mutableMapOf<String, Int>()
    return HeroCarouselRow(
        key = MODERN_FEATURED_ROW_KEY,
        title = title,
        globalRowIndex = -1,
        items = input.heroItems.map { item ->
            val occurrence = occurrences.getOrDefault(item.id, 0)
            occurrences[item.id] = occurrence + 1
            buildCatalogItem(
                item = item,
                row = source,
                useLandscapePosters = input.useLandscapePosters,
                occurrence = occurrence,
                showFullReleaseDate = input.showFullReleaseDate
            )
        }.asStable()
    )
}

private fun collectionRowKey(collection: Collection): String {
    return "collection_${collection.id}"
}

private fun getLocalizedContext(context: Context): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return context
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

private fun Collection.hasVisibleFolders(): Boolean {
    return folders.isNotEmpty()
}

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        activeRowKeys += row.key

        row.items.list.forEach { item ->
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey.asStable(),
        rowByKey = rowByKey.asStable(),
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex.asStable(),
        activeRowKeys = activeRowKeys.asStable(),
        activeCatalogItemIds = activeCatalogItemIds.asStable()
    )
}
