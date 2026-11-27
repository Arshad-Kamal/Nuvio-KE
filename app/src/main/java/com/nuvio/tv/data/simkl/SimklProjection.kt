package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.watchProgressKey

/**
 * What Continue Watching, Up next and the watched marks read from a [SimklSnapshot] (Spec 106 S2 plan), built once per
 * snapshot. Titles are named by the id Nuvio's catalogs use: IMDb, else TMDB, else Kitsu, MAL, Simkl; Simkl's anime by
 * the profile's [SimklAnimeSeasons] (S5). Posters stay empty: Continue Watching fills artwork from the catalog as it does for local
 * entries.
 */
data class SimklProjected(
    /** Paused playbacks, newest first, one per title. */
    val progress: List<WatchProgress> = emptyList(),
    val nextUpSeeds: List<WatchProgress> = emptyList(),
    val watchedItems: List<WatchedItem> = emptyList(),
    val watchedMovieIds: Set<String> = emptySet(),
    /** Per title: its playbacks and watched episodes by (season, episode). */
    val episodeProgress: Map<String, Map<Pair<Int, Int>, WatchProgress>> = emptyMap()
) {
    /** [watchedItems] by `watchProgressKey`, for the per-title watched checks. */
    val watchedKeys: Set<String> by lazy { watchedItems.mapTo(HashSet()) { watchProgressKey(it.contentId, it.season, it.episode) } }
}

fun projectSimkl(snapshot: SimklSnapshot?, anime: SimklAnimeSeasons = SimklAnimeSeasons.DEFAULT): SimklProjected {
    if (snapshot == null) return SimklProjected()
    val entriesById = snapshot.entries.mapNotNull { entry -> entry.media?.simklId?.let { it to entry } }.toMap()

    val visiblePlayback = snapshot.playback
        .filterNot { session -> session.isHiddenBy(session.media?.simklId?.let(entriesById::get)) }
        .mapNotNull { session -> session.toWatchProgress(anime) }
    val progress = visiblePlayback.sortedByDescending(WatchProgress::lastWatched).distinctBy(WatchProgress::contentId)

    val watchedItems = snapshot.entries.flatMap { entry -> entry.watchedItems(anime) }
    val watchedMovieIds = snapshot.entries
        .filter { it.isMovie && it.status == SimklListStatus.COMPLETED }
        .flatMapTo(HashSet()) { entry -> entry.media?.let { movieIdForms(it, entry.animeMode(anime)) }.orEmpty() }

    val nextUpSeeds = snapshot.entries
        .filter { it.status == SimklListStatus.WATCHING && !it.isMovie && it.nextToWatch != null }
        .mapNotNull { entry -> entry.nextUpSeed(anime) }
        .sortedByDescending(WatchProgress::lastWatched)
        .distinctBy(WatchProgress::contentId)

    val episodeProgress = buildMap<String, MutableMap<Pair<Int, Int>, WatchProgress>> {
        watchedItems.forEach { item ->
            val season = item.season ?: return@forEach
            val episode = item.episode ?: return@forEach
            getOrPut(item.contentId) { mutableMapOf() }[season to episode] = item.toFinishedProgress()
        }
        // A playback after the episode was watched is a rewatch: its resume point wins.
        visiblePlayback.forEach { entry ->
            val key = (entry.season ?: return@forEach) to (entry.episode ?: return@forEach)
            val episodes = getOrPut(entry.contentId) { mutableMapOf() }
            if ((episodes[key]?.lastWatched ?: Long.MIN_VALUE) <= entry.lastWatched) episodes[key] = entry
        }
    }
    return SimklProjected(progress, nextUpSeeds, watchedItems, watchedMovieIds, episodeProgress)
}

/**
 * True for a content id Simkl cannot match (an addon's own id): its local progress and marks stay visible while
 * Simkl is the source.
 */
fun isSimklUnmatchable(contentId: String): Boolean {
    val ids = parseTrackingExternalIds(contentId)
    return listOf(ids.imdb, ids.tmdb, ids.tvdb, ids.simkl, ids.mal, ids.anidb, ids.anilist, ids.kitsu).all { it == null }
}

/** [anime] for one of Simkl's anime series, else null (films, anime films included, and shows ignore the mode). */
internal fun SimklEntry.animeMode(anime: SimklAnimeSeasons): SimklAnimeSeasons? =
    anime.takeIf { type == SimklMediaType.ANIME && !isMovie }

private fun movieIdForms(media: SimklMedia, anime: SimklAnimeSeasons?): List<String> = listOfNotNull(
    media.id("imdb")?.takeIf { it.startsWith("tt") },
    media.id("tmdb")?.toLongOrNull()?.let { "tmdb:$it" },
    media.contentId(anime)
)

/**
 * A playback is not shown for a title on hold or dropped (the user put it away), nor for one completed at or after the
 * pause (an overtaken leftover). A pause after completion is a rewatch and stays (upstream `70169a65d`).
 */
