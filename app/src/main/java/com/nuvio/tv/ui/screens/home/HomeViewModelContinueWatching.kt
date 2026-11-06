package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.toNextUpInfo
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.normalizeLanguageCode
import com.nuvio.tv.domain.model.countryToLanguageCode
import com.nuvio.tv.ui.util.parseEpisodeReleaseDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val CW_MAX_RECENT_PROGRESS_ITEMS = 300
private const val CW_MAX_NEXT_UP_LOOKUPS = 32
private const val CW_MAX_NEXT_UP_CONCURRENCY = 4
private const val CW_MAX_ENRICHMENT_CONCURRENCY = 4
private const val CW_PROGRESS_DEBOUNCE_MS = 500L

private data class ProgressSnapshot(
    val items: List<WatchProgress>,
    val nextUpSeeds: List<WatchProgress>
)

private data class CwSettings(
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean,
    val nextUpFromFurthestEpisode: Boolean,
    val continueWatchingSortMode: ContinueWatchingSortMode
)

private data class ContinueWatchingSettingsSnapshot(
    val items: List<WatchProgress>,
    val nextUpSeeds: List<WatchProgress>,
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean,
    val nextUpFromFurthestEpisode: Boolean,
    val continueWatchingSortMode: ContinueWatchingSortMode,
    val watchedItemsVersion: Int  // triggers re-evaluation when watched items change
)

/**
 * Lightweight projection of [Meta] for CW pipeline caching.
 * Drops cast, crew, trailers, streams, release dates, and other heavy fields
 * that are never read by continue-watching or badge evaluation.
 */
internal data class CwMetaSummary(
    val id: String,
    val name: String,
    val poster: String?,
    val backdropUrl: String?,
    val logo: String?,
    val description: String?,
    val genres: List<String>,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val language: String?,
    val country: String?,
    val videos: List<CwVideoSummary>,
    val status: String? = null
) {
    fun watchableEpisodes(): List<CwVideoSummary> {
        val candidates = videos.filter { it.season != null && it.episode != null && it.season > 0 }
        fun isFutureRelease(raw: String?): Boolean = isEpisodeReleaseAired(raw) == false
        val unavailableSeasons = candidates.groupBy { it.season }
            .filter { (_, eps) ->
                val first = eps.minByOrNull { it.episode ?: Int.MAX_VALUE } ?: return@filter false
                if (first.available == false) return@filter true
                isFutureRelease(first.released)
            }.keys
        return candidates
            .filter { it.season !in unavailableSeasons }
            .filter { it.available != false && !isFutureRelease(it.released) }
    }

    /**
     * Returns the start-of-day (00:00 UTC) epochMs of the earliest upcoming season's
     * first episode release date minus 7 days, or null if no upcoming seasons are known.
     * Subtracts 7 days so revalidation triggers a week before the premiere,
     * allowing the countdown badge ("Airs in X days") to appear in CW.
     * Uses start-of-day so revalidation triggers right after midnight, not at
     * the exact broadcast time.
     */
    fun earliestUpcomingSeasonMs(): Long? {
        val today = java.time.LocalDate.now()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val candidates = videos.filter { (it.season ?: 0) > 0 }
        return candidates.groupBy { it.season }
            .mapNotNull { (_, eps) ->
                val first = eps.minByOrNull { it.episode ?: Int.MAX_VALUE } ?: return@mapNotNull null
                if (first.available == false) return@mapNotNull null
                val date = parseEpisodeReleaseDate(first.released)
                if (date == null) return@mapNotNull null
                if (date.isAfter(today)) {
                    val premiereMs = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    // Trigger revalidation 7 days before premiere so countdown badge shows up
                    (premiereMs - sevenDaysMs).coerceAtLeast(System.currentTimeMillis())
                } else null
            }
            .minOrNull()
    }

    /**
     * Earliest not-yet-aired episode release instant (mid-season or new season).
     * Used as a revalidation deadline so currently-airing "caught up" series are
     * re-checked the day a new episode drops, instead of waiting the default 7-day TTL.
     */
    fun earliestUpcomingEpisodeMs(now: Instant = Instant.now()): Long? {
        return videos
            .asSequence()
            .filter { (it.season ?: 0) > 0 && it.available != false }
            .mapNotNull { video -> parseEpisodeReleaseInstant(video.released) }
            .filter { it.isAfter(now) }
            .minOrNull()
            ?.toEpochMilli()
    }

    /** Best revalidation deadline: next episode air time, else next-season window. */
    fun earliestRevalidationMs(now: Instant = Instant.now()): Long? =
        listOfNotNull(earliestUpcomingEpisodeMs(now), earliestUpcomingSeasonMs()).minOrNull()

    fun caughtUpRecheckMs(nowMs: Long = System.currentTimeMillis()): Long =
        caughtUpRecheckAtMs(
            nextReleaseMs = earliestRevalidationMs(Instant.ofEpochMilli(nowMs)),
            ended = isEndedSeriesStatus(status),
            nowMs = nowMs
        )
}

internal data class CwVideoSummary(
    val id: String,
    val title: String?,
    val released: String?,
    val thumbnail: String?,
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val available: Boolean? = null
)

private fun Meta.toCwSummary(): CwMetaSummary = CwMetaSummary(
    id = id,
    name = name,
    poster = poster,
    backdropUrl = backdropUrl,
    logo = logo,
    description = description,
    genres = genres,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    language = language,
    country = country,
    videos = videos.map { v ->
        CwVideoSummary(
            id = v.id,
            title = v.title,
            released = v.released,
            thumbnail = v.thumbnail,
            season = v.season,
            episode = v.episode,
            overview = v.overview,
            available = v.available
        )
    },
    status = status
)

