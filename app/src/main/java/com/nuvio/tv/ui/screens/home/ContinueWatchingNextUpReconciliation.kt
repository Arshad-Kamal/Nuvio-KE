package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ExpiringUrlBlacklist
import com.nuvio.tv.ui.components.brokenImageUrls
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal fun reconcileConclusiveNextUpItems(
    currentItems: List<ContinueWatchingItem>,
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>
): List<ContinueWatchingItem> {
    val resolvedByContentId = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .associateBy { item -> item.info.contentId }
    val inProgressContentIds = currentItems.mapNotNullTo(mutableSetOf()) { item ->
        (item as? ContinueWatchingItem.InProgress)?.progress?.contentId
    }
    val retainedItems = currentItems.filterNot { item ->
        item is ContinueWatchingItem.NextUp &&
            item.info.contentId in conclusivelyProcessedContentIds
    }
    val acceptedResolvedItems = resolvedByContentId.values.filter { item ->
        item.info.contentId !in inProgressContentIds &&
            item.info.contentId in conclusivelyProcessedContentIds &&
            nextUpDismissKey(
                item.info.contentId,
                item.info.seedSeason,
                item.info.seedEpisode
            ) !in dismissedNextUpKeys
    }
    return retainedItems + acceptedResolvedItems
}

internal fun mergeConcurrentContinueWatchingEnrichment(
    currentItems: List<ContinueWatchingItem>,
    originalItems: List<ContinueWatchingItem>,
    enrichedItems: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    val replacements = originalItems.zip(enrichedItems).associate { (original, enriched) ->
        original.reconciliationKey() to enriched
    }
    return currentItems.map { current ->
        replacements[current.reconciliationKey()] ?: current
    }
}

/**
 * In-progress rows whose progress enrichment changed, each paired with its original by
 * reconciliation key (content, episode, video), not by list position, so a re-sorted
 * [enrichedItems] still pairs correctly. Enriched rows with no original are skipped.
 */
internal fun changedInProgressMetadata(
    originalItems: List<ContinueWatchingItem>,
    enrichedItems: List<ContinueWatchingItem>
): List<WatchProgress> {
    val originalProgressByKey = originalItems
        .filterIsInstance<ContinueWatchingItem.InProgress>()
        .associate { item -> item.reconciliationKey() to item.progress }
    return enrichedItems.mapNotNull { enriched ->
        val enrichedProgress = (enriched as? ContinueWatchingItem.InProgress)?.progress
            ?: return@mapNotNull null
        val originalProgress = originalProgressByKey[enriched.reconciliationKey()]
            ?: return@mapNotNull null
        enrichedProgress.takeIf { it != originalProgress }
    }
}

internal suspend fun HomeViewModel.applyConclusiveOlderNextUpResults(
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>,
    sortMode: ContinueWatchingSortMode,
    pipelineProfileId: Int,
    persistSnapshot: Boolean
) {
    if (resolvedItems.isEmpty() && conclusivelyProcessedContentIds.isEmpty()) return

    val acceptedResolvedItems = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .filter { item ->
            item.info.contentId in conclusivelyProcessedContentIds &&
                nextUpDismissKey(
                    item.info.contentId,
                    item.info.seedSeason,
                    item.info.seedEpisode
                ) !in dismissedNextUpKeys
        }
    val reconciledContentIds = conclusivelyProcessedContentIds

    synchronized(discoveredOlderNextUpItems) {
        discoveredOlderNextUpItems.removeAll { item ->
            item.info.contentId in reconciledContentIds
        }
        discoveredOlderNextUpItems.addAll(acceptedResolvedItems)
    }
    reconciledContentIds.forEach(cwEnrichedNextUpOverlay::remove)

    _uiState.update { state ->
        val reconciled = reconcileConclusiveNextUpItems(
            currentItems = state.continueWatchingItems + state.upcomingItems,
            resolvedItems = acceptedResolvedItems,
            conclusivelyProcessedContentIds = conclusivelyProcessedContentIds,
            dismissedNextUpKeys = dismissedNextUpKeys
        )
        val sorted = sortContinueWatchingItems(reconciled, sortMode)
        val (main, upcoming) = splitUpcomingItems(sorted, sortMode)
        if (state.continueWatchingItems == main && state.upcomingItems == upcoming) {
            state
        } else {
            state.copy(continueWatchingItems = main, upcomingItems = upcoming)
        }
    }

    if (!persistSnapshot) return
    withContext(Dispatchers.IO) {
        if (profileManager.activeProfileId.value != pipelineProfileId) return@withContext
        val snapshot = (_uiState.value.continueWatchingItems + _uiState.value.upcomingItems)
            .toCachedNextUpSnapshot(brokenImageUrls)
        runCatching {
            cwEnrichmentCache.saveNextUpSnapshot(snapshot, force = true)
        }
    }
}