private fun SimklPlayback.isHiddenBy(entry: SimklEntry?): Boolean = when (entry?.status) {
    SimklListStatus.ON_HOLD, SimklListStatus.DROPPED -> true
    SimklListStatus.COMPLETED -> {
        val completedAt = parseSimklTime(entry.lastWatchedAt)
        val pausedAtMs = parseSimklTime(pausedAt)
        completedAt != null && (pausedAtMs == null || completedAt >= pausedAtMs)
    }
    else -> false
}

/** [anime] is the profile's mode; it applies only to a playback Simkl files under `anime`. */
internal fun SimklPlayback.toWatchProgress(anime: SimklAnimeSeasons = SimklAnimeSeasons.DEFAULT): WatchProgress? {
    val media = media ?: return null
    val isEpisode = movie == null
    // An anime film's pause (no episode) keeps the film naming, as its entry does.
    val mode = anime.takeIf { this.anime != null && episode != null }
    val contentId = media.contentId(mode) ?: return null
    val perSeason = media.numbersWithinEntry(mode)
    val season = when {
        !isEpisode -> null
        perSeason -> perSeasonNumber(episode?.season)
        else -> episode?.tvdbSeason ?: episode?.season ?: if (this.anime != null) 1 else null
    }
    val number = when {
        !isEpisode -> null
        perSeason -> episode?.number
        else -> episode?.tvdbNumber ?: episode?.number
    }
    if (isEpisode && (season == null || number == null)) return null
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
        episodeTitle = episode?.title,
        position = 0L,
        duration = 0L,
        lastWatched = parseSimklTime(pausedAt) ?: 0L,
        progressPercent = progress.toFloat().coerceIn(0f, 100f),
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
        simklPlaybackId = id,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = media.simklId?.toString()
    )
}

/** Entry-numbered anime ([numbersWithinEntry]): an entry's episodes are its season 1 (as the Kitsu addon numbers them); specials stay 0. */
private fun perSeasonNumber(season: Int?): Int = if (season == 0) 0 else 1

/**
 * An episode's (season, number) as Nuvio numbers it: anime by its TVDB mapping when Simkl gives one, or, one per season,
 * within its own entry.
 */
private fun SimklEntry.episodeKey(season: SimklSeason, episode: SimklEpisode, anime: SimklAnimeSeasons?): Pair<Int, Int>? {
    val number = episode.number ?: return null
    if (media?.numbersWithinEntry(anime) == true) return perSeasonNumber(season.number) to number
    episode.tvdb?.let { mapped -> if (mapped.season != null && mapped.episode != null) return mapped.season to mapped.episode }
    val seasonNumber = season.number ?: if (type == SimklMediaType.ANIME) 1 else return null
    return seasonNumber to number
}

private fun SimklEntry.watchedItems(anime: SimklAnimeSeasons): List<WatchedItem> {
    val media = media ?: return emptyList()
    val mode = animeMode(anime)
    val contentId = media.contentId(mode) ?: return emptyList()
    val itemId = media.simklId?.toString()
    val lastWatchedAtMs = parseSimklTime(lastWatchedAt) ?: 0L
    if (isMovie) {
        if (status != SimklListStatus.COMPLETED) return emptyList()
        return listOf(
            WatchedItem(contentId, "movie", media.title.orEmpty(), watchedAt = lastWatchedAtMs,
                trackingProviderId = TrackingProviderId.SIMKL.storageId, trackingProviderItemId = itemId)
        )
    }
    return seasons.flatMap { season ->
        season.episodes.mapNotNull { episode ->
            val (seasonNumber, number) = episodeKey(season, episode, mode) ?: return@mapNotNull null
            WatchedItem(
                contentId = contentId,
                contentType = "series",
                title = media.title.orEmpty(),
                season = seasonNumber,
                episode = number,
                watchedAt = parseSimklTime(episode.watchedAt) ?: lastWatchedAtMs,
                trackingProviderId = TrackingProviderId.SIMKL.storageId,
                trackingProviderItemId = itemId
            )
        }
    }.distinctBy { it.season to it.episode }
}

/**
 * The latest watched regular episode of a show the user is partway through (specials never lead Up next, as with local
 * seeds); Nuvio works out the next one from it.
 */
private fun SimklEntry.nextUpSeed(anime: SimklAnimeSeasons): WatchProgress? {
    val media = media ?: return null
    val contentId = media.contentId(animeMode(anime)) ?: return null
    val latest = watchedItems(anime).filter { (it.season ?: 0) > 0 }.maxWithOrNull(compareBy<WatchedItem>({ it.watchedAt }, { it.season }, { it.episode }))
        ?: return null
    val season = latest.season ?: return null
    val number = latest.episode ?: return null
    return WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = media.title.orEmpty(),
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$number",
        season = season,
        episode = number,
        episodeTitle = null,
        position = 0L,
        duration = 0L,
        lastWatched = parseSimklTime(lastWatchedAt) ?: latest.watchedAt,
        progressPercent = 100f,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = media.simklId?.toString()
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