internal data class NextUpResolution(
    val season: Int,
    val episode: Int,
    val videoId: String,
    val episodeTitle: String?,
    val released: String?,
    val hasAired: Boolean,
    val airDateLabel: String?,
    val lastWatched: Long
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun HomeViewModel.loadContinueWatchingPipeline() {
    cwPipelineJob?.cancel()
    cwPipelineJob = viewModelScope.launch {
        combine(
            combine(
                watchProgressRepository.allProgress,
                watchProgressRepository.observeNextUpSeeds()
            ) { items, nextUpSeeds ->
                ProgressSnapshot(items, nextUpSeeds)
            },
            combine(
                continueWatchingSettings.dismissedNextUpKeys,
                layoutPreferenceDataStore.showUnairedNextUp,
                layoutPreferenceDataStore.nextUpFromFurthestEpisode,
                layoutPreferenceDataStore.continueWatchingSortMode
            ) { dismissedNextUp, showUnairedNextUp, nextUpFromFurthest, sortMode ->
                CwSettings(dismissedNextUp, showUnairedNextUp, nextUpFromFurthest, sortMode)
            },
            watchProgressRepository.watchedItems.map { it.size },
            cwPipelineRefreshTrigger
        ) { progressSnapshot, settings, watchedItemsSize, _ ->
            val (items, nextUpSeeds) = progressSnapshot
            ContinueWatchingSettingsSnapshot(
                items = items,
                nextUpSeeds = nextUpSeeds,
                dismissedNextUp = settings.dismissedNextUp,
                showUnairedNextUp = settings.showUnairedNextUp,
                nextUpFromFurthestEpisode = settings.nextUpFromFurthestEpisode,
                continueWatchingSortMode = settings.continueWatchingSortMode,
                watchedItemsVersion = watchedItemsSize
            )
        }.debounce(CW_PROGRESS_DEBOUNCE_MS).collectLatest { snapshot -> runContinueWatchingCycle(snapshot) }
    }
}

/** One CW cycle: its child launches die with it on a collectLatest restart; the viewModelScope.launch snapshot writes deliberately escape. */
private suspend fun HomeViewModel.runContinueWatchingCycle(
    snapshot: ContinueWatchingSettingsSnapshot
): Unit = coroutineScope {
    val pipelineProfileId = profileManager.activeProfileId.value
    val items = snapshot.items
    val nextUpSeeds = snapshot.nextUpSeeds
    val dismissedNextUp = snapshot.dismissedNextUp
    val showUnairedNextUp = snapshot.showUnairedNextUp
    val nextUpFromFurthestEpisode = snapshot.nextUpFromFurthestEpisode
    val continueWatchingSortMode = snapshot.continueWatchingSortMode
    // Local progress has no recency window (only external trackers set one).
    val recentItems = items
        .asSequence()
        .sortedByDescending { it.lastWatched }
        .take(CW_MAX_RECENT_PROGRESS_ITEMS)
        .toList()

    val recentNextUpSeeds = nextUpSeeds
        .asSequence()
        .sortedByDescending { it.lastWatched }
        .take(CW_MAX_RECENT_PROGRESS_ITEMS)
        .toList()
    // All series that still have at least one watched-episode seed.
    // Used to drop cached next-up items for series whose episodes
    // have been fully unmarked as watched.
    val activeSeedContentIds = nextUpSeeds
        .mapTo(mutableSetOf()) { it.contentId }
    // Evict in-memory next-up caches for series that lost all seeds
    // (e.g. user unmarked all episodes as watched). Local seeds are
    // always loaded, so eviction runs every cycle.
    synchronized(discoveredOlderNextUpItems) {
        discoveredOlderNextUpItems.removeAll { it.info.contentId !in activeSeedContentIds }
    }
    synchronized(cwEnrichedNextUpOverlay) {
        cwEnrichedNextUpOverlay.keys.removeAll { it !in activeSeedContentIds }
    }

    // Load cached CW snapshots for instant render
    val (cachedNextUp, cachedInProgress) = coroutineScope {
        val nextUpDeferred = async(Dispatchers.IO) {
            runCatching { cwEnrichmentCache.getNextUpSnapshot() }.getOrDefault(emptyList())
        }
        val inProgressDeferred = async(Dispatchers.IO) {
            runCatching { cwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
        }
        nextUpDeferred.await() to inProgressDeferred.await()
    }
    // Build enrichment lookup from cached snapshots (replaces old CwEnrichmentEntry)
    val cachedEnrichmentFromInProgress = cachedInProgress.associateBy { it.contentId }
    val cachedEnrichmentFromNextUp = cachedNextUp.associateBy { it.contentId }

    // Seed the in-memory enrichment overlay from disk cache on first cycle
    // so that fresh builds use enriched titles/thumbnails from the start.
    if (cwEnrichedNextUpOverlay.isEmpty() && cachedNextUp.isNotEmpty()) {
        cachedNextUp.forEach { cached ->
            cwEnrichedNextUpOverlay[cached.contentId] = cached.toNextUpInfo()
        }
    }
    val inProgressOnly = deduplicateInProgress(
        recentItems.filter { shouldTreatAsInProgressForContinueWatching(it) }
    ).map { progress ->
        val cached = cachedEnrichmentFromInProgress[progress.contentId]
        val displayProgress = if (cached != null && (cached.backdrop != null || cached.poster != null || cached.logo != null || cached.name.isNotBlank())) {
            val sameEpisode = cached.season == progress.season && cached.episode == progress.episode
            progress.copy(
                backdrop = cached.backdrop ?: progress.backdrop,
                poster = cached.poster ?: progress.poster,
                logo = cached.logo ?: progress.logo,
                name = cached.name.takeIf { it.isNotBlank() } ?: progress.name,
                episodeTitle = if (sameEpisode) (cached.episodeTitle ?: progress.episodeTitle) else progress.episodeTitle,
                videoId = if (sameEpisode) (cached.videoId.takeIf { it.isNotBlank() } ?: progress.videoId) else progress.videoId
            )
        } else {
            progress
        }
        ContinueWatchingItem.InProgress(
            progress = displayProgress,
            episodeThumbnail = cached?.episodeThumbnail,
            episodeDescription = cached?.episodeDescription,
            episodeImdbRating = cached?.episodeImdbRating,
            genres = cached?.genres ?: emptyList(),
            releaseInfo = cached?.releaseInfo,
            contentLanguage = cached?.contentLanguage
        )
    }

    // Render in-progress items + cached next-up immediately
    val currentSeedByContentId = nextUpSeeds
        .filter { it.season != null && it.episode != null }
        .associateBy({ it.contentId }, { (it.season!! to it.episode!!) })
    val cachedNextUpItems = cachedNextUp.mapNotNull { cached ->
        // Skip if this show is already in-progress (suppression)
        if (inProgressOnly.any { it.progress.contentId == cached.contentId }) return@mapNotNull null
        // Skip dismissed items
        if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in dismissedNextUp) return@mapNotNull null
        // Release flags recalculated from persisted timestamps, so badges are right even
        // when the cache was written before the new season aired.
        val freshInfo = cached.toFreshNextUpInfo()
        // Respect "show unaired" setting (use recalculated hasAired)
        if (!freshInfo.hasAired && !showUnairedNextUp) return@mapNotNull null
        // Drop if the series no longer has any watched-episode seeds
        // (e.g. user unmarked all episodes as watched).
        if (cached.contentId !in activeSeedContentIds) return@mapNotNull null
        // Concluded with no next episode by an earlier, possibly cancelled, cycle.
        if (isConcludedWithoutNextUp(cached.contentId, cached.seedSeason, cached.seedEpisode)) return@mapNotNull null
        val currentSeed = currentSeedByContentId[cached.contentId]
        if (currentSeed != null && cached.seedSeason != null && cached.seedEpisode != null) {
            val (curSeason, curEpisode) = currentSeed
            val seedAdvanced = curSeason > cached.seedSeason ||
                (curSeason == cached.seedSeason && curEpisode > cached.seedEpisode)
            if (seedAdvanced) return@mapNotNull null
        }
        ContinueWatchingItem.NextUp(info = freshInfo)
    }
    if (inProgressOnly.isNotEmpty() || cachedNextUpItems.isNotEmpty()) {
        val initialItems = applyContinueWatchingEnrichmentOverlay(
            mergeContinueWatchingItems(
                inProgressItems = inProgressOnly,
                nextUpItems = cachedNextUpItems,
                mode = continueWatchingSortMode
            )
        )
        val (mainItems, upcomingOnly) = splitUpcomingItems(initialItems, continueWatchingSortMode)

        _uiState.update { state ->
            if (mainItems.isEmpty() && upcomingOnly.isEmpty() && state.continueWatchingItems.isNotEmpty()) {
                state
            } else if (state.continueWatchingItems == mainItems && state.upcomingItems == upcomingOnly) {
                state
            } else {
                state.copy(continueWatchingItems = mainItems, upcomingItems = upcomingOnly)
            }
        }
        _initialCwResolved.value = true
        // Persist in-progress snapshot early so force-close doesn't lose items.
        if (inProgressOnly.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val ipSnap = inProgressOnly.toCachedInProgressSnapshot(
                    com.nuvio.tv.ui.components.brokenImageUrls
                )
                runCatching {
                    if (profileManager.activeProfileId.value == pipelineProfileId) {
                        cwEnrichmentCache.saveInProgressSnapshot(ipSnap)
                    }
                }
            }
        }
    }

    val publishedPartialNextUpCount = AtomicInteger(0)
    val partialPublishMutex = Mutex()
    val nextUpItems = buildLightweightNextUpItems(
        allProgress = recentItems,
        nextUpSeeds = recentNextUpSeeds,
        inProgressItems = inProgressOnly,
        dismissedNextUp = dismissedNextUp,
        showUnairedNextUp = showUnairedNextUp,
        nextUpFromFurthestEpisode = nextUpFromFurthestEpisode,
        onPartialUpdate = { partialNextUpItems ->
            partialPublishMutex.withLock {
                val partialCount = partialNextUpItems.size
                if (partialCount > publishedPartialNextUpCount.get()) {
                    publishedPartialNextUpCount.set(partialCount)
                    val freshIds = partialNextUpItems.map { it.info.contentId }.toSet()
                    val cachedPartialNextUp = partialNextUpItems.map { nextUp ->
                        nextUp.withCachedEnrichment(cachedEnrichmentFromNextUp[nextUp.info.contentId])
                    }
                    // Keep cached next-up items for series not yet processed
                    // by the fresh pipeline so they don't disappear mid-build.
                    val retainedCached = cachedNextUpItems.filter {
                        it.info.contentId !in freshIds
                    }
                    val partialItems = applyContinueWatchingEnrichmentOverlay(
                        mergeContinueWatchingItems(
                            inProgressItems = inProgressOnly,
                            nextUpItems = cachedPartialNextUp + retainedCached,
                            mode = continueWatchingSortMode
                        )
                    )
                    val (partialMain, partialUpcoming) = splitUpcomingItems(partialItems, continueWatchingSortMode)
                    _uiState.update { state ->
                        if (state.continueWatchingItems == partialMain && state.upcomingItems == partialUpcoming) {
                            state
                        } else {
                            state.copy(continueWatchingItems = partialMain, upcomingItems = partialUpcoming)
                        }
                    }
                }
            }
        }
    )

    // Badge evaluation is handled exclusively by publishBadgeUpdate below,
    // which uses getWatchedShowEpisodes() as the single source of truth.
    // No seed-based heuristics here.
    val allWatchedItems = watchProgressRepository.watchedItems.first()
    // --- Async badge evaluation ---
    // Resolve meta for all series with watched episodes and evaluate badges.
    // Uses getWatchedShowEpisodes() as the single source of truth.
    launch(Dispatchers.IO) {
        val allWatchedEpisodes = watchProgressRepository.getWatchedShowEpisodes()

        // Skip badge evaluation if watched episodes haven't changed since
        // last cycle (e.g. position save triggered pipeline restart).
        val currentKeys = allWatchedEpisodes.keys
        if (currentKeys == cwLastBadgeEpisodeKeys) {
            // Keys unchanged — just re-run publishBadgeUpdate with cached data
            // in case in-memory badge episode cache was populated by a prior cycle.
            publishBadgeUpdate(allWatchedEpisodes)
            return@launch
        }

        // One badge meta resolution per watched show id not yet cached. Resolved ids
        // stay in cwBadgeEpisodeCache, so a pass cancelled with its cycle resumes here.
        val resolvableIds = allWatchedEpisodes.keys.filter { contentId ->
            val cacheKey = "series:$contentId"
            synchronized(cwBadgeEpisodeCache) {
                !cwBadgeEpisodeCache.containsKey(cacheKey) &&
                    !cwBadgeEpisodeCache.containsKey("tv:$contentId")
            }
        }
        val staleIds = resolvableIds.filter { contentId ->
            fullyWatchedSeriesIds.filterStaleIds(setOf(contentId)).isNotEmpty()
        }
        // Split into first-time (never validated) vs revalidation (expired deadline).
        val (firstTimeIds, revalidationIds) = staleIds.partition { contentId ->
            !fullyWatchedSeriesIds.hasBeenValidated(contentId)
        }

        // First-time: resolve as fast as possible so badges appear quickly.
        if (firstTimeIds.isNotEmpty()) {
            val metaSemaphore = Semaphore(2)
            firstTimeIds.map { contentId ->
                async {
                    metaSemaphore.withPermit {
                        resolveBadgeEpisodes(contentId, "series")
                    }
                }
            }.awaitAll()
        }

        // Revalidation: process gently to avoid CPU/memory spikes.
        for (contentId in revalidationIds) {
            resolveBadgeEpisodes(contentId, "series")
            kotlinx.coroutines.yield()
        }

        // Single badge evaluation after all meta is resolved.
        publishBadgeUpdate(allWatchedEpisodes)
        // Recorded only once the pass completed, so a cancelled pass is redone.
        cwLastBadgeEpisodeKeys = currentKeys.toSet()
    }

    // --- CW next-up injection ---
    // Discover next-up items for older seeds and inject release alerts into CW.
    // Seeds come from observeNextUpSeeds() (local watched series episodes, season > 0);
    // there is no hidden-show filter. Older seeds are narrowed below to those not yet
    // processed, not freshly validated, and without a live cwNextUpResolutionCache entry.
    val conclusivelyProcessedOlderContentIds = ConcurrentHashMap.newKeySet<String>()
    val resolvedOlderNextUpContentIds = ConcurrentHashMap.newKeySet<String>()
    val allSeedContentIds = nextUpSeeds
        .filter { isSeriesType(it.contentType) && it.season != null && it.episode != null }
        .map { it.contentId }
        .toSet()
    // Include seeds that were in the recent window but didn't fit
    // into CW_MAX_NEXT_UP_LOOKUPS — they were never processed by
    // buildLightweightNextUpItems and need async resolution.
    val processedContentIds = synchronized(cwLastProcessedNextUpContentIds) {
        cwLastProcessedNextUpContentIds.toSet()
    }
    releaseOlderSeedsDueAgain()
    val olderSeedContentIds = allSeedContentIds - processedContentIds - cwProcessedOlderSeedContentIds
    val uncachedOlderSeedIds = olderSeedContentIds.filter { contentId ->
        // Skip series validated recently — no new episodes expected within TTL.
        if (fullyWatchedSeriesIds.isSeriesValidationFresh(contentId)) return@filter false
        // Allow series in disk cache to be re-resolved when their
        // validation TTL has expired — otherwise stale badge flags
        // (isNewSeasonRelease, isReleaseAlert) never get refreshed and
        // new seasons won't show the correct badge until manual cache clear.
        !hasLiveNextUpResolution(contentId)
    }.toSet()
    if (uncachedOlderSeedIds.isNotEmpty()) {
        val seedsFromNextUp = nextUpSeeds
            .filter { it.contentId in uncachedOlderSeedIds }
            .filter { isSeriesType(it.contentType) && it.season != null && it.episode != null && it.season != 0 }
            .filter { shouldUseAsCompletedSeed(it) }
        val seedsFromWatchedItems = uncachedOlderSeedIds
            .filter { contentId -> seedsFromNextUp.none { it.contentId == contentId } }
            .mapNotNull { contentId ->
                val latestEpisode = allWatchedItems
                    .filter { it.contentId == contentId && it.season != null && it.episode != null }
                    .maxWithOrNull(compareBy({ it.season }, { it.episode }))
                    ?: return@mapNotNull null
                WatchProgress(
                    contentId = contentId,
                    contentType = "series",
                    name = latestEpisode.title,
                    poster = null, backdrop = null, logo = null,
                    videoId = contentId,
                    season = latestEpisode.season,
                    episode = latestEpisode.episode,
                    episodeTitle = null,
                    position = 1L, duration = 1L,
                    lastWatched = latestEpisode.watchedAt,
                    progressPercent = 100f
                )
            }
        val uncachedSeeds = (seedsFromNextUp + seedsFromWatchedItems)
            .groupBy { it.contentId }
            .mapNotNull { (_, items) -> choosePreferredNextUpSeed(items, nextUpFromFurthestEpisode) }
        if (uncachedSeeds.isNotEmpty()) {
            launch(Dispatchers.IO) {
                // Process sequentially with yielding to avoid CPU/GC spikes.
                // Emit partial updates every few resolved items so user sees
                // new CW entries appearing progressively.
                // A seed is recorded (cwProcessedOlderSeedContentIds, and its resolved item
                // in discoveredOlderNextUpItems) only once its work is done, so a cycle
                // cancelled mid-loop loses nothing and the next cycle resumes after it.
                val olderItemsResolvedThisCycle = mutableListOf<ContinueWatchingItem.NextUp>()
                suspend fun publishOlder(persist: Boolean): Unit = applyConclusiveOlderNextUpResults(
                    resolvedItems = olderItemsResolvedThisCycle.toList(),
                    conclusivelyProcessedContentIds = conclusivelyProcessedOlderContentIds.toSet(),
                    dismissedNextUpKeys = dismissedNextUp,
                    sortMode = continueWatchingSortMode,
                    pipelineProfileId = pipelineProfileId,
                    persistSnapshot = persist
                )
                var resolvedSinceLastEmit = 0
                for (seed in uncachedSeeds) {
                    // Re-check freshness — badge pipeline may have validated
                    // this series while we were processing earlier seeds.
                    if (fullyWatchedSeriesIds.isSeriesValidationFresh(seed.contentId)) {
                        cwProcessedOlderSeedContentIds += seed.contentId
                        kotlinx.coroutines.yield()
                        continue
                    }
                    val item = buildNextUpItem(
                        progress = seed,
                        showUnairedNextUp = showUnairedNextUp
                    )
                    if (item != null) {
                        conclusivelyProcessedOlderContentIds += seed.contentId
                        resolvedOlderNextUpContentIds += seed.contentId
                        // A seed released for re-lookup that now has a next episode is no
                        // longer concluded; otherwise its saved card is filtered each cycle.
                        cwOlderSeedsWithoutNextUp.remove(seed.contentId)
                        // Same mid-season case as the lightweight path.
                        if (seed.contentId in fullyWatchedSeriesIds.fullyWatchedSeriesIds.value &&
                            fullyWatchedNextUpAction(item.info.hasAired) ==
                            FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE
                        ) {
                            clearStaleFullyWatchedForAiredNextUp(
                                contentId = seed.contentId,
                                nextSeason = item.info.season,
                                nextEpisode = item.info.episode
                            )
                        }
                        olderItemsResolvedThisCycle.add(item)
                        rememberDiscoveredOlderNextUp(item, dismissedNextUp)
                        cwProcessedOlderSeedContentIds += seed.contentId
                        resolvedSinceLastEmit++
                        if (resolvedSinceLastEmit >= 3) {
                            resolvedSinceLastEmit = 0
                            // Partial emit: inject discovered items into UI.
                            publishOlder(persist = false)
                        }
                    } else if (cachedCwMeta(seed.contentType, seed.contentId) != null) {
                        // Meta resolved and there is no next episode: conclusive.
                        // buildNextUpItem already recorded the validation deadline.
                        conclusivelyProcessedOlderContentIds += seed.contentId
                        // Recorded VM-wide before the seed is marked processed: the disk
                        // snapshot is cleaned only by a later persist, and this seed is not
                        // processed again, so a cancelled cycle must not let the stale card
                        // come back from disk.
                        val seedSeason = seed.season
                        val seedEpisode = seed.episode
                        if (seedSeason != null && seedEpisode != null) {
                            cwOlderSeedsWithoutNextUp[seed.contentId] = seedSeason to seedEpisode
                        }
                        cwProcessedOlderSeedContentIds += seed.contentId
                        publishOlder(persist = false)
                    }
                    // Otherwise meta was unavailable (network error, addon timeout): the
                    // seed stays unrecorded, so a later cycle retries it once the
                    // negative caches expire, and it is not removed from Continue Watching.
                    kotlinx.coroutines.yield()
                }

                // Re-run badge evaluation with episode caches populated
                // by buildNextUpItem — picks up fully-watched series
                // discovered during async inject and persists their
                // deadlines so they're skipped on next launch.
                val asyncWatchedEpisodes = watchProgressRepository.getWatchedShowEpisodes()
                publishBadgeUpdate(asyncWatchedEpisodes)

                if (conclusivelyProcessedOlderContentIds.isNotEmpty()) {
                    publishOlder(persist = true)
                }
            }
        }
    }

    // Include previously discovered older next-up items so they survive collectLatest restarts.
    val persistedOlderItems = synchronized(discoveredOlderNextUpItems) {
        discoveredOlderNextUpItems.toList()
    }
    // Preserve cached next-up items from disk until async inject re-verifies them.
    // Drop items whose series no longer has any watched-episode seeds.
    val cachedOlderNextUp = cachedNextUp
        .filter { it.contentId in activeSeedContentIds }
        .filterNot { isConcludedWithoutNextUp(it.contentId, it.seedSeason, it.seedEpisode) }
        .map { cached -> ContinueWatchingItem.NextUp(info = cached.toFreshNextUpInfo()) }
    val recentIds = nextUpItems.map { it.info.contentId }.toSet()
    val inProgressIds = inProgressOnly.map { it.progress.contentId }.toSet()
    // Exclude cached older items for series that the fresh pipeline evaluated
    // but didn't produce a next-up for (e.g. fully watched series).
    val rejectedByFreshPipeline = synchronized(cwLastProcessedNextUpContentIds) {
        cwLastProcessedNextUpContentIds.toSet()
    } - recentIds
    // Both sources hold only series with an active seed: the cached list is filtered
    // above, and discoveredOlderNextUpItems is evicted at cycle start and refilled only
    // from this cycle's seeds.
    val olderToInclude = (persistedOlderItems + cachedOlderNextUp)
        .distinctBy { it.info.contentId }
        .filter {
            it.info.contentId !in recentIds &&
                it.info.contentId !in inProgressIds &&
                (
                    it.info.contentId !in conclusivelyProcessedOlderContentIds ||
                        it.info.contentId in resolvedOlderNextUpContentIds
                ) &&
                // Reject items the fresh pipeline evaluated but produced no
                // next-up for (e.g. fully watched series).  Cached-from-disk
                // items survive only until the fresh pipeline processes their
                // seed — once rejected there, they are removed immediately.
                it.info.contentId !in rejectedByFreshPipeline &&
                // Respect "show unaired" setting for all items including cached.
                (it.info.hasAired || showUnairedNextUp) &&
                nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in dismissedNextUp
        }
    val allNextUpItems = nextUpItems + olderToInclude
    val freshContentIds = allNextUpItems.map { it.info.contentId }.toSet()
    val retainedFromCache = cachedNextUpItems.filter {
        it.info.contentId !in freshContentIds &&
            (
                it.info.contentId !in conclusivelyProcessedOlderContentIds ||
                    it.info.contentId in resolvedOlderNextUpContentIds
            ) &&
            it.info.contentId !in rejectedByFreshPipeline &&
            nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in dismissedNextUp
    }
    val finalNextUpItems = allNextUpItems + retainedFromCache
    val normalItems = applyContinueWatchingEnrichmentOverlay(
        mergeContinueWatchingItems(
            inProgressItems = inProgressOnly,
            nextUpItems = finalNextUpItems.map { nextUp ->
                nextUp.withCachedEnrichment(cachedEnrichmentFromNextUp[nextUp.info.contentId])
            },
            mode = continueWatchingSortMode
        )
    )
    val (normalMain, normalUpcoming) = splitUpcomingItems(normalItems, continueWatchingSortMode)

    _uiState.update { state ->
        // Local progress is always loaded, so an empty result is trusted
        // (items may have been deleted remotely).
        if (state.continueWatchingItems == normalMain && state.upcomingItems == normalUpcoming) {
            state
        } else {
            state.copy(continueWatchingItems = normalMain, upcomingItems = normalUpcoming)
        }
    }
    // Signal that the first CW cycle completed (items or confirmed empty).
    if (!_initialCwResolved.value) {
        _initialCwResolved.value = true
    }

    // Save lightweight CW snapshot to disk immediately so cache stays fresh
    // even if enrichment is cancelled by collectLatest.
    viewModelScope.launch(Dispatchers.IO) {
        val currentItems = _uiState.value.continueWatchingItems + _uiState.value.upcomingItems
        val brokenUrls = com.nuvio.tv.ui.components.brokenImageUrls
        val nextUpSnap = currentItems.toCachedNextUpSnapshot(brokenUrls)
        val ipSnap = currentItems.toCachedInProgressSnapshot(brokenUrls)
        if (profileManager.activeProfileId.value == pipelineProfileId) {
            runCatching { cwEnrichmentCache.saveNextUpSnapshot(nextUpSnap, force = true) }
            runCatching { cwEnrichmentCache.saveInProgressSnapshot(ipSnap, force = true) }
        }
    }

    // Rich metadata only runs after the final lightweight CW list is visible.
    val enrichmentDelayMs = remainingContinueWatchingEnrichmentGraceMs()
    if (enrichmentDelayMs > 0L) {
        delay(enrichmentDelayMs)
    }

    enrichVisibleContinueWatchingItems(
        finalItems = normalItems,
        pipelineProfileId = pipelineProfileId
    )
}

