package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/*
 * What Continue Watching, Detail and the player read while a tracker is the source (Spec 104 S2b). The tracker decides
 * which titles are there; local data (always written, never cleared: S2-R1) adds two things:
 * - an entry under the same key that is newer than the tracker's: the play on this TV the tracker has not caught up
 *   with yet, with the exact position;
 * - the titles, or single episodes, the tracker cannot hold ([TrackingProgressProvider.retainsLocalEpisode]).
 * Each merge takes that check per local entry.
 * A title only local otherwise stays hidden: it was removed on the tracker, or watched before connecting it.
 * Built on upstream NuvioTV's `core/tracking/TrackingProgressProjection.kt` (newer-local rule added).
 */

/** One entry: the newer of the two under the same key; a local-only one only when [retained]. */
internal fun projectProgressEntry(provider: WatchProgress?, local: WatchProgress?, retained: Boolean): WatchProgress? =
    if (provider == null) local?.takeIf { retained } else newerOf(provider, local)

private fun newerOf(provider: WatchProgress, local: WatchProgress?): WatchProgress =
    if (local != null && local.lastWatched > provider.lastWatched) local else provider

/** Both lists hold one entry per title (its latest), so they meet by title: a newer local episode wins too. */
internal fun mergeProgressProjection(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocal: (WatchProgress) -> Boolean
): List<WatchProgress> {
    val localByTitle = localEntries.associateBy(WatchProgress::contentId)
    val providerTitles = providerEntries.mapTo(mutableSetOf(), WatchProgress::contentId)
    return buildList {
        providerEntries.forEach { entry ->
            add(newerOf(entry, localByTitle[entry.contentId]))
        }
        localEntries.forEach { entry ->
            if (entry.contentId !in providerTitles && retainsLocal(entry)) add(entry)
        }
    }.sortedByDescending(WatchProgress::lastWatched)
}

/** Episode progress of one show, keyed by (season, episode), under the same rule. */
internal fun mergeEpisodeProgressProjection(
    providerEpisodes: Map<Pair<Int, Int>, WatchProgress>,
    localEpisodes: Map<Pair<Int, Int>, WatchProgress>,
    retained: (WatchProgress) -> Boolean
): Map<Pair<Int, Int>, WatchProgress> = (providerEpisodes.keys + localEpisodes.keys)
    .mapNotNull { key ->
        val local = localEpisodes[key]
        projectProgressEntry(providerEpisodes[key], local, local != null && retained(local))?.let { key to it }
    }
    .toMap()

/**
 * Up next seeds: one per show. A retained local seed counts beside the tracker's, and the newer of the two wins (a play
 * the tracker could not receive is the household's latest episode).
 */
internal fun mergeNextUpSeedProjection(
    providerSeeds: List<WatchProgress>,
    localSeeds: List<WatchProgress>,
    retainsLocal: (WatchProgress) -> Boolean
): List<WatchProgress> {
    val byShow = LinkedHashMap<String, WatchProgress>()
    providerSeeds.forEach { byShow.putIfAbsent(it.contentId, it) }
    localSeeds.filter(retainsLocal).forEach { local ->
        val provider = byShow[local.contentId]
        if (provider == null || local.lastWatched > provider.lastWatched) byShow[local.contentId] = local
    }
    return byShow.values.sortedByDescending(WatchProgress::lastWatched)
}

/** Watched marks: the tracker's, plus the local marks of titles it cannot hold. */
internal fun mergeWatchedItemProjection(
    providerItems: List<WatchedItem>,
    localItems: List<WatchedItem>,
    retainsLocal: (WatchedItem) -> Boolean
): List<WatchedItem> {
    val providerKeys = providerItems.mapTo(mutableSetOf()) { Triple(it.contentId, it.season, it.episode) }
    return providerItems + localItems.filter { item ->
        Triple(item.contentId, item.season, item.episode) !in providerKeys && retainsLocal(item)
    }
}

/** Watched episodes per show, from the projected marks. */
internal fun List<WatchedItem>.watchedEpisodesByShow(): Map<String, Set<Pair<Int, Int>>> =
    mapNotNull { item -> item.season?.let { season -> item.episode?.let { episode -> item.contentId to (season to episode) } } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, episodes) -> episodes.toSet() }
