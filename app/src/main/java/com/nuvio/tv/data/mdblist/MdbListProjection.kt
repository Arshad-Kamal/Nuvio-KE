package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.watchProgressKey

/**
 * What Continue Watching, Up next and the watched marks read from a [MdbListSnapshot] (Spec 105 S2a), built once per
 * snapshot. Titles are named by the id Nuvio's catalogs use: IMDb, else TMDB (a title with neither is left out).
 * Episode numbers are the snapshot's: the provider moves a `tt` show's rows into the addon's numbering first (S6). Posters stay empty: Continue Watching fills
 * artwork from the catalog as it does for local entries.
 */
data class MdbListProjected(
    /** Paused playbacks, newest first, one per title. */
    val progress: List<WatchProgress> = emptyList(),
    /** Per show: its latest watched regular episode. */
    val nextUpSeeds: List<WatchProgress> = emptyList(),
    val watchedItems: List<WatchedItem> = emptyList(),
    val watchedMovieIds: Set<String> = emptySet(),
    /** Per title: its playbacks and watched episodes by (season, episode). */
    val episodeProgress: Map<String, Map<Pair<Int, Int>, WatchProgress>> = emptyMap()
) {
    /** [watchedItems] by `watchProgressKey`, for the per-title watched checks. */
    val watchedKeys: Set<String> by lazy { watchedItems.mapTo(HashSet()) { watchProgressKey(it.contentId, it.season, it.episode) } }
}

/** MDBList's own completion mark: a pause at or past it is a watch (its scrobble docs: ">= 80% marks watched"). */
internal const val MDBLIST_WATCHED_PERCENT = 80.0

fun projectMdbList(snapshot: MdbListSnapshot?): MdbListProjected {
    if (snapshot == null) return MdbListProjected()
    val watchedItems = snapshot.marks.mapNotNull(MdbListWatchedMark::toWatchedItem)
        .distinctBy { watchProgressKey(it.contentId, it.season, it.episode) }
    val watchedAtByKey = watchedItems.associate { watchProgressKey(it.contentId, it.season, it.episode) to it.watchedAt }

    // A pause at or before the title's (or episode's) watch was overtaken by it; a later one is a rewatch and stays.
    val visiblePlayback = snapshot.playback.mapNotNull(MdbListPlayback::toWatchProgress).filter { entry ->
        val watchedAt = watchedAtByKey[watchProgressKey(entry.contentId, entry.season, entry.episode)]
        watchedAt == null || entry.lastWatched > watchedAt
    }
    val progress = visiblePlayback.sortedByDescending(WatchProgress::lastWatched).distinctBy(WatchProgress::contentId)

    val watchedMovieIds = snapshot.marks.filterNot(MdbListWatchedMark::isEpisode)
        .flatMapTo(HashSet()) { mark -> listOfNotNull(mark.media.imdb, mark.media.tmdb?.let { "tmdb:$it" }) }

    val nextUpSeeds = watchedItems.filter { it.season != null && it.episode != null && it.season > 0 }
        .groupBy(WatchedItem::contentId)
        .mapNotNull { (_, episodes) ->
            episodes.maxWithOrNull(compareBy<WatchedItem>({ it.watchedAt }, { it.season }, { it.episode }))?.toFinishedProgress()
        }
        .sortedByDescending(WatchProgress::lastWatched)

    val episodeProgress = buildMap<String, MutableMap<Pair<Int, Int>, WatchProgress>> {
        watchedItems.forEach { item ->
            val season = item.season ?: return@forEach
            val episode = item.episode ?: return@forEach
            getOrPut(item.contentId) { mutableMapOf() }[season to episode] = item.toFinishedProgress()
        }
        visiblePlayback.forEach { entry ->
            val key = (entry.season ?: return@forEach) to (entry.episode ?: return@forEach)
            val episodes = getOrPut(entry.contentId) { mutableMapOf() }
            if ((episodes[key]?.lastWatched ?: Long.MIN_VALUE) <= entry.lastWatched) episodes[key] = entry
        }
    }
    return MdbListProjected(progress, nextUpSeeds, watchedItems, watchedMovieIds, episodeProgress)
}

/**
 * True for a content id MDBList's sync cannot name (neither IMDb nor TMDB): its local progress and marks stay visible
 * while MDBList is the source, since the projection never shows such a title.
 */
fun isMdbListUnmatchable(contentId: String): Boolean {
    val ids = parseTrackingExternalIds(contentId)
    return ids.imdb == null && ids.tmdb == null
}

internal fun MdbListPlayback.toWatchProgress(): WatchProgress? {
    val media = media ?: return null
    val contentId = media.contentId ?: return null
    val percent = progress?.takeIf { it.isFinite() && it >= 0.0 && it < MDBLIST_WATCHED_PERCENT } ?: return null
    val season = if (isEpisode) episode?.season?.takeIf { it >= 0 } ?: return null else null
    val number = if (isEpisode) episode?.number?.takeIf { it > 0 } ?: return null else null
    return WatchProgress(
        contentId = contentId,
        contentType = if (isEpisode) "series" else "movie",
        name = media.title.orEmpty(),
        poster = null,
        backdrop = null,
        logo = null,
        videoId = if (isEpisode) "$contentId:$season:$number" else contentId,
        season = season,
        episode = number,
        episodeTitle = episode?.name ?: episode?.title,
        position = 0L,
        duration = 0L,
        lastWatched = parseMdbListTime(pausedAt) ?: parseMdbListTime(updatedAt) ?: 0L,
        progressPercent = percent.toFloat(),
        mdbListPlaybackId = id,
        trackingProviderId = TrackingProviderId.MDBLIST.storageId,
        trackingProviderItemId = media.id("mdblist")
    )
}

private fun MdbListWatchedMark.toWatchedItem(): WatchedItem? {
    val contentId = media.contentId ?: return null
    return WatchedItem(
        contentId = contentId,
        contentType = if (isEpisode) "series" else "movie",
        title = media.title.orEmpty(),
        season = season,
        episode = episode,
        watchedAt = parseMdbListTime(watchedAt) ?: 0L,
        trackingProviderId = TrackingProviderId.MDBLIST.storageId,
        trackingProviderItemId = media.id("mdblist")
    )
}

private fun WatchedItem.toFinishedProgress(): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = contentType,
    name = title,
    poster = null,
    backdrop = null,
    logo = null,
    videoId = "$contentId:$season:$episode",
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 0L,
    duration = 0L,
    lastWatched = watchedAt,
    progressPercent = 100f,
    trackingProviderId = trackingProviderId,
    trackingProviderItemId = trackingProviderItemId
)