private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
    val (series, nonSeries) = items.partition { isSeriesType(it.contentType) }
    val latestPerShow = series
        .sortedByDescending { it.lastWatched }
        .distinctBy { it.contentId }
    return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
}

private fun shouldTreatAsInProgressForContinueWatching(progress: WatchProgress): Boolean {
    if (progress.isCompleted()) return false
    if (progress.isInProgress() && progress.progressPercentage >= 0.02f) return true

    return progress.position > 0L ||
        progress.progressPercent?.let { it > 0f } == true
}

private fun HomeViewModel.shouldUseAsCompletedSeed(progress: WatchProgress): Boolean {
    if (isMalformedNextUpSeedContentId(progress.contentId)) return false
    return progress.isCompleted()
}

private fun HomeViewModel.shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgress,
    latestCompletedAt: Long?
): Boolean {
    if (!shouldTreatAsInProgressForContinueWatching(progress)) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastWatched >= latestCompletedAt
}

private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
    val trimmed = contentId?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return when (trimmed.lowercase(Locale.US)) {
        "tmdb", "imdb", "tmdb:", "imdb:" -> true
        else -> false
    }
}

// Every seed is built locally (observeNextUpSeeds or the watched-items fallback), so there is
// no source ranking: only the episode/recency order picks between a series' seeds.
private fun choosePreferredNextUpSeed(items: List<WatchProgress>, nextUpFromFurthestEpisode: Boolean = true): WatchProgress? {
    return items.maxWithOrNull(
        if (nextUpFromFurthestEpisode) {
            compareBy<WatchProgress>(
                { it.season ?: -1 },
                { it.episode ?: -1 },
                { it.lastWatched }
            )
        } else {
            compareBy<WatchProgress>(
                { it.lastWatched },
                { it.season ?: -1 },
                { it.episode ?: -1 }
            )
        }
    )
}

