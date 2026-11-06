package com.nuvio.tv.ui.components.posteroptions

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.parseTmdbId
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reusable controller for the long-press / hold-down poster options menu.
 *
 * Inject into a ViewModel via Hilt and call [bind] from the VM's init with
 * `viewModelScope`. The screen renders [PosterOptionsHost] with [state] and
 * wires [show] to each card's `onLongPress`.
 */
class PosterOptionsController @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val tmdbService: TmdbService
) {
    private val _state = MutableStateFlow(PosterOptionsState())
    val state: StateFlow<PosterOptionsState> = _state.asStateFlow()

    private val targetFlow = MutableStateFlow<MetaPreview?>(null)

    private var scope: CoroutineScope? = null
    private var bound = false
    private var showJob: kotlinx.coroutines.Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bind(scope: CoroutineScope) {
        if (bound) return
        bound = true
        this.scope = scope

        targetFlow
            .flatMapLatest { item ->
                if (item == null) {
                    flowOf(false to false)
                } else {
                    val isSeries = item.apiType.equals("series", ignoreCase = true) ||
                        item.apiType.equals("tv", ignoreCase = true) ||
                        item.apiType.equals("anime", ignoreCase = true)
                    if (isSeries) {
                        combine(
                            libraryRepository.isInLibrary(item.id, item.apiType),
                            watchedSeriesStateHolder.fullyWatchedSeriesIds
                        ) { lib, watchedIds -> lib to (item.id in watchedIds) }
                    } else {
                        combine(
                            libraryRepository.isInLibrary(item.id, item.apiType),
                            watchProgressRepository.isWatched(item.id)
                        ) { lib, watched -> lib to watched }
                    }
                }
            }
            .onEach { (isInLibrary, isWatched) ->
                _state.update { current ->
                    current.copy(
                        isInLibrary = isInLibrary,
                        isWatched = isWatched
                    )
                }
            }
            .launchIn(scope)
    }

    fun show(item: MetaPreview, addonBaseUrl: String?) {
        val launchScope = this.scope ?: return
        showJob?.cancel()
        showJob = launchScope.launch {
            val canonical = canonicalize(item)
            val isSeries = canonical.apiType.equals("series", ignoreCase = true) ||
                canonical.apiType.equals("tv", ignoreCase = true) ||
                canonical.apiType.equals("anime", ignoreCase = true)
            val initialIsInLibrary = runCatching {
                libraryRepository.isInLibrary(canonical.id, canonical.apiType).first()
            }.getOrDefault(false)
            val initialIsWatched = if (isSeries) {
                canonical.id in watchedSeriesStateHolder.fullyWatchedSeriesIds.value
            } else {
                runCatching {
                    watchProgressRepository.isWatched(canonical.id).first()
                }.getOrDefault(false)
            }

            _state.update { current ->
                current.copy(
                    target = canonical,
                    originalItemId = item.id,
                    addonBaseUrl = addonBaseUrl.orEmpty(),
                    isInLibrary = initialIsInLibrary,
                    isWatched = initialIsWatched,
                    isLibraryPending = false,
                    isWatchedPending = false
                )
            }
            targetFlow.value = canonical
        }
    }

    private suspend fun canonicalize(item: MetaPreview): MetaPreview {
        if (item.id.startsWith("tt", ignoreCase = false)) return item
        val tmdbNumber = parseTmdbId(item.id) ?: return item
        val mediaType = if (item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        ) "tv" else "movie"
        val imdb = runCatching { tmdbService.tmdbToImdb(tmdbNumber, mediaType) }.getOrNull()
        return if (!imdb.isNullOrBlank()) item.copy(id = imdb) else item
    }

    private suspend fun ensureCanonical(): MetaPreview? {
        val current = _state.value.target ?: return null
        val canonical = canonicalize(current)
        if (canonical.id != current.id && _state.value.target?.id == current.id) {
            _state.update { it.copy(target = canonical) }
            targetFlow.value = canonical
        }
        return canonical
    }

    fun dismiss() {
        showJob?.cancel()
        showJob = null
        targetFlow.value = null
        _state.update { it.copy(target = null) }
    }

    fun toggleLibrary() {
        val state = _state.value
        if (state.target == null) return
        if (state.isLibraryPending) return
        val scope = this.scope ?: return

        _state.update { it.copy(isLibraryPending = true) }
        scope.launch {
            val canonical = ensureCanonical()
            if (canonical == null) {
                _state.update { it.copy(isLibraryPending = false) }
                return@launch
            }
            val input = canonical.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() })
            try {
                libraryRepository.toggleDefault(input)
                _state.update { it.copy(isLibraryPending = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to toggle library for ${canonical.id}: ${error.message}")
                _state.update { it.copy(isLibraryPending = false) }
            }
        }
    }

    fun toggleMovieWatched() {
        val state = _state.value
        val item = state.target ?: return
        if (!item.apiType.equals("movie", ignoreCase = true)) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        val currentlyWatched = state.isWatched
        // Collect all known ID variants: original UI id (e.g. "tmdb:123"),
        // canonical id (e.g. "tt1979376"), and imdbId field if available.
        val optimisticIds = buildSet {
            add(item.id)
            state.originalItemId?.takeIf { it != item.id }?.let(::add)
            item.imdbId?.takeIf(String::isNotBlank)?.let(::add)
        }

        watchProgressRepository.applyOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical()
            if (canonical == null) {
                // Target vanished mid-toggle: undo the optimistic flip — the write never ran.
                watchProgressRepository.revertOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)
                _state.update { it.copy(isWatchedPending = false) }
                return@launch
            }
            try {
                if (currentlyWatched) {
                    watchProgressRepository.removeFromHistory(canonical.id)
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(canonical))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to toggle watched for ${canonical.id}: ${error.message}")
                // Revert optimistic update on failure
                watchProgressRepository.revertOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    fun toggleSeriesWatched() {
        val state = _state.value
        val item = state.target ?: return
        val isSeries = item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        if (!isSeries) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        val currentlyWatched = state.isWatched

        // Optimistic UI update — badge changes immediately. Only this series' id is flipped,
        // under the holder's lock, so concurrent CW badge updates to other series survive
        // both this step and the revert below.
        val applyOptimistic: (Set<String>) -> Set<String> = { ids ->
            if (currentlyWatched) ids - item.id else ids + item.id
        }
        val revertOptimistic: (Set<String>) -> Set<String> = { ids ->
            if (currentlyWatched) ids + item.id else ids - item.id
        }
        watchedSeriesStateHolder.update(applyOptimistic)

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical()
            if (canonical == null) {
                // Target vanished mid-toggle: undo the optimistic badge — the write never ran.
                watchedSeriesStateHolder.update(revertOptimistic)
                _state.update { it.copy(isWatchedPending = false) }
                return@launch
            }
            var applied = false
            try {
                applied = if (currentlyWatched) {
                    unmarkSeriesWatched(canonical)
                } else {
                    markSeriesWatched(canonical)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to toggle series watched for ${canonical.id}: ${error.message}")
            }
            if (!applied) {
                // The write never landed (meta fetch failed or the write threw). The
                // optimistic badge is persisted to disk, so without this revert it would
                // keep claiming "watched" until the next CW-pipeline revalidation.
                watchedSeriesStateHolder.update(revertOptimistic)
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    /** @return true when the watched state was actually written; false when the meta fetch
     *  failed and nothing was marked (the caller must revert optimistic state). */
    private suspend fun markSeriesWatched(item: MetaPreview): Boolean {
        val episodes = fetchSeriesEpisodes(item)
            ?.filter { it.season != null && it.episode != null && it.season != 0 }
            ?: run {
                // A failed fetch must not become "series watched": that would write bogus
                // completion for every episode. Fail loudly instead.
                Log.w(TAG, "markSeriesWatched: meta fetch failed for ${item.id}; not marking")
                return false
            }
        if (episodes.isEmpty()) {
            watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
            return true
        }

        val progressList = episodes.map { video ->
            WatchProgress(
                contentId = item.id,
                contentType = item.apiType,
                name = item.name,
                poster = item.poster,
                backdrop = item.backdropUrl,
                logo = item.logo,
                videoId = video.id,
                season = video.season,
                episode = video.episode,
                episodeTitle = video.title,
                position = 1L,
                duration = 1L,
                lastWatched = System.currentTimeMillis(),
                progressPercent = 100f
            )
        }
        watchProgressRepository.markAsCompletedBatch(progressList)
        return true
    }

    /** @return true when the unwatched state was actually written. The fetch-failed branch
     *  still removes the whole-series watch state (progress AND episode flags), so it
     *  counts as applied. */
    private suspend fun unmarkSeriesWatched(item: MetaPreview): Boolean {
        val episodes = fetchSeriesEpisodes(item)
            ?.filter { it.season != null && it.episode != null && it.season != 0 }
            ?: run {
                // Fetch failed: the safe removal is the whole-series key (the same state
                // the mark path writes), not a guessed episode set.
                Log.w(TAG, "unmarkSeriesWatched: meta fetch failed for ${item.id}; removing series-level watch state")
                watchProgressRepository.removeFromHistory(item.id)
                return true
            }
        if (episodes.isEmpty()) {
            watchProgressRepository.removeFromHistory(item.id)
            return true
        }

        watchProgressRepository.removeFromHistoryBatch(
            contentId = item.id,
            episodes = episodes.map { it.season!! to it.episode!! }
        )
        return true
    }

    /** null = fetch FAILED (caller must not guess); empty list = meta loaded, no episodes. */
    private suspend fun fetchSeriesEpisodes(item: MetaPreview): List<Video>? {
        val type = if (item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        ) "series" else item.apiType
        var episodes: List<Video> = emptyList()
        var succeeded = false
        metaRepository.getMetaFromPrimaryAddon(type, item.id)
            .collect { networkResult ->
                if (networkResult is NetworkResult.Success) {
                    succeeded = true
                    episodes = networkResult.data.videos
                }
            }
        return if (succeeded) episodes else null
    }

    companion object {
        private const val TAG = "PosterOptionsCtrl"
    }
}

private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
    return WatchProgress(
        contentId = item.id,
        contentType = item.apiType,
        name = item.name,
        poster = item.poster,
        backdrop = item.backdropUrl,
        logo = item.logo,
        videoId = item.id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )
}

private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
    // The library renders as portrait. If the source MetaPreview was built for landscape
    // display (e.g. TMDB collection / more-like-this), its `poster` field holds the backdrop.
    // Prefer `rawPosterUrl` (the proper portrait) when available so the saved entry isn't a
    // stretched/cropped landscape inside a portrait card.
    val isLandscapeSource = posterShape == com.nuvio.tv.domain.model.PosterShape.LANDSCAPE
    val portraitPoster = rawPosterUrl?.takeIf { it.isNotBlank() }
    val savedPoster = if (isLandscapeSource && portraitPoster != null) portraitPoster else poster
    val savedShape = if (isLandscapeSource) com.nuvio.tv.domain.model.PosterShape.POSTER else posterShape
    return LibraryEntryInput(
        itemId = id,
        itemType = apiType,
        title = name,
        poster = savedPoster,
        posterShape = savedShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}