internal fun List<ContinueWatchingItem>.toCachedNextUpSnapshot(
    brokenImageUrls: ExpiringUrlBlacklist
): List<CachedNextUpItem> = mapNotNull { item ->
    val info = (item as? ContinueWatchingItem.NextUp)?.info ?: return@mapNotNull null
    CachedNextUpItem(
        contentId = info.contentId,
        contentType = info.contentType,
        name = info.name,
        poster = info.poster,
        backdrop = info.backdrop,
        logo = info.logo,
        videoId = info.videoId,
        season = info.season,
        episode = info.episode,
        episodeTitle = info.episodeTitle,
        episodeDescription = info.episodeDescription,
        thumbnail = info.thumbnail?.takeIf { imageUrl -> imageUrl !in brokenImageUrls },
        released = info.released,
        hasAired = info.hasAired,
        airDateLabel = info.airDateLabel,
        lastWatched = info.lastWatched,
        imdbRating = info.imdbRating,
        genres = info.genres,
        releaseInfo = info.releaseInfo,
        sortTimestamp = info.sortTimestamp,
        releaseTimestamp = info.releaseTimestamp,
        isReleaseAlert = info.isReleaseAlert,
        isNewSeasonRelease = info.isNewSeasonRelease,
        seedSeason = info.seedSeason,
        seedEpisode = info.seedEpisode,
        contentLanguage = info.contentLanguage
    )
}

/** In-progress rows of a CW list as cached rows; the inverse of toInProgressItem. */
internal fun List<ContinueWatchingItem>.toCachedInProgressSnapshot(
    brokenImageUrls: ExpiringUrlBlacklist
): List<CachedInProgressItem> = mapNotNull { item ->
    val inProgress = item as? ContinueWatchingItem.InProgress ?: return@mapNotNull null
    val progress = inProgress.progress
    CachedInProgressItem(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = progress.name,
        poster = progress.poster,
        backdrop = progress.backdrop,
        logo = progress.logo,
        videoId = progress.videoId,
        season = progress.season,
        episode = progress.episode,
        episodeTitle = progress.episodeTitle,
        position = progress.position,
        duration = progress.duration,
        lastWatched = progress.lastWatched,
        progressPercent = progress.progressPercent,
        episodeThumbnail = inProgress.episodeThumbnail?.takeIf { imageUrl -> imageUrl !in brokenImageUrls },
        episodeDescription = inProgress.episodeDescription,
        episodeImdbRating = inProgress.episodeImdbRating,
        genres = inProgress.genres,
        releaseInfo = inProgress.releaseInfo,
        contentLanguage = inProgress.contentLanguage
    )
}

private fun ContinueWatchingItem.reconciliationKey(): String = when (this) {
    is ContinueWatchingItem.InProgress -> listOf(
        "progress",
        progress.contentId,
        progress.season,
        progress.episode,
        progress.videoId
    ).joinToString("|")

    is ContinueWatchingItem.NextUp -> listOf(
        "next",
        info.contentId,
        info.season,
        info.episode,
        info.seedSeason,
        info.seedEpisode
    ).joinToString("|")
}