private fun resolveVideoForProgress(progress: WatchProgress, meta: CwMetaSummary): CwVideoSummary? {
    if (!isSeriesType(progress.contentType)) return null
    val videos = meta.videos.filter { it.season != null && it.episode != null && it.season != 0 }
    if (videos.isEmpty()) return null

    progress.videoId.takeIf { it.isNotBlank() }?.let { videoId ->
        videos.firstOrNull { it.id == videoId }?.let { return it }
    }

    val season = progress.season
    val episode = progress.episode
    if (season != null && episode != null) {
        videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }

        // Fallback: if not found by season+episode (anime with absolute numbering
        // vs multi-season on addon), try global index matching.
        val addonSeasons = videos.mapTo(mutableSetOf()) { it.season }
        if (season == 1 && addonSeasons.size > 1 && episode > 0) {
            val sorted = videos.sortedWith(
                compareBy<CwVideoSummary>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE })
            )
            val globalIndex = episode - 1
            if (globalIndex in sorted.indices) {
                return sorted[globalIndex]
            }
        }
    }

    return null
}

private suspend fun HomeViewModel.buildLightweightNextUpItems(
    allProgress: List<WatchProgress>,
    nextUpSeeds: List<WatchProgress>,
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    dismissedNextUp: Set<String>,
    showUnairedNextUp: Boolean,
    nextUpFromFurthestEpisode: Boolean = true,
    onPartialUpdate: suspend (List<ContinueWatchingItem.NextUp>) -> Unit = {}
): List<ContinueWatchingItem.NextUp> = coroutineScope {
    // Move seed filtering/grouping/sorting off the main thread.
    val seeds = withContext(Dispatchers.Default) {
        val latestCompletedByContent = allProgress
            .asSequence()
            .filter { isSeriesType(it.contentType) }
            .filter { it.contentId.isNotBlank() }
            .filter { shouldUseAsCompletedSeed(it) }
            .groupBy { it.contentId }
            .mapValues { (_, items) ->
                items.maxOfOrNull { it.lastWatched } ?: Long.MIN_VALUE
            }

        val ipIds = inProgressItems
            .map { it.progress }
            .filter { progress ->
                shouldTreatAsActiveInProgressForNextUpSuppression(
                    progress = progress,
                    latestCompletedAt = latestCompletedByContent[progress.contentId]
                )
            }
            .map { it.contentId }
            .toSet()

        nextUpSeeds
            .filter { progress ->
                isSeriesType(progress.contentType) &&
                    progress.season != null &&
                    progress.episode != null &&
                    progress.season != 0 &&
                    shouldUseAsCompletedSeed(progress)
            }
            .groupBy { it.contentId }
            .mapNotNull { (_, items) ->
                choosePreferredNextUpSeed(items, nextUpFromFurthestEpisode)
            }
            .filter { it.contentId !in ipIds }
            .filter { progress ->
                nextUpDismissKey(progress.contentId, progress.season, progress.episode) !in dismissedNextUp
            }
            .sortedByDescending { it.lastWatched }
            // Skip seeds validated as "no next-up" ONLY if the seed hasn't changed.
            // The cache key includes season+episode, so a changed seed (user watched
            // a new episode) produces a cache miss and is always processed.
            .filter { progress ->
                val cacheKey = buildNextUpSeedCacheKey(progress, showUnairedNextUp)
                val inCache = synchronized(cwNextUpResolutionCache) {
                    cwNextUpResolutionCache.containsKey(cacheKey)
                }
                if (!inCache) return@filter true // cache miss — seed changed or first time
                val cachedValue = synchronized(cwNextUpResolutionCache) {
                    cwNextUpResolutionCache[cacheKey]
                }
                if (cachedValue != null) return@filter true // positive hit — has next-up
                // Negative hit (no next-up) — skip if TTL is fresh
                !fullyWatchedSeriesIds.isSeriesValidationFresh(progress.contentId)
            }
            .take(CW_MAX_NEXT_UP_LOOKUPS)
    }

    if (seeds.isEmpty()) {
        return@coroutineScope emptyList()
    }

    val lookupSemaphore = Semaphore(CW_MAX_NEXT_UP_CONCURRENCY)
    val mergeMutex = Mutex()
    val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()
    val processedContentIds = Collections.synchronizedSet(mutableSetOf<String>())
    val resolvedSinceLastPublish = java.util.concurrent.atomic.AtomicInteger(0)
    // Batch partial updates: publish every N resolved items instead of after each one.
    val partialPublishBatchSize = (seeds.size / 3).coerceIn(2, 8)

    val jobs = seeds.map { progress ->
        launch(Dispatchers.IO) {
            lookupSemaphore.withPermit {
                processedContentIds.add(progress.contentId)
                val nextUp = buildNextUpItem(
                    progress = progress,
                    showUnairedNextUp = showUnairedNextUp
                ) ?: run {
                    // If meta was not available (network error, addon timeout),
                    // remove from processedContentIds so this series is NOT
                    // treated as "rejected". The cached CW snapshot will keep
                    // it visible until the next successful meta resolution.
                    if (cachedCwMeta(progress.contentType, progress.contentId) == null) {
                        processedContentIds.remove(progress.contentId)
                    }
                    return@withPermit
                }
                val fullyWatched = fullyWatchedSeriesIds.fullyWatchedSeriesIds.value
                if (progress.contentId in fullyWatched) {
                    // See [fullyWatchedNextUpAction] — never drop when a next episode exists.
                    when (fullyWatchedNextUpAction(nextUp.info.hasAired)) {
                        FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE -> {
                            clearStaleFullyWatchedForAiredNextUp(
                                contentId = progress.contentId,
                                nextSeason = nextUp.info.season,
                                nextEpisode = nextUp.info.episode
                            )
                        }
                        FullyWatchedNextUpAction.KEEP_WITH_BADGE -> {
                            // Unaired countdown / new-season window: badge stays, item stays.
                        }
                    }
                }
                val shouldPublish: Boolean
                val partialItems = mergeMutex.withLock {
                    nextUpByContent[progress.contentId] = nextUp
                    val count = resolvedSinceLastPublish.incrementAndGet()
                    shouldPublish = count >= partialPublishBatchSize
                    if (shouldPublish) resolvedSinceLastPublish.set(0)
                    if (shouldPublish) nextUpByContent.values.toList() else emptyList()
                }
                if (shouldPublish) {
                    onPartialUpdate(partialItems)
                }
            }
        }
    }
    jobs.joinAll()

    // Store which contentIds were evaluated so olderToInclude can skip fully-watched series.
    synchronized(cwLastProcessedNextUpContentIds) {
        cwLastProcessedNextUpContentIds.clear()
        cwLastProcessedNextUpContentIds.addAll(processedContentIds)
    }

    nextUpByContent.values.toList()
}

private suspend fun HomeViewModel.enrichVisibleContinueWatchingItems(
    finalItems: List<ContinueWatchingItem>,
    pipelineProfileId: Int
): Unit = coroutineScope {
    if (finalItems.isEmpty()) return@coroutineScope

    val metaCache = cwMetaCache
    val enrichmentSemaphore = Semaphore(CW_MAX_ENRICHMENT_CONCURRENCY)
    val enrichedItems = finalItems
        .mapIndexed { index, item ->
            async(Dispatchers.IO) {
                enrichmentSemaphore.withPermit {
                    index to when (item) {
                        is ContinueWatchingItem.InProgress -> {
                            val overlay = cwEnrichedInProgressOverlay[item.progress.contentId]
                            if (overlay != null &&
                                overlay.progress.videoId == item.progress.videoId &&
                                overlay.progress.season == item.progress.season &&
                                overlay.progress.episode == item.progress.episode
                            ) {
                                overlay.copy(
                                    progress = overlay.progress.copy(
                                        position = item.progress.position,
                                        duration = item.progress.duration,
                                        lastWatched = item.progress.lastWatched,
                                        progressPercent = item.progress.progressPercent
                                    )
                                )
                            } else {
                                enrichInProgressItem(item, metaCache)
                            }
                        }
                        is ContinueWatchingItem.NextUp -> {
                            val overlay = cwEnrichedNextUpOverlay[item.info.contentId]
                            if (overlay != null &&
                                overlay.season == item.info.season &&
                                overlay.episode == item.info.episode
                            ) {
                                val refreshed = overlay.withRefreshedAiring()
                                if (refreshed.hasAired != overlay.hasAired) {
                                    cwEnrichedNextUpOverlay[item.info.contentId] = refreshed
                                }
                                item.copy(info = refreshed)
                            } else {
                                enrichNextUpItem(item, metaCache)
                            }
                        }
                    }
                }
            }
        }
        .awaitAll()
        .sortedBy { it.first }
        .map { it.second }

    // Re-sort if any sortTimestamp changed during enrichment (e.g. release alert
    // detected after the full addon meta provided a more accurate release date).
    val sortChanged = enrichedItems.zip(finalItems).any { (enriched, original) ->
        val enrichedTs = when (enriched) {
            is ContinueWatchingItem.InProgress -> enriched.progress.lastWatched
            is ContinueWatchingItem.NextUp -> enriched.info.sortTimestamp
        }
        val originalTs = when (original) {
            is ContinueWatchingItem.InProgress -> original.progress.lastWatched
            is ContinueWatchingItem.NextUp -> original.info.sortTimestamp
        }
        enrichedTs != originalTs
    }
    val sortedEnrichedItems = if (sortChanged) {
        sortContinueWatchingItems(enrichedItems, continueWatchingSortMode)
    } else {
        enrichedItems
    }

    if (sortedEnrichedItems == finalItems) return@coroutineScope

    // Save enriched next-up info to in-memory overlay so the next CW cycle's
    // cached/partial/normal emissions use enriched data from the start,
    // preventing title/thumbnail flickering between lightweight and enriched values.
    sortedEnrichedItems.forEach { item ->
        when (item) {
            is ContinueWatchingItem.NextUp -> {
                cwEnrichedNextUpOverlay[item.info.contentId] = item.info
            }
            is ContinueWatchingItem.InProgress -> {
                cwEnrichedInProgressOverlay[item.progress.contentId] = item
            }
        }
    }

    _uiState.update { state ->
        val mergedEnrichedItems = mergeConcurrentContinueWatchingEnrichment(
            currentItems = state.continueWatchingItems + state.upcomingItems,
            originalItems = finalItems,
            enrichedItems = enrichedItems
        )
        val sortedMergedItems = sortContinueWatchingItems(
            mergedEnrichedItems,
            continueWatchingSortMode
        )
        val (enrichedMain, enrichedUpcoming) = splitUpcomingItems(
            sortedMergedItems,
            continueWatchingSortMode
        )
        if (state.continueWatchingItems == enrichedMain && state.upcomingItems == enrichedUpcoming) {
            state
        } else {
            state.copy(continueWatchingItems = enrichedMain, upcomingItems = enrichedUpcoming)
        }
    }
    persistLocalContinueWatchingMetadata(
        originalItems = finalItems,
        enrichedItems = enrichedItems,
        pipelineProfileId = pipelineProfileId
    )
}

/** Unaired rows by release date, soonest first; rows with no known date go last. */
private val releaseDateAscending = Comparator<ContinueWatchingItem> { a, b ->
    val dateA = parseEpisodeReleaseDate((a as? ContinueWatchingItem.NextUp)?.info?.released)
    val dateB = parseEpisodeReleaseDate((b as? ContinueWatchingItem.NextUp)?.info?.released)
    when {
        dateA == null && dateB == null -> 0
        dateA == null -> 1
        dateB == null -> -1
        else -> dateA.compareTo(dateB)
    }
}

internal fun sortContinueWatchingItems(
    items: List<ContinueWatchingItem>,
    mode: ContinueWatchingSortMode
): List<ContinueWatchingItem> {
    return when (mode) {
        ContinueWatchingSortMode.DEFAULT, ContinueWatchingSortMode.SPLIT_UPCOMING -> items.sortedByDescending { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.lastWatched
                is ContinueWatchingItem.NextUp -> item.info.sortTimestamp
            }
        }

        ContinueWatchingSortMode.STREAMING_STYLE -> {
            val (released, unreleased) = items.partition { item ->
                when (item) {
                    is ContinueWatchingItem.InProgress -> true // in-progress is always released
                    is ContinueWatchingItem.NextUp -> item.info.hasAired
                }
            }

            val sortedReleased = released.sortedByDescending { item ->
                when (item) {
                    is ContinueWatchingItem.InProgress -> item.progress.lastWatched
                    is ContinueWatchingItem.NextUp -> if (item.info.isReleaseAlert) item.info.sortTimestamp else item.info.lastWatched
                }
            }

            sortedReleased + unreleased.sortedWith(releaseDateAscending)
        }
    }
}

internal fun mergeContinueWatchingItems(
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    nextUpItems: List<ContinueWatchingItem.NextUp>,
    mode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT
): List<ContinueWatchingItem> {
    val allInProgressIds = inProgressItems
        .asSequence()
        .map { it.progress }
        .filter { isSeriesType(it.contentType) }
        .map { it.contentId }
        .filter { it.isNotBlank() }
        .toSet()

    val filteredNextUpItems = nextUpItems.filter { item ->
        item.info.contentId !in allInProgressIds
    }

    val combined = inProgressItems + filteredNextUpItems

    val seen = mutableSetOf<String>()
    val deduplicated = combined.filter { item ->
        val contentId = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.contentId
            is ContinueWatchingItem.NextUp -> item.info.contentId
        }
        contentId.isBlank() || seen.add(contentId)
    }

    return sortContinueWatchingItems(deduplicated, mode)
}

/**
 * Splits items into (mainRow, upcomingRow) for [ContinueWatchingSortMode.SPLIT_UPCOMING].
 * Upcoming items are unaired NextUp episodes; everything else stays in the main row.
 * For other modes, all items go into mainRow and upcomingRow is empty.
 */
internal fun splitUpcomingItems(
    items: List<ContinueWatchingItem>,
    mode: ContinueWatchingSortMode
): Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>> {
    if (mode != ContinueWatchingSortMode.SPLIT_UPCOMING) {
        return items to emptyList()
    }
    val (upcoming, main) = items.partition { item ->
        item is ContinueWatchingItem.NextUp && !item.info.hasAired
    }
    return main to upcoming.sortedWith(releaseDateAscending)
}

private suspend fun HomeViewModel.buildNextUpItem(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): ContinueWatchingItem.NextUp? {
    val nextUp = findNextUpEpisodeFromMetaSeed(
        progress = progress,
        showUnairedNextUp = showUnairedNextUp
    ) ?: run {
        // Populate badge episode cache from meta that was already resolved by
        // findNextUpEpisodeFromMetaSeed — avoids duplicate meta fetch in badge pipeline.
        val cachedMeta = cachedCwMeta(progress.contentType, progress.contentId)
        if (cachedMeta != null) {
            val episodes = cachedMeta.watchableEpisodes()
                .mapNotNull { v -> v.season?.let { s -> v.episode?.let { e -> s to e } } }
                .toSet()
            val cacheKey = "series:${progress.contentId}"
            synchronized(cwBadgeEpisodeCache) {
                if (!cwBadgeEpisodeCache.containsKey(cacheKey)) {
                    cwBadgeEpisodeCache[cacheKey] = episodes
                }
            }
        }
        // Only record the series as validated (no next episode) if meta was
        // actually resolved. When meta is unavailable (network error, addon
        // timeout) we must NOT validate it — that would incorrectly remove it
        // from Continue Watching. The cached CW snapshot will keep it visible
        // until the next successful meta resolution. The badge IDs are left
        // as they are. The deadline goes into cwBadgeNextSeasonMs too, so the
        // badge pass re-applies it (a stale list's 5 min back-off included).
        if (cachedMeta != null) {
            val deadline = caughtUpValidationDeadline(progress.contentType, progress.contentId, cachedMeta)
            cwBadgeNextSeasonMs[progress.contentId] = deadline
            fullyWatchedSeriesIds.transformWithValidation(
                validatedIds = setOf(progress.contentId),
                revalidateAt = mapOf(progress.contentId to deadline)
            )
        }
        return null
    }
    val seedMeta = resolveMetaForProgress(progress, cwMetaCache)

    val name = progress.name.trim().takeIf { it.isNotEmpty() }
        ?: seedMeta?.name
        ?: progress.contentId
    val releaseState = resolveNextUpReleaseState(
        seedProgress = progress,
        nextSeason = nextUp.season,
        nextReleased = nextUp.released,
        hasAired = nextUp.hasAired
    )
    val nextUpVideo = seedMeta?.videos?.firstOrNull {
        it.season == nextUp.season && it.episode == nextUp.episode
    }
    val info = NextUpInfo(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = name,
        poster = progress.poster.normalizeImageUrl() ?: seedMeta?.poster.normalizeImageUrl(),
        backdrop = progress.backdrop.normalizeImageUrl() ?: seedMeta?.backdropUrl.normalizeImageUrl(),
        logo = progress.logo.normalizeImageUrl() ?: seedMeta?.logo.normalizeImageUrl(),
        videoId = nextUp.videoId,
        season = nextUp.season,
        episode = nextUp.episode,
        episodeTitle = nextUp.episodeTitle ?: nextUpVideo?.title,
        episodeDescription = nextUpVideo?.overview,
        thumbnail = nextUpVideo?.thumbnail.normalizeImageUrl(),
        released = nextUp.released,
        hasAired = nextUp.hasAired,
        airDateLabel = nextUp.airDateLabel,
        lastWatched = nextUp.lastWatched,
        imdbRating = null,
        genres = emptyList(),
        releaseInfo = null,
        sortTimestamp = releaseState.sortTimestamp,
        releaseTimestamp = releaseState.releaseTimestamp,
        isReleaseAlert = releaseState.isReleaseAlert,
        isNewSeasonRelease = releaseState.isNewSeasonRelease,
        seedSeason = progress.season,
        seedEpisode = progress.episode
    )
    return ContinueWatchingItem.NextUp(info)
}

private suspend fun HomeViewModel.enrichInProgressItem(
    item: ContinueWatchingItem.InProgress,
    metaCache: MutableMap<String, CwMetaSummary?>
): ContinueWatchingItem.InProgress {
    val meta = resolveMetaForProgress(item.progress, metaCache) ?: return item
    val video = resolveVideoForProgress(item.progress, meta)
    val genres = meta.genres.take(3)
    val releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() }
    return item.copy(
        progress = item.progress.copy(
            name = meta.name,
            poster = item.progress.poster ?: meta.poster.normalizeImageUrl(),
            backdrop = meta.backdropUrl.normalizeImageUrl() ?: item.progress.backdrop,
            logo = meta.logo.normalizeImageUrl() ?: item.progress.logo,
            episodeTitle = video?.title?.takeIf { it.isNotBlank() } ?: item.progress.episodeTitle
        ),
        episodeDescription = video?.overview?.takeIf { it.isNotBlank() }
            ?: meta.description?.takeIf { it.isNotBlank() }
            ?: item.episodeDescription,
        episodeThumbnail = video?.thumbnail.normalizeImageUrl() ?: item.episodeThumbnail,
        episodeImdbRating = meta.imdbRating,
        genres = genres,
        releaseInfo = releaseInfo,
        contentLanguage = normalizeLanguageCode(meta.language)
            ?: countryToLanguageCode(meta.country)
            ?: item.contentLanguage
    )
}

private suspend fun HomeViewModel.enrichNextUpItem(
    item: ContinueWatchingItem.NextUp,
    metaCache: MutableMap<String, CwMetaSummary?>
): ContinueWatchingItem.NextUp {
    val progressSeed = item.info.toProgressSeed()
    val meta = resolveMetaForProgress(progressSeed, metaCache) ?: return item
    val video = resolveNextUpVideoFromMeta(progressSeed, meta)

    val released = (video?.released ?: item.info.released)?.trim()?.takeIf { it.isNotEmpty() }
    val releaseDate = parseEpisodeReleaseDate(released)
    val hasAired = hasEpisodeAired(released, fallback = item.info.hasAired)
    val releaseState = resolveNextUpReleaseState(
        seedProgress = progressSeed,
        nextSeason = video?.season ?: item.info.season,
        nextReleased = released,
        hasAired = hasAired
    )

    val enrichedInfo = item.info.copy(
        name = meta.name,
        poster = item.info.poster ?: meta.poster.normalizeImageUrl(),
        backdrop = meta.backdropUrl.normalizeImageUrl() ?: item.info.backdrop,
        logo = meta.logo.normalizeImageUrl() ?: item.info.logo,
        season = video?.season ?: item.info.season,
        episode = video?.episode ?: item.info.episode,
        videoId = video?.id?.takeIf { it.isNotBlank() } ?: item.info.videoId,
        episodeTitle = video?.title?.takeIf { it.isNotBlank() } ?: item.info.episodeTitle,
        episodeDescription = video?.overview?.takeIf { it.isNotBlank() } ?: item.info.episodeDescription,
        thumbnail = video?.thumbnail.normalizeImageUrl() ?: item.info.thumbnail,
        released = released,
        hasAired = hasAired,
        airDateLabel = if (hasAired || releaseDate == null) null else formatEpisodeAirDateLabel(releaseDate),
        imdbRating = meta.imdbRating ?: item.info.imdbRating,
        genres = meta.genres.take(3).ifEmpty { item.info.genres },
        releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.info.releaseInfo,
        sortTimestamp = releaseState.sortTimestamp,
        releaseTimestamp = releaseState.releaseTimestamp,
        isReleaseAlert = releaseState.isReleaseAlert,
        isNewSeasonRelease = releaseState.isNewSeasonRelease,
        contentLanguage = normalizeLanguageCode(meta.language)
            ?: countryToLanguageCode(meta.country)
            ?: item.info.contentLanguage
    )
    return item.copy(info = enrichedInfo)
}

private suspend fun HomeViewModel.findNextUpEpisodeFromMetaSeed(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): NextUpResolution? {
    val cacheKey = buildNextUpSeedCacheKey(progress, showUnairedNextUp)
    synchronized(cwNextUpResolutionCache) {
        if (cwNextUpResolutionCache.containsKey(cacheKey)) {
            val cached = cwNextUpResolutionCache[cacheKey]
            if (cached != null) {
                // Recompute hasAired from the release date so a resolution cached
                // while the episode was still unaired does not stay stuck as
                // "unaired" after the episode drops (same-session / same-day).
                val freshHasAired = hasEpisodeAired(cached.released, fallback = cached.hasAired)
                if (freshHasAired == cached.hasAired) return cached
                val refreshed = cached.copy(
                    hasAired = freshHasAired,
                    airDateLabel = if (freshHasAired) {
                        null
                    } else {
                        cached.airDateLabel
                            ?: cached.released?.let(::parseEpisodeReleaseDate)?.let(::formatEpisodeAirDateLabel)
                    }
                )
                cwNextUpResolutionCache[cacheKey] = refreshed
                return refreshed
            }
            // Negative cache entry — check TTL
            if (isNextUpResolutionLive(
                    cwNextUpResolutionCache,
                    cwNextUpNegativeCacheTimestamps,
                    cacheKey,
                    SystemClock.elapsedRealtime()
                )
            ) {
                return null
            }
            // TTL expired — retry
            cwNextUpResolutionCache.remove(cacheKey)
            cwNextUpNegativeCacheTimestamps.remove(cacheKey)
        }
    }
    val contentId = progress.contentId
    val season = progress.season
    val episode = progress.episode
    if (season == null || episode == null || season == 0) {
        synchronized(cwNextUpResolutionCache) {
            cwNextUpResolutionCache[cacheKey] = null
            cwNextUpNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
        }
        return null
    }

    val meta = resolveMetaForProgress(progress, cwMetaCache, refreshWhenDue = true) ?: run {
        synchronized(cwNextUpResolutionCache) {
            cwNextUpResolutionCache[cacheKey] = null
            cwNextUpNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
        }
        return null
    }
    val nextVideo = resolveNextUpVideoFromMeta(progress, meta, showUnairedNextUp)
    if (nextVideo == null) {
        synchronized(cwNextUpResolutionCache) {
            cwNextUpResolutionCache[cacheKey] = null
            cwNextUpNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
        }
        return null
    }

    val nextSeason = nextVideo.season ?: return null
    val nextEpisode = nextVideo.episode ?: return null
    val rawReleased = nextVideo.released?.trim()?.takeIf { it.isNotBlank() }
    val computedHasAired = hasEpisodeAired(rawReleased, fallback = true)
    val resolution = NextUpResolution(
        season = nextSeason,
        episode = nextEpisode,
        videoId = nextVideo.id.takeIf { it.isNotBlank() }
            ?: buildLightweightEpisodeVideoId(
                contentId,
                nextSeason,
                nextEpisode
            ),
        episodeTitle = nextVideo.title?.takeIf { it.isNotBlank() },
        released = rawReleased,
        hasAired = computedHasAired,
        airDateLabel = rawReleased?.let(::parseEpisodeReleaseDate)?.let { releaseDate ->
            if (computedHasAired) null
            else formatEpisodeAirDateLabel(releaseDate)
        },
        lastWatched = progress.lastWatched
    )
    synchronized(cwNextUpResolutionCache) {
        cwNextUpResolutionCache[cacheKey] = resolution
    }
    return resolution
}

private const val CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS = 7

// An episode with no date is no more watchable than one dated ahead, so a missing date counts as unaired and stays under the same setting instead of passing as aired.
internal fun isNextUpEpisodeUnaired(releaseDate: LocalDate?, today: LocalDate): Boolean =
    releaseDate == null || releaseDate.isAfter(today)

private fun resolveNextUpVideoFromMeta(
    progress: WatchProgress,
    meta: CwMetaSummary,
    showUnairedNextUp: Boolean = true
): CwVideoSummary? {
    val episodes = meta.videos
        .filter { video ->
            val season = video.season
            val episode = video.episode
            season != null && episode != null && season != 0
        }
        .sortedWith(compareBy<CwVideoSummary>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))

    if (episodes.isEmpty()) return null

    val seedSeason = progress.season
    val seedEpisode = progress.episode
    if (seedSeason == null || seedEpisode == null) return null

    var watchedIndex = episodes.indexOfFirst { it.season == seedSeason && it.episode == seedEpisode }

    // Fallback: if the seed wasn't found by season+episode
    if (watchedIndex < 0) {
        val videoId = progress.videoId.takeIf { it.isNotBlank() }
        if (videoId != null) {
            watchedIndex = episodes.indexOfFirst { it.id == videoId }
        }
        if (watchedIndex < 0) {
            // Index-based fallback: treat the seed as the Nth episode overall
            val addonSeasons = episodes.mapTo(mutableSetOf()) { it.season }
            if (seedSeason == 1 && addonSeasons.size > 1 && seedEpisode > 0) {
                val globalIndex = seedEpisode - 1 // 0-based
                if (globalIndex in episodes.indices) {
                    watchedIndex = globalIndex
                }
            }
        }
    }

    if (watchedIndex < 0) return null

    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val watchedEpisodeSeason = episodes[watchedIndex].season
    val nextVideo = episodes.drop(watchedIndex + 1).firstOrNull { video ->
        val releaseDate = parseEpisodeReleaseDate(video.released)
        val isSeasonRollover = video.season != watchedEpisodeSeason
        if (isSeasonRollover) {
            if (releaseDate == null) {
                return@firstOrNull false
            }
            if (!releaseDate.isAfter(todayLocal)) {
                return@firstOrNull true
            }
            // Match mobile: show unaired next-season episodes within 7-day window
            if (showUnairedNextUp) {
                val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(todayLocal, releaseDate)
                if (daysUntil <= CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS) {
                    return@firstOrNull true
                }
            }
            return@firstOrNull false
        }

        if (!isNextUpEpisodeUnaired(releaseDate, todayLocal)) {
            return@firstOrNull true
        }
        showUnairedNextUp
    }

    return nextVideo
}

private const val CW_META_NEGATIVE_CACHE_TTL_MS = 5 * 60_000L

/** Lets older shows whose check-again time has passed be looked up again in this run. */
private fun HomeViewModel.releaseOlderSeedsDueAgain() {
    val due = olderSeedsDueAgain(
        processedContentIds = cwProcessedOlderSeedContentIds.toSet(),
        hasBeenValidated = fullyWatchedSeriesIds::hasBeenValidated,
        isValidationFresh = fullyWatchedSeriesIds::isSeriesValidationFresh
    )
    // Upstream also prunes their expired negative next-up entries here; ours need no pruning,
    // because hasLiveNextUpResolution already ignores them.
    if (due.isNotEmpty()) cwProcessedOlderSeedContentIds.removeAll(due)
}

/**
 * Check-again time for a show with no next episode. A list that could not be refreshed is
 * tried again after the usual back-off instead of counting as checked.
 */
private fun HomeViewModel.caughtUpValidationDeadline(
    contentType: String,
    contentId: String,
    meta: CwMetaSummary
): Long {
    val nowMs = System.currentTimeMillis()
    // Same three keys cachedCwMeta reads the list from.
    val stale = listOf(contentType, "series", "tv").any { type -> "$type:$contentId" in cwMetaStaleKeys }
    return if (stale) {
        nowMs + CW_META_NEGATIVE_CACHE_TTL_MS
    } else {
        meta.caughtUpRecheckMs(nowMs)
    }
}

/**
 * Meta for [progress], cached per type:id. With [refreshWhenDue] a cached list past its
 * [cwMetaRefreshAtMs] is fetched again (never while the player is open; then it is marked
 * stale); a failed refresh keeps the older list and retries after the negative-cache back-off.
 */
private suspend fun HomeViewModel.resolveMetaForProgress(
    progress: WatchProgress,
    metaCache: MutableMap<String, CwMetaSummary?>,
    refreshWhenDue: Boolean = false
): CwMetaSummary? {
    val cacheKey = "${progress.contentType}:${progress.contentId}"
    val playerOpen = refreshWhenDue && PlayerSessionTracker.isSessionOpen.first()
    var dueSummary: CwMetaSummary? = null
    synchronized(metaCache) {
        if (metaCache.containsKey(cacheKey)) {
            val cached = metaCache[cacheKey]
            if (cached != null) {
                val due = refreshWhenDue && shouldRefreshCwMeta(
                    refreshAtMs = cwMetaRefreshAtMs[cacheKey],
                    nowMs = System.currentTimeMillis(),
                    playerOpen = false
                )
                if (!due || playerOpen) {
                    if (due) cwMetaStaleKeys += cacheKey
                    return cached
                }
                dueSummary = cached
            } else {
                val negativeCachedAt = cwMetaNegativeCacheTimestamps[cacheKey]
                if (negativeCachedAt != null &&
                    SystemClock.elapsedRealtime() - negativeCachedAt < CW_META_NEGATIVE_CACHE_TTL_MS
                ) {
                    return null
                }
                metaCache.remove(cacheKey)
                cwMetaNegativeCacheTimestamps.remove(cacheKey)
            }
        }
    }

    val idCandidates = buildList {
        add(progress.contentId)
        if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
    }.distinct()

    val typeCandidates = cwMetaTypeCandidates(progress.contentType)
    val useAllAddons = externalMetaPrefetchEnabled
    val resolved = run {
        var summary: CwMetaSummary? = null
        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(6_000L) {
                    if (useAllAddons) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    } else {
                        metaRepository.getMetaFromPrimaryAddon(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    }
                }
                if (result == null) continue
                summary = ((result as? NetworkResult.Success<*>)?.data as? Meta)?.toCwSummary()
                if (summary != null) break
            }
            if (summary != null) break
        }
        // Fallback: if primary addon failed, try all addons before giving up.
        if (summary == null && !useAllAddons) {
            for (type in typeCandidates) {
                for (candidateId in idCandidates) {
                    val fallbackResult = withTimeoutOrNull(6_000L) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    }
                    summary = ((fallbackResult as? NetworkResult.Success<*>)?.data as? Meta)?.toCwSummary()
                    if (summary != null) break
                }
                if (summary != null) break
            }
        }
        summary
    }

    synchronized(metaCache) {
        val nowMs = System.currentTimeMillis()
        val stale = dueSummary
        if (resolved != null) {
            metaCache[cacheKey] = resolved
            cwMetaNegativeCacheTimestamps.remove(cacheKey)
            cwMetaStaleKeys -= cacheKey
            cwMetaRefreshAtMs[cacheKey] = cwMetaRefreshDueAtMs(
                nextEpisodeAirMs = resolved.earliestUpcomingEpisodeMs(Instant.ofEpochMilli(nowMs)),
                nowMs = nowMs
            )
        } else if (stale != null) {
            // The older list stays cached when a refresh fails; try again after the usual
            // back-off. Skipped when a cache clear or another resolve replaced it meanwhile.
            if (metaCache[cacheKey] === stale) {
                cwMetaStaleKeys += cacheKey
                cwMetaRefreshAtMs[cacheKey] = nowMs + CW_META_NEGATIVE_CACHE_TTL_MS
            }
        } else {
            metaCache[cacheKey] = null
            cwMetaNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
        }
    }
    return resolved ?: dueSummary
}

/**
 * Lightweight badge-only resolve: fetches meta and extracts only aired (season, episode) pairs.
 * Does NOT populate cwMetaCache — keeps memory minimal for badge evaluation of many series.
 */
private suspend fun HomeViewModel.resolveBadgeEpisodes(
    contentId: String,
    contentType: String
): Set<Pair<Int, Int>>? {
    val cacheKey = "$contentType:$contentId"
    synchronized(cwBadgeEpisodeCache) {
        if (cwBadgeEpisodeCache.containsKey(cacheKey)) return cwBadgeEpisodeCache[cacheKey]
    }
    val existingSummary = cachedCwMeta(contentType, contentId)
    if (existingSummary != null) {
        val episodes = existingSummary.watchableEpisodes()
            .mapNotNull { v -> v.season?.let { s -> v.episode?.let { e -> s to e } } }
            .toSet()
        cwBadgeNextSeasonMs[contentId] = caughtUpValidationDeadline(contentType, contentId, existingSummary)
        synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = episodes }
        return episodes
    }

    val idCandidates = buildList {
        add(contentId)
        if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
    }.distinct()
    val typeCandidates = cwMetaTypeCandidates(contentType)
    val useAllAddons = externalMetaPrefetchEnabled

    for (type in typeCandidates) {
        for (candidateId in idCandidates) {
            val result = withTimeoutOrNull(2_500L) {
                if (useAllAddons) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } else {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                }
            } ?: continue
            val meta = (result as? NetworkResult.Success<*>)?.data as? Meta ?: continue
            val summary = meta.toCwSummary()
            val episodes = summary.watchableEpisodes()
                .mapNotNull { v -> v.season?.let { s -> v.episode?.let { e -> s to e } } }
                .toSet()
            cwBadgeNextSeasonMs[contentId] = summary.caughtUpRecheckMs()
            synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = episodes }
            return episodes
        }
    }
    synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = null }
    return null
}

/**
 * Meta types to try for a CW item (Spec 50 I1, ysosrs cc1c72fcb): a film is only ever a film, so it skips the
 * `series`/`tv` retries (each a 6 s addon wait on a miss); series and unknown types keep them.
 */
internal fun cwMetaTypeCandidates(contentType: String): List<String> =
    if (contentType.equals("movie", ignoreCase = true)) listOf(contentType)
    else listOf(contentType, "series", "tv").distinct()

private fun buildLightweightEpisodeVideoId(
    contentId: String,
    season: Int,
    episode: Int
): String = "$contentId:$season:$episode"

private fun buildNextUpSeedCacheKey(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): String {
    return buildString {
        append(progress.contentId.trim())
        append("|")
        append(progress.season ?: -1)
        append("|")
        append(progress.episode ?: -1)
        append("|unaired=")
        append(showUnairedNextUp)
    }
}

/**
 * Saves the CW disk snapshots and persists the metadata enrichment found for in-progress rows
 * (paired with [originalItems] by identity, see [changedInProgressMetadata]).
 */
private fun HomeViewModel.persistLocalContinueWatchingMetadata(
    originalItems: List<ContinueWatchingItem>,
    enrichedItems: List<ContinueWatchingItem>,
    pipelineProfileId: Int
) {
    val localItems = changedInProgressMetadata(originalItems, enrichedItems)

    // Use the full UI state for cache snapshots so async-injected items are included.
    val currentUiItems = _uiState.value.continueWatchingItems + _uiState.value.upcomingItems

    // Build next-up snapshot for cache
    val brokenUrls = com.nuvio.tv.ui.components.brokenImageUrls
    val nextUpSnapshot = currentUiItems.toCachedNextUpSnapshot(brokenUrls)

    // Build in-progress snapshot for cache
    val inProgressSnapshot = currentUiItems.toCachedInProgressSnapshot(brokenUrls)

    viewModelScope.launch(Dispatchers.IO) {
        if (profileManager.activeProfileId.value != pipelineProfileId) return@launch
        if (nextUpSnapshot.isNotEmpty()) {
            runCatching { cwEnrichmentCache.saveNextUpSnapshot(nextUpSnapshot, force = true) }
        }
        if (inProgressSnapshot.isNotEmpty()) {
            runCatching { cwEnrichmentCache.saveInProgressSnapshot(inProgressSnapshot, force = true) }
        }
        val persistable = localItems.filter { it.hasRenderableMetadata() }
        if (persistable.isEmpty()) return@launch
        // Metadata only, merged onto each entry as stored now: a position the player saved
        // after this cycle started is kept, and an entry removed meanwhile is not resurrected.
        runCatching {
            watchProgressRepository.mergeContinueWatchingMetadata(persistable, pipelineProfileId)
        }
    }
}

private fun WatchProgress.hasRenderableMetadata(): Boolean {
    return name.isNotBlank() || poster != null || backdrop != null || logo != null || episodeTitle != null
}

private fun NextUpInfo.toProgressSeed(): WatchProgress {
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = seedSeason ?: season,
        episode = seedEpisode ?: episode,
        episodeTitle = episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched
    )
}

/** Meta summary already resolved for a series under any of its type keys, or null. */
private fun HomeViewModel.cachedCwMeta(contentType: String, contentId: String): CwMetaSummary? =
    synchronized(cwMetaCache) {
        cwMetaCache["$contentType:$contentId"]
            ?: cwMetaCache["series:$contentId"]
            ?: cwMetaCache["tv:$contentId"]
    }

/**
 * True while a series has a next-up resolution worth reusing: a positive one, or a negative one
 * still inside its TTL. An expired negative (typically a failed meta fetch) does not count, so
 * the older-seed pass retries that series.
 */
private fun HomeViewModel.hasLiveNextUpResolution(contentId: String): Boolean {
    val nowMs = SystemClock.elapsedRealtime()
    return synchronized(cwNextUpResolutionCache) {
        cwNextUpResolutionCache.keys.any { key ->
            key.startsWith("$contentId|") &&
                isNextUpResolutionLive(cwNextUpResolutionCache, cwNextUpNegativeCacheTimestamps, key, nowMs)
        }
    }
}

/**
 * True when [resolutions] holds a reusable entry for [key]: a positive resolution, or a negative
 * one recorded (in [negativeCachedAtMs], elapsed-realtime ms) less than the negative TTL before
 * [nowMs]. An expired or untimestamped negative, or no entry, is not live.
 */
internal fun isNextUpResolutionLive(
    resolutions: Map<String, NextUpResolution?>,
    negativeCachedAtMs: Map<String, Long>,
    key: String,
    nowMs: Long
): Boolean {
    if (!resolutions.containsKey(key)) return false
    if (resolutions[key] != null) return true
    val cachedAtMs = negativeCachedAtMs[key] ?: return false
    return nowMs - cachedAtMs < CW_META_NEGATIVE_CACHE_TTL_MS
}

private fun HomeViewModel.isConcludedWithoutNextUp(
    contentId: String,
    seedSeason: Int?,
    seedEpisode: Int?
): Boolean = isSeededAtOrBefore(cwOlderSeedsWithoutNextUp[contentId], seedSeason, seedEpisode)

/**
 * True when a cached Next Up card seeded at ([seedSeason], [seedEpisode]) is covered by a seed
 * [concludedSeed] that resolved to no next episode: same or earlier seed, or no seed stamp
 * (legacy cache). A card seeded later (the user watched on) is not covered; null [concludedSeed]
 * covers nothing.
 */
internal fun isSeededAtOrBefore(
    concludedSeed: Pair<Int, Int>?,
    seedSeason: Int?,
    seedEpisode: Int?
): Boolean {
    val (season, episode) = concludedSeed ?: return false
    if (seedSeason == null || seedEpisode == null) return true
    return seedSeason < season || (seedSeason == season && seedEpisode <= episode)
}

/**
 * Keeps an older-seed item the moment it resolves, so it survives its cycle being cancelled
 * (the seed is not resolved again: its resolution is cached). Dismissed items are not kept.
 */
private fun HomeViewModel.rememberDiscoveredOlderNextUp(
    item: ContinueWatchingItem.NextUp,
    dismissedNextUp: Set<String>
) {
    if (nextUpDismissKey(item.info.contentId, item.info.seedSeason, item.info.seedEpisode) in dismissedNextUp) return
    synchronized(discoveredOlderNextUpItems) {
        discoveredOlderNextUpItems.removeAll { it.info.contentId == item.info.contentId }
        discoveredOlderNextUpItems.add(item)
    }
}

/** Overlays enrichment from the disk-cached row of the same episode, if there is one. */
private fun ContinueWatchingItem.NextUp.withCachedEnrichment(
    cached: CachedNextUpItem?
): ContinueWatchingItem.NextUp {
    if (cached == null || cached.season != info.season || cached.episode != info.episode) return this
    return copy(info = info.copy(
        thumbnail = cached.thumbnail ?: info.thumbnail,
        backdrop = cached.backdrop ?: info.backdrop,
        poster = cached.poster ?: info.poster,
        logo = cached.logo ?: info.logo,
        name = cached.name.takeIf { it.isNotBlank() } ?: info.name,
        episodeDescription = cached.episodeDescription ?: info.episodeDescription,
        imdbRating = cached.imdbRating ?: info.imdbRating,
        genres = cached.genres.ifEmpty { info.genres },
        releaseInfo = cached.releaseInfo ?: info.releaseInfo,
        contentLanguage = cached.contentLanguage ?: info.contentLanguage
    ))
}

/** Applies enriched overlay from the previous enrichment cycle to avoid
 *  flickering between lightweight and enriched values during fresh builds. */
private suspend fun HomeViewModel.applyContinueWatchingEnrichmentOverlay(
    items: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    if (cwEnrichedNextUpOverlay.isEmpty() && cwEnrichedInProgressOverlay.isEmpty()) return items
    return withContext(Dispatchers.Default) {
        var sortChanged = false
        val mapped = items.map { item ->
            when (item) {
                is ContinueWatchingItem.NextUp -> {
                    val overlay = cwEnrichedNextUpOverlay[item.info.contentId] ?: return@map item
                    if (overlay.season != item.info.season || overlay.episode != item.info.episode) {
                        cwEnrichedNextUpOverlay.remove(item.info.contentId)
                        return@map item
                    }
                    // Recalculate hasAired from current time so stale overlays
                    // don't keep episodes stuck in "upcoming".
                    val effectiveOverlay = overlay.withRefreshedAiring()
                    if (effectiveOverlay.hasAired != overlay.hasAired) {
                        cwEnrichedNextUpOverlay[item.info.contentId] = effectiveOverlay
                    }
                    if (effectiveOverlay.sortTimestamp != item.info.sortTimestamp) sortChanged = true
                    item.copy(info = item.info.copy(
                        name = effectiveOverlay.name.takeIf { it.isNotBlank() } ?: item.info.name,
                        episodeTitle = effectiveOverlay.episodeTitle ?: item.info.episodeTitle,
                        episodeDescription = effectiveOverlay.episodeDescription ?: item.info.episodeDescription,
                        thumbnail = effectiveOverlay.thumbnail ?: item.info.thumbnail,
                        poster = effectiveOverlay.poster ?: item.info.poster,
                        backdrop = effectiveOverlay.backdrop ?: item.info.backdrop,
                        logo = effectiveOverlay.logo ?: item.info.logo,
                        imdbRating = effectiveOverlay.imdbRating ?: item.info.imdbRating,
                        genres = effectiveOverlay.genres.ifEmpty { item.info.genres },
                        releaseInfo = effectiveOverlay.releaseInfo ?: item.info.releaseInfo,
                        sortTimestamp = effectiveOverlay.sortTimestamp,
                        isReleaseAlert = effectiveOverlay.isReleaseAlert,
                        isNewSeasonRelease = effectiveOverlay.isNewSeasonRelease,
                        hasAired = effectiveOverlay.hasAired,
                        airDateLabel = effectiveOverlay.airDateLabel ?: item.info.airDateLabel,
                        releaseTimestamp = effectiveOverlay.releaseTimestamp ?: item.info.releaseTimestamp,
                        contentLanguage = effectiveOverlay.contentLanguage ?: item.info.contentLanguage
                    ))
                }
                is ContinueWatchingItem.InProgress -> {
                    val overlay = cwEnrichedInProgressOverlay[item.progress.contentId] ?: return@map item
                    if (overlay.progress.season != item.progress.season || overlay.progress.episode != item.progress.episode) return@map item
                    item.copy(
                        progress = item.progress.copy(
                            name = overlay.progress.name.takeIf { it.isNotBlank() } ?: item.progress.name,
                            poster = overlay.progress.poster ?: item.progress.poster,
                            backdrop = overlay.progress.backdrop ?: item.progress.backdrop,
                            logo = overlay.progress.logo ?: item.progress.logo,
                            episodeTitle = overlay.progress.episodeTitle ?: item.progress.episodeTitle
                        ),
                        episodeThumbnail = overlay.episodeThumbnail ?: item.episodeThumbnail,
                        episodeDescription = overlay.episodeDescription ?: item.episodeDescription,
                        episodeImdbRating = overlay.episodeImdbRating ?: item.episodeImdbRating,
                        genres = overlay.genres.ifEmpty { item.genres },
                        releaseInfo = overlay.releaseInfo ?: item.releaseInfo,
                        contentLanguage = overlay.contentLanguage ?: item.contentLanguage
                    )
                }
            }
        }
        if (sortChanged) sortContinueWatchingItems(mapped, continueWatchingSortMode) else mapped
    }
}

/**
 * Clears a stale "fully watched" checkmark after Continue Watching resolves an
 * aired next episode the user has not watched yet (typical mid-season drop day).
 *
 * Also injects that episode into the badge episode cache so a later
 * [publishBadgeUpdate] pass does not re-add the badge from a stale aired list.
 */
private fun HomeViewModel.clearStaleFullyWatchedForAiredNextUp(
    contentId: String,
    nextSeason: Int?,
    nextEpisode: Int?
) {
    val toRemove = buildSet {
        add(contentId)
        if (contentId.startsWith("tt")) {
            tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
                add("tmdb:$tmdbId")
            }
        }
    }
    // The check is a cheap pre-filter; the removal itself runs on the holder's current IDs.
    if (toRemove.any { it in fullyWatchedSeriesIds.fullyWatchedSeriesIds.value }) {
        fullyWatchedSeriesIds.transformWithValidation(
            validatedIds = toRemove,
            revalidateAt = toRemove.associateWith { Long.MAX_VALUE }
        ) { current -> current - toRemove }
    }
    synchronized(cwBadgeEpisodeCache) {
        listOf("series:$contentId", "tv:$contentId").forEach { key ->
            val existing = cwBadgeEpisodeCache[key] ?: return@forEach
            mergeAiredNextEpisodeIntoBadgeCache(existing, nextSeason, nextEpisode)?.let {
                cwBadgeEpisodeCache[key] = it
            }
        }
        // If only the tv: key was populated, mirror onto series: for publishBadgeUpdate.
        val seriesKey = "series:$contentId"
        if (!cwBadgeEpisodeCache.containsKey(seriesKey)) {
            val fromTv = cwBadgeEpisodeCache["tv:$contentId"]
            mergeAiredNextEpisodeIntoBadgeCache(fromTv, nextSeason, nextEpisode)?.let {
                cwBadgeEpisodeCache[seriesKey] = it
            }
        }
    }
}

private fun HomeViewModel.publishBadgeUpdate(
    allWatchedEpisodes: Map<String, Set<Pair<Int, Int>>>
) {
    val validatedNotFullyWatched = mutableSetOf<String>()
    val updatedFullyWatched = allWatchedEpisodes.keys
        .filter { contentId ->
            val cacheKey = "series:$contentId"
            val airedEpisodes = synchronized(cwBadgeEpisodeCache) {
                cwBadgeEpisodeCache[cacheKey] ?: cwBadgeEpisodeCache["tv:$contentId"]
            } ?: return@filter false
            if (airedEpisodes.isEmpty()) return@filter false
            val watched = allWatchedEpisodes[contentId] ?: return@filter false
            val allWatched = isFullyWatchedAgainstAiredList(airedEpisodes, watched)
            val fullyWatchedByCount = !allWatched &&
                watched.size >= airedEpisodes.size
            if (!allWatched && !fullyWatchedByCount && watched.isNotEmpty()) {
                validatedNotFullyWatched.add(contentId)
            }
            allWatched || fullyWatchedByCount
        }
        .toSet()
    // Expand IDs: for each fully-watched IMDB ID, also include the
    // "tmdb:<id>" variant so catalogs that use TMDB IDs get the badge too.
    val expandedFullyWatched = buildSet {
        addAll(updatedFullyWatched)
        for (contentId in updatedFullyWatched) {
            if (contentId.startsWith("tt")) {
                tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
                    add("tmdb:$tmdbId")
                }
            }
        }
    }
    val expandedNotFullyWatched = buildSet {
        addAll(validatedNotFullyWatched)
        for (contentId in validatedNotFullyWatched) {
            if (contentId.startsWith("tt")) {
                tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
                    add("tmdb:$tmdbId")
                }
            }
        }
    }
    // A fully-watched id gets a new deadline only when its list has been read in this ViewModel
    // (cwBadgeNextSeasonMs holds its check-again time) or it was never validated, so an
    // unresolved show keeps its earlier check-again time.
    val allValidatedIds = expandedFullyWatched.filterTo(mutableSetOf()) { contentId ->
        cwBadgeNextSeasonMs.containsKey(contentId) || !fullyWatchedSeriesIds.hasBeenValidated(contentId)
    } + expandedNotFullyWatched
    val revalidateAt = buildMap {
        for (contentId in expandedFullyWatched) {
            // Check-again time: caughtUpRecheckMs (next release, else 1 d / 7 d ended), or
            // the 5 min back-off while the list is stale (caughtUpValidationDeadline).
            cwBadgeNextSeasonMs[contentId]?.let { put(contentId, it) }
        }
        for (contentId in expandedNotFullyWatched) {
            put(contentId, Long.MAX_VALUE)
        }
    }
    fullyWatchedSeriesIds.transformWithValidation(allValidatedIds, revalidateAt) { current ->
        (current - expandedNotFullyWatched) + expandedFullyWatched
    }
}

/**
 * Determines whether an episode has actually aired by comparing the full release
 * instant when available. Date-only metadata becomes available on its local calendar day.
 */
private fun hasEpisodeAired(raw: String?, fallback: Boolean = true): Boolean {
    return isEpisodeReleaseAired(raw) ?: fallback
}

private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val locale = Locale.getDefault()
    val skeleton = if (releaseDate.year == todayLocal.year) "dMMM" else "dMMMy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(releaseDate)
}

private fun resolveNextUpReleaseState(
    seedProgress: WatchProgress,
    nextSeason: Int,
    nextReleased: String?,
    hasAired: Boolean
): NextUpReleaseState = nextUpReleaseState(
    hasAired = hasAired,
    releaseTimestamp = parseEpisodeReleaseInstant(nextReleased)?.toEpochMilli(),
    lastWatched = seedProgress.lastWatched,
    nextSeason = nextSeason,
    seedSeason = seedProgress.season
)

private fun String?.normalizeImageUrl(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

internal fun nextUpDismissKey(
    contentId: String,
    season: Int?,
    episode: Int?
): String {
    val id = contentId.trim()
    // G4-2: dismissal is per seed episode — dismissing the next-up card seeded
    // from S2E3 must not suppress the card that appears after the user watches
    // on (seed S2E4). UI dismiss paths, the cache/snapshot/reconciliation
    // filters and the fresh pipeline's seed pre-filter all key on the SEED
    // season/episode (stamped on NextUpInfo as seedSeason/seedEpisode). When the
    // seed is unknown (movies, legacy cache entries), fall back to the historical
    // contentId-level key so existing dismissals keep matching.
    return if (season != null && episode != null) "$id:s$season:e$episode" else id
}

private val NEXT_UP_SEED_KEY = Regex("^(.+):s(\\d+):e(\\d+)$")

/** [nextUpDismissKey] read back: content id, seed season, seed episode (null for a bare-id key). */
internal fun parseNextUpDismissKey(key: String): Triple<String, Int?, Int?> {
    val match = NEXT_UP_SEED_KEY.matchEntire(key) ?: return Triple(key, null, null)
    return Triple(match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3].toIntOrNull())
}

internal fun HomeViewModel.removeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    if (isNextUp) {
        val dismissKey = nextUpDismissKey(contentId, season, episode)
        removeContinueWatchingRows { item ->
            item is ContinueWatchingItem.NextUp &&
                nextUpDismissKey(item.info.contentId, item.info.seedSeason, item.info.seedEpisode) == dismissKey
        }
        viewModelScope.launch {
            continueWatchingSettings.addDismissedNextUpKey(dismissKey)
        }
        return
    }
    viewModelScope.launch {
        // Optimistic UI: remove the item from the CW list immediately
        // so the user sees instant feedback while the DataStore write propagates.
        removeContinueWatchingRows { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId == contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId == contentId
            }
        }
        // An in-progress row removes every progress entry of its title.
        watchProgressRepository.removeProgress(contentId = contentId)
    }
}

/** Drops matching rows from both the main CW row and the upcoming row. */
private fun HomeViewModel.removeContinueWatchingRows(predicate: (ContinueWatchingItem) -> Boolean) {
    _uiState.update { state ->
        state.copy(
            continueWatchingItems = state.continueWatchingItems.filterNot(predicate),
            upcomingItems = state.upcomingItems.filterNot(predicate)
        )
    }
}
