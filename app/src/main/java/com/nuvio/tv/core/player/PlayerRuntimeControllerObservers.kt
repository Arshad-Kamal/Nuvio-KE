package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield

internal data class SubtitleFetchRequest(
    val type: String,
    val id: String,
    val videoId: String?,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?
)

internal fun PlayerRuntimeController.buildSubtitleFetchRequest(): SubtitleFetchRequest? {
    val id = contentId ?: return null
    val type = contentType ?: return null
    return SubtitleFetchRequest(
        type = type.lowercase(),
        id = id,
        videoId = currentVideoId,
        imdbId = metaImdbId,
        season = currentSeason,
        episode = currentEpisode
    )
}

internal suspend fun PlayerRuntimeController.fetchAddonSubtitlesNow(
    onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null,
    onSubtitlesEmitted: ((List<Subtitle>) -> Unit)? = null
): List<Subtitle> {
    // 1.7 audit: addon subtitles are opt-in on this fork; embedded-only otherwise.
    // nt6 race hardening: this var's only writer is the settings collector, so a
    // fetch that runs before its first emission reads a stale false and the whole
    // session silently loses external subtitles with no retry.
    // Confirm against the store before denying; zero cost when already enabled.
    if (!addonSubtitlesEnabled) {
        addonSubtitlesEnabled =
            playerSettingsDataStore.playerSettings.firstOrNull()?.addonSubtitlesEnabled == true
        if (!addonSubtitlesEnabled) return emptyList()
    }
    awaitMetaImdbIdIfNeeded(contentId, metaImdbId, metaFetchJob)
    val request = buildSubtitleFetchRequest() ?: return emptyList()
    // Snapshot with the request: both describe the stream this fetch serves.
    val streamSubtitles = currentStreamSubtitles
    val installedAddonOrder = addonRepository.getInstalledAddons().firstOrNull()
        ?.enabledAddons()
        ?.map { it.displayName }
        .orEmpty()
    _uiState.update { it.copy(installedSubtitleAddonOrder = installedAddonOrder) }

    // Compute hash lazily for providers that support OpenSubtitles-style matching.
    if (currentVideoHash == null && currentStreamUrl.isNotBlank()) {
        val hashedStreamUrl = currentStreamUrl
        val result = OpenSubtitlesHasher.compute(hashedStreamUrl, currentHeaders)
        // A stream switch during the hash suspend makes the result the old
        // file's: drop it rather than store it under the new stream.
        if (result != null && currentStreamUrl == hashedStreamUrl) {
            currentVideoHash = result.hash
            // The hasher read the file's exact size: it replaces a size parsed from the stream
            // text (effectiveSizeBytes), and the hash and size sent to subtitle addons must pair.
            if (currentVideoSize != result.fileSize) {
                currentVideoSize = result.fileSize
                // The size badge. Already on Main (fetchAddonSubtitles launches on the
                // ViewModel scope; the hash returns from IO), the player's thread.
                refreshOsdTechBadges()
            }
            val key = streamCacheKey
            // Same gate as persistSelectedStreamForReuse/persistTorrentStreamForReuse:
            // the cache is only read when last-link reuse is on. A stream the runtime guard
            // rejected while this hash ran stays forgotten (Spec 42 P2).
            if (key != null && streamReuseLastLinkEnabled && hashedStreamUrl !in rejectedStreamUrls) {
                val state = _uiState.value
                val torrentInfoHash = currentInfoHash
                if (isTorrentStream && torrentInfoHash != null) {
                    streamLinkCacheDataStore.save(
                        contentKey = key,
                        url = "",
                        streamName = state.currentStreamName ?: title,
                        headers = emptyMap(),
                        filename = currentFilename,
                        videoHash = currentVideoHash,
                        videoSize = currentVideoSize,
                        infoHash = torrentInfoHash,
                        fileIdx = currentFileIdx,
                        sources = currentTorrentSources,
                        bingeGroup = currentStreamBingeGroup,
                        contentLanguage = contentLanguage,
                        year = year
                    )
                } else if (currentStreamUrl.isNotBlank()) {
                    streamLinkCacheDataStore.save(
                        contentKey = key,
                        url = currentStreamUrl,
                        streamName = state.currentStreamName ?: title,
                        headers = currentHeaders,
                        filename = currentFilename,
                        videoHash = currentVideoHash,
                        videoSize = currentVideoSize,
                        bingeGroup = currentStreamBingeGroup,
                        contentLanguage = contentLanguage,
                        year = year
                    )
                }
            }
        }
    }

    if (streamSubtitles.isNotEmpty()) onSubtitlesEmitted?.invoke(streamSubtitles)
    val addonSubtitles = subtitleRepository.getSubtitles(
        type = request.type,
        id = request.id,
        videoId = request.videoId,
        imdbId = request.imdbId,
        season = request.season,
        episode = request.episode,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        filename = currentFilename,
        onProgress = onProgress,
        onSubtitlesEmitted = onSubtitlesEmitted?.let { emit ->
            { snapshot: List<Subtitle> -> emit(mergeStreamSubtitles(streamSubtitles, snapshot)) }
        }
    )
    return mergeStreamSubtitles(streamSubtitles, addonSubtitles)
}

/**
 * metaImdbId is set by the async metaFetchJob; on a cold meta cache a subtitle
 * fetch for tmdb:/kitsu:/mal: content would skip tt-only subtitle addons with no
 * refetch, so wait for that job (bounded, as the engine pick does). No wait when
 * the content id is already IMDb, the id is known, or the job is done.
 */
internal suspend fun awaitMetaImdbIdIfNeeded(
    contentId: String?,
    metaImdbId: String?,
    metaJob: Job?,
    timeoutMs: Long = META_FETCH_JOIN_TIMEOUT_MS
) {
    if (metaImdbId != null || contentId == null || contentId.startsWith("tt")) return
    if (metaJob?.isActive != true) return
    withTimeoutOrNull(timeoutMs) { metaJob.join() }
}

/**
 * The playing stream's own subtitles (shipped with this exact file) lead the
 * subtitle-addon results; one entry per url. With no stream subtitles the addon
 * list passes through untouched.
 */
internal fun mergeStreamSubtitles(
    streamSubtitles: List<Subtitle>,
    addonSubtitles: List<Subtitle>
): List<Subtitle> =
    if (streamSubtitles.isEmpty()) addonSubtitles else (streamSubtitles + addonSubtitles).distinctBy { it.url }

internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    if (buildSubtitleFetchRequest() == null) return

    // One fetch at a time: a superseded fetch must not write stale results.
    addonSubtitleFetchJob?.cancel()
    addonSubtitleFetchJob = scope.launch {
        // nt6 race hardening: gate check runs inside the coroutine so it can
        // suspend to confirm against the store (see fetchAddonSubtitlesNow).
        if (!addonSubtitlesEnabled &&
            playerSettingsDataStore.playerSettings.firstOrNull()?.addonSubtitlesEnabled != true
        ) {
            // A superseded fetch may have left the flag set; nothing else clears
            // it on this exit.
            _uiState.update { it.copy(isLoadingAddonSubtitles = false) }
            return@launch
        }
        _uiState.update { it.copy(isLoadingAddonSubtitles = true) }

        try {
            val subtitles = fetchAddonSubtitlesNow(
                onSubtitlesEmitted = { currentList ->
                    val visibleSubtitles = filterToVisibleAddonSubtitles(currentList)
                    _uiState.update { it.copy(addonSubtitles = visibleSubtitles) }
                }
            )
            val visibleSubtitles = filterToVisibleAddonSubtitles(subtitles)
            Log.d(PlayerRuntimeController.TAG, "fetchAddonSubtitles done: ${subtitles.size} subs, visible=${visibleSubtitles.size}, persistedPref=${trackSelection.persistedTrackPreference?.subtitle?.javaClass?.simpleName}")
            _uiState.update {
                it.copy(
                    addonSubtitles = visibleSubtitles,
                    isLoadingAddonSubtitles = false
                )
            }
            val pendingAddon = trackSelection.pendingRestoredAddonSubtitle
            if (pendingAddon != null) {
                val match = visibleSubtitles.firstOrNull { it.id == pendingAddon.id }
                    ?: visibleSubtitles.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.lang, pendingAddon.lang) }
                if (match != null) {
                    autoSelectAddonSubtitleDeferringReload(match)
                    return@launch
                }
            }
            applyPersistedTrackPreference(
                audioTracks = _uiState.value.audioTracks,
                subtitleTracks = _uiState.value.subtitleTracks
            )
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (e: CancellationException) {
            // Superseded or stream changed: the canceller owns the loading state.
            throw e
        } catch (_: Exception) {
            _uiState.update {
                it.copy(isLoadingAddonSubtitles = false)
            }
        }
    }
}

// nt6: on Exo, attaching an addon subtitle that is neither sidecar-capable nor
// pre-attached at startup runs a full setMediaSource+prepare at the current
// position (see selectAddonSubtitle) - a mid-playback transition that latches
// bad frame pacing on some vendor HALs (the Prism+ class; the nt5 fix covered
// audio switches under tunnelling only). Auto-restore must never trigger that
// while the user is actively watching: seamless cases apply immediately,
// reload cases park the pick and attach at the next user pause. Explicit user
// picks are untouched - someone choosing a subtitle accepts the hiccup.
internal fun PlayerRuntimeController.autoSelectAddonSubtitleDeferringReload(subtitle: Subtitle) {
    val seamless = isSeamlessAddonSubtitleAttach(
        usingMpv = isUsingMpvEngine(),
        preAttached = attachedAddonSubtitleKeys.contains(addonSubtitleKey(subtitle)),
        sidecarAttachable = canAttachAddonSubtitleViaSidecar(subtitle),
        playing = isPlaybackCurrentlyPlaying()
    )
    if (seamless) {
        trackSelection.autoSubtitleSelected = true
        selectAddonSubtitle(subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
        return
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "deferred addon subtitle attach (would reload mid-play): id=${subtitle.id} lang=${subtitle.lang}"
    )
    deferredAutoAddonSubtitle = subtitle
}

/**
 * True when auto-attaching an addon subtitle cannot reload the media mid-play:
 * MPV adds tracks live, pre-attached and sidecar subtitles skip setMediaSource,
 * and a reload while not playing is invisible.
 */
internal fun isSeamlessAddonSubtitleAttach(
    usingMpv: Boolean,
    preAttached: Boolean,
    sidecarAttachable: Boolean,
    playing: Boolean
): Boolean = usingMpv || preAttached || sidecarAttachable || !playing

internal fun PlayerRuntimeController.maybeAttachDeferredAddonSubtitle() {
    val subtitle = deferredAutoAddonSubtitle ?: return
    deferredAutoAddonSubtitle = null
    Log.i(
        PlayerRuntimeController.TAG,
        "attaching deferred addon subtitle at pause: id=${subtitle.id} lang=${subtitle.lang}"
    )
    trackSelection.autoSubtitleSelected = true
    selectAddonSubtitle(subtitle)
    _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
}

/**
 * Subtitle state reset for an episode switch, HTTP and torrent alike (Spec 21 P4, 11/L1-67; before
 * P4 it ran for torrents only). Resets the per-stream latches and keeps subtitles off when the user
 * turned them off. The remembered subtitle carries unchanged: it only ever holds the title's chosen
 * subtitle (an explicit pick, or the persisted one rememberAudioSelection copies in), and dropping it
 * made the next persist save "no subtitle" over that choice (review dddf64e1). No fetch: initializePlayer fetches the new episode's
 * addon subtitles (fetching here too was the torrent path's double fetch).
 */
internal fun PlayerRuntimeController.resetSubtitlesForEpisodeSwitch() {
    trackSelection.episodeSwitchSubtitlesReset()
    deferredAutoAddonSubtitle = null
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    resetSubtitleAutoSyncState()
    attachedAddonSubtitleKeys = emptySet()
    stopSidecarAddonSubtitle(clearView = true)
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            // Reset to no selection; track selection re-runs on the new
            // player (keepDisabled only decides whether subtitles stay off,
            // not which index to select).
            selectedSubtitleTrackIndex = -1,
            // A fetch cancelled by the stream change may have left it set; a torrent
            // whose TorrServer start fails never reaches the init that clears it.
            isLoadingAddonSubtitles = false
        )
    }
}

internal fun PlayerRuntimeController.filterToVisibleAddonSubtitles(
    subtitles: List<Subtitle>
): List<Subtitle> {
    val style = _uiState.value.subtitleStyle
    if (!style.showOnlyPreferredLanguages) return subtitles

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(style.preferredLanguage)) {
        "none" -> listOfNotNull(
            style.secondaryPreferredLanguage?.takeIf { it.isNotBlank() },
            if (style.useForcedSubtitles) {
                selectedAudioTrackForSubtitleMatching(_uiState.value)
                    ?.takeIf { selectedAudioMatchesResolvedPreferredAudio(it) }
                    ?.let { selectedAudioLanguageTarget(it) }
            } else {
                null
            }
        )
        else -> listOfNotNull(
            style.preferredLanguage,
            style.secondaryPreferredLanguage?.takeIf { it.isNotBlank() }
        )
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        .distinct()

    if (preferredTargets.isEmpty()) {
        return if (
            style.useForcedSubtitles &&
            PlayerSubtitleUtils.normalizeLanguageCode(style.preferredLanguage) == "none" &&
            selectedAudioTrackForSubtitleMatching(_uiState.value) == null
        ) {
            subtitles
        } else {
            emptyList()
        }
    }

    return subtitles.filter { subtitle ->
        preferredTargets.any { target ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
        }
    }
}

internal fun PlayerRuntimeController.observeBlurUnwatchedEpisodes() {
    scope.launch {
        layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
            _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
        }
    }
}

internal fun PlayerRuntimeController.observeEpisodeWatchProgress() {
    val id = contentId ?: return
    val type = contentType ?: return
    if (type.lowercase() != "series") return
    val baseId = id.split(":").firstOrNull() ?: id
    scope.launch {
        watchProgressRepository.getAllEpisodeProgress(baseId).collectLatest { progressMap ->
            _uiState.update { it.copy(episodeWatchProgressMap = progressMap) }
        }
    }
    scope.launch {
        watchedItemsPreferences.getWatchedEpisodesForContent(baseId).collectLatest { watchedSet ->
            _uiState.update { it.copy(watchedEpisodeKeys = watchedSet) }
        }
    }
}

internal fun PlayerRuntimeController.observeSubtitleSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collect { settings ->
            currentPlayerSettings = settings
            val currentState = _uiState.value
            val showOnlyPreferredLanguagesChanged =
                currentState.subtitleStyle.showOnlyPreferredLanguages != settings.subtitleStyle.showOnlyPreferredLanguages
            val wasRememberingAudioDelayPerDevice = rememberAudioDelayPerDeviceEnabled
            rememberAudioDelayPerDeviceEnabled = settings.rememberAudioDelayPerDevice
            val resolvedInternalPlayerEngine =
                runtimeInternalPlayerEngineOverride ?: sessionPlayerEngine ?: settings.internalPlayerEngine
            val resolvedAudioAmplificationDb = when {
                !hasInitializedAudioAmplificationForSession -> {
                    hasInitializedAudioAmplificationForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.audioAmplificationDb
                    } else {
                        AUDIO_AMPLIFICATION_MIN_DB
                    }
                }
                settings.persistAudioAmplification -> settings.audioAmplificationDb
                else -> currentState.audioAmplificationDb
            }
            val resolvedCenterMixLevelDb = when {
                !hasInitializedCenterMixForSession -> {
                    hasInitializedCenterMixForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.centerMixLevelDb
                    } else {
                        0
                    }
                }
                settings.persistAudioAmplification -> settings.centerMixLevelDb
                else -> currentState.centerMixLevelDb
            }

            _uiState.update { state ->
                val shouldShowOverlay = when {
                    !settings.loadingOverlayEnabled -> false
                    !hasRenderedFirstFrame && state.isBuffering -> true
                    else -> state.showLoadingOverlay
                }

                state.copy(
                    subtitleStyle = settings.subtitleStyle,
                    loadingOverlayEnabled = settings.loadingOverlayEnabled,
                    showPlayerLoadingStatus = settings.showPlayerLoadingStatus,
                    showLoadingOverlay = shouldShowOverlay,
                    pauseOverlayEnabled = settings.pauseOverlayEnabled,
                    osdClockEnabled = settings.osdClockEnabled,
                    internalPlayerEngine = resolvedInternalPlayerEngine,
                    frameRateMatchingMode = settings.frameRateMatchingMode,
                    tunnelingEnabled = settings.effectiveTunnelingEnabled &&
                            resolvedInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER &&
                            !isTunnelingSuppressedForDvConversion,
                    persistAudioAmplification = settings.persistAudioAmplification,
                    audioAmplificationDb = resolvedAudioAmplificationDb,
                    centerMixLevelDb = resolvedCenterMixLevelDb
                )
            }

            if (resolvedAudioAmplificationDb != currentState.audioAmplificationDb) {
                applyAudioAmplification(resolvedAudioAmplificationDb)
            }
            if (resolvedCenterMixLevelDb != currentState.centerMixLevelDb) {
                applyCenterMixLevel(resolvedCenterMixLevelDb)
            }

            if (settings.rememberAudioDelayPerDevice && !wasRememberingAudioDelayPerDevice) {
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            }

            bufferLogsEnabled = settings.enableBufferLogs
            if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
                _uiState.update {
                    it.copy(
                        detectedFrameRate = 0f,
                        detectedFrameRateSource = null,
                        afrProbeRunning = false,
                        // AFR off restores the display (PlayerScreen): the applied mode no longer
                        // holds, so the OSD frame-rate badge loses its tick.
                        displayModeInfo = null
                    )
                }
                refreshOsdTechBadges()
            }

            if (!settings.pauseOverlayEnabled) {
                cancelPauseOverlay()
            } else if (!_uiState.value.isPlaying &&
                !_uiState.value.showPauseOverlay && pauseOverlayJob == null &&
                userPausedManually && hasRenderedFirstFrame
            ) {
                schedulePauseOverlay()
            }
            streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled
            autoSwitchInternalPlayerOnErrorEnabled = settings.autoSwitchInternalPlayerOnError
            addonSubtitlesEnabled = settings.addonSubtitlesEnabled
            currentInternalPlayerEngine = resolvedInternalPlayerEngine
            streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
            _uiState.update {
                it.copy(
                    streamAutoPlayMode = settings.streamAutoPlayMode,
                    streamAutoPlayNextEpisodeEnabled = settings.streamAutoPlayNextEpisodeEnabled,
                    streamAutoPlayPreferBingeGroupForNextEpisode = settings.streamAutoPlayPreferBingeGroupForNextEpisode
                )
            }
            streamAutoPlayPreferBingeGroupForNextEpisodeSetting =
                settings.streamAutoPlayPreferBingeGroupForNextEpisode
            nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
            nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
            nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd
            stillWatchingEnabledSetting = settings.stillWatchingEnabled
            stillWatchingEpisodeThresholdSetting = settings.stillWatchingEpisodeThreshold

            val previousMpvHardwareDecodeMode = mpvHardwareDecodeModeSetting
            mpvHardwareDecodeModeSetting = settings.mpvHardwareDecodeMode
            if (isUsingMpvEngine() && previousMpvHardwareDecodeMode != mpvHardwareDecodeModeSetting) {
                mpvView?.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
            }

            val resolvedAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = settings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            if (resolvedAudioLanguages != mpvPreferredAudioLanguages) {
                mpvPreferredAudioLanguages = resolvedAudioLanguages
                if (isUsingMpvEngine()) {
                    mpvView?.applyAudioLanguagePreferences(resolvedAudioLanguages)
                    updateMpvAvailableTracks()
                }
            }

            val subtitlePreferenceChanged =
                lastSubtitlePreferredLanguage != settings.subtitleStyle.preferredLanguage ||
                    lastSubtitleSecondaryLanguage != settings.subtitleStyle.secondaryPreferredLanguage ||
                    lastUseForcedSubtitles != settings.subtitleStyle.useForcedSubtitles
            if (subtitlePreferenceChanged) {
                // Apply only on an actual preference change: this collector
                // re-emits on every playerSettings write (e.g. the first-frame
                // diagnostics persist), and re-applying preferred=none
                // mid-playback disabled the TEXT track type under a live
                // selection (capture 2026-09-04 23:39) before the none-branch
                // learned to skip an active selection.
                applySubtitlePreferences(
                    settings.subtitleStyle.preferredLanguage,
                    settings.subtitleStyle.secondaryPreferredLanguage
                )
                if (!trackSelection.subtitleDisabledByPersistedPreference && !trackSelection.subtitleAddonRestoredByPersistedPreference) trackSelection.autoSubtitleSelected = false
                lastSubtitlePreferredLanguage = settings.subtitleStyle.preferredLanguage
                lastSubtitleSecondaryLanguage = settings.subtitleStyle.secondaryPreferredLanguage
                lastUseForcedSubtitles = settings.subtitleStyle.useForcedSubtitles
                tryAutoSelectPreferredSubtitleFromAvailableTracks()
            }

            if (showOnlyPreferredLanguagesChanged) {
                if (settings.subtitleStyle.showOnlyPreferredLanguages) {
                    _uiState.update { state ->
                        val visibleSubtitles = filterToVisibleAddonSubtitles(state.addonSubtitles)
                        state.copy(
                            addonSubtitles = visibleSubtitles,
                            selectedAddonSubtitle = state.selectedAddonSubtitle?.takeIf { selected ->
                                visibleSubtitles.any { it.id == selected.id }
                            }
                        )
                    }
                } else if (_uiState.value.addonSubtitles.isNotEmpty() || _uiState.value.selectedAddonSubtitle != null) {
                    fetchAddonSubtitles()
                }
            }

            val wasEnabled = skipIntroEnabled
            skipIntroEnabled = settings.skipIntroEnabled
            parentalGuideEnabled = settings.parentalGuideEnabled
            autoSkipSegmentTypes = settings.autoSkipSegmentTypes
            playerSettingsInitialized = true

            // Fetch parental guide once per content id from this collector (after
            // we know whether the feature is enabled). The latch matters because
            // this collector re-emits on every playerSettings write and warnings
            // stay empty for titles without a guide (the repository caches only
            // hits). Episode/source switches refetch directly, not through here.
            if (settings.parentalGuideEnabled && _uiState.value.parentalWarnings.isEmpty() &&
                parentalGuideFetchedKey != contentId
            ) {
                parentalGuideFetchedKey = contentId
                fetchParentalGuide(contentId)
            }

            if (!skipIntroEnabled) {
                // Always, so a reply still in flight is dropped by fetchSkipIntervals' key check.
                skipIntroFetchedKey = null
                if (skipIntervals.isNotEmpty() || _uiState.value.activeSkipInterval != null) {
                    skipIntervals = emptyList()
                    autoSkippedIntervalKeys.clear()
                    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
                }
            } else {
                if (!wasEnabled || skipIntroFetchedKey == null) {
                    _uiState.update { it.copy(skipIntervalDismissed = false) }
                    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.loadSavedProgressFor(season: Int?, episode: Int?) {
    val progressContentId = contentId ?: return

    scope.launch {
        pendingResumeProgress = null
        val progress = if (season != null && episode != null) {
            watchProgressRepository.getEpisodeProgress(progressContentId, season, episode).firstOrNull()
        } else {
            watchProgressRepository.getProgress(progressContentId).firstOrNull()
        }

        progress?.let { saved ->

            if (saved.isInProgress()) {
                pendingResumeProgress = saved
                if (isUsingMpvEngine()) {
                    _uiState.update { it.copy(pendingSeekPosition = null) }
                    mpvView?.let { view ->
                        applyPendingMpvSeekIfNeeded(view)
                    }
                } else {
                    _exoPlayer?.let { player ->
                        if (player.playbackState == Player.STATE_READY) {
                            tryApplyPendingResumeProgress(player)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Suspend variant of [loadSavedProgressFor] that completes the DB read inline
 * instead of launching a fire-and-forget coroutine.
 *
 * [preparePlaybackBeforeStart] launches this concurrently (savedProgressDeferred) and
 * [initializePlayer] joins it via [awaitSavedProgressLoad] before reading the resume
 * position, so [pendingResumeProgress] is set before ExoPlayer's `STATE_READY` fires.
 * The fire-and-forget version races against the player lifecycle and can lose the
 * resume position entirely.
 */
internal suspend fun PlayerRuntimeController.loadSavedProgressSuspend(season: Int?, episode: Int?) {
    val progressContentId = contentId ?: return

    pendingResumeProgress = null
    val progress = if (season != null && episode != null) {
        watchProgressRepository.getEpisodeProgress(progressContentId, season, episode).firstOrNull()
    } else {
        watchProgressRepository.getProgress(progressContentId).firstOrNull()
    }

    progress?.let { saved ->
        if (saved.isInProgress()) {
            pendingResumeProgress = saved
            Log.d(
                PlayerRuntimeController.TAG,
                "loadSavedProgressSuspend: set pendingResumeProgress " +
                    "position=${saved.position} duration=${saved.duration} " +
                    "percent=${saved.progressPercent} S${season}E${episode}"
            )
        }
    }
}

/**
 * nt7 (task 2): join the saved-progress read launched at
 * preparePlaybackBeforeStart. Called on both engine branches of
 * initializePlayer immediately before the resume position is read. The
 * runway to that point is the whole player build, so the residual block
 * here should be ~0 and is logged per play so the win stays measurable.
 *
 * A failed read is swallowed: losing the resume position must not kill
 * playback. (Previously an exception here killed the whole prep
 * coroutine and playback never started -- a strictly worse outcome.)
 * Cancellation of the AWAITING coroutine is rethrown so cancellation
 * stays cooperative; only cancellation of the deferred itself (a newer
 * press superseding this one) is swallowed.
 */
internal suspend fun PlayerRuntimeController.awaitSavedProgressLoad() {
    val deferred = savedProgressDeferred ?: return
    val awaitT0 = android.os.SystemClock.elapsedRealtime()
    try {
        deferred.await()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        if (!deferred.isCancelled) throw ce
    } catch (e: Exception) {
        Log.d(
            PlayerRuntimeController.TAG,
            "awaitSavedProgressLoad: read failed, starting without resume: ${e.message}"
        )
    }
    savedProgressDeferred = null
    android.util.Log.i(
        "TTFF_STAGE",
        "SAVED_PROGRESS_AWAIT ms=${android.os.SystemClock.elapsedRealtime() - awaitT0}"
    )
}

internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    if (id.isNullOrBlank()) return

    // Prefer videoId over contentId — videoId carries the season/episode-specific ID
    val effectiveId = currentVideoId?.takeIf { it.isNotBlank() } ?: id

    val imdbId = effectiveId.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return
    if (season == null || episode == null) return

    val key = "$imdbId:$season:$episode"
    if (skipIntroFetchedKey == key) return
    skipIntroFetchedKey = key

    scope.launch {
        val fetchT0 = android.os.SystemClock.elapsedRealtime()
        val fetched = withTimeoutOrNull(15_000L) {
            skipIntroRepository.getSkipIntervals(imdbId, season, episode)
        } ?: emptyList()
        // The reply can outlive its episode (an episode switch resets the key and fetches
        // again; turning skip intro off clears it): only the current key's reply applies,
        // or the previous episode's skip times land on this one.
        if (skipIntroFetchedKey != key) {
            android.util.Log.i("TTFF_STAGE", "SKIP_INTERVALS stale reply dropped key=$key")
            return@launch
        }
        skipIntervals = fetched
        // The nt4 capture could not answer why the next-episode card fired at
        // the 99% threshold rather than at the start of a two-minute credit
        // roll. SkipIntroRepository logs only its no-data path, and at DEBUG,
        // so silence was consistent with three different worlds: intervals
        // returned but no outro among them, no data at all, or the 15 s
        // timeout elapsing. This line separates them. Logged at INFO under
        // TTFF_STAGE so it lands in the standing capture filter.
        //
        // PlayerNextEpisodeRules is deliberately NOT instrumented: it is a
        // pure object under unit test, and android.util.Log there would throw
        // in the JVM test source set. The interval list is the input that
        // decides its branch, so logging it here is sufficient.
        val intervalTypes = if (skipIntervals.isEmpty()) {
            "-"
        } else {
            skipIntervals.joinToString(",") { it.type }
        }
        android.util.Log.i(
            "TTFF_STAGE",
            "SKIP_INTERVALS n=${skipIntervals.size} types=$intervalTypes " +
                "ms=${android.os.SystemClock.elapsedRealtime() - fetchT0} key=$key"
        )
    }
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgress(player: Player) {
    val saved = pendingResumeProgress ?: return
    if (!player.isCurrentMediaItemSeekable) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = null) }
        return
    }
    val duration = player.duration
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }

    if (target > 0L) {
        player.seekTo(target)
        resumePositionApplied = true
    }
    _uiState.update { it.copy(pendingSeekPosition = null) }
    pendingResumeProgress = null
}

internal fun PlayerRuntimeController.resolvePendingInitialResumePosition(): Long {
    val saved = pendingResumeProgress ?: return 0L
    val target = when {
        saved.duration > 0L -> saved.resolveResumePosition(saved.duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }
    if (target <= 0L && saved.progressPercent == null) {
        clearPendingInitialResumePosition()
    }
    return target.coerceAtLeast(0L)
}

internal fun PlayerRuntimeController.clearPendingInitialResumePosition() {
    pendingResumeProgress = null
    _uiState.update { it.copy(pendingSeekPosition = null) }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamFromStartAfter416() {
    hasRetriedCurrentStreamAfter416 = true
    pendingResumeProgress = null
    scheduleDeferredPlayerReinitialize("HTTP 416", fromPositionMs = 0L, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.cancelFirstFrameWatchdog() {
    firstFrameWatchdogJob?.cancel()
    firstFrameWatchdogJob = null
}

internal fun PlayerRuntimeController.cancelStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
}

internal fun PlayerRuntimeController.cancelStartupWatchdog() {
    startupWatchdogJob?.cancel()
    startupWatchdogJob = null
}

/**
 * Startup watchdog (nt34): armed when the stream starts loading, disarmed at first
 * frame (real onRenderedFirstFrame or the tunneled synthetic first frame at READY)
 * and on stream reset/reinit. Checks every [PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS]
 * and extends instead of firing while buffered-ahead keeps growing — a slow 4K cold
 * start that is still pulling data is not a wedge (T3, ysosrs 01b6b4bae) — up to the
 * hard ceiling [PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS]. At fire it surfaces
 * an actionable error matched to track state (T3, ysosrs 859ecf718) instead of an
 * infinite spinner. Deliberately does NOT auto-retry: the known trigger is the vendor
 * Codec2 service wedging during decoder allocation, where a reinit just hangs again; the
 * honest remedy is telling the user (a device restart clears the wedge). One interval
 * after a surfaced fire it stops the player if still no first frame (Spec 56 R1): a
 * prepared player kept buffering behind the error screen (+50 MB, 568 MB peak on the
 * 2 GB Shield). Until then a late first frame still retracts the error (T3). Exo engine
 * path only, matching the arm site.
 */
internal fun PlayerRuntimeController.scheduleStartupWatchdog() {
    cancelStartupWatchdog()
    startupWatchdogJob = scope.launch {
        // T3 (ysosrs 01b6b4bae, rev2): extend-with-ceiling on buffered-AHEAD growth.
        // Absolute bufferedPosition misfires on every resume (position opens at the
        // resume offset) and measures the timeline, not data flow. totalBufferedDuration
        // is the buffered-ahead amount in ms — a delta, resume-safe by construction (the
        // analytics layer already treats it as buffered-ahead). If it grew since the
        // last check and another full interval fits inside the ceiling, re-arm instead
        // of firing. Monotonic clock: a wall-clock step (NTP re-sync — common right
        // after the device restart that clears the nt34 wedge) must not skew the gate.
        val armedAtMs = android.os.SystemClock.elapsedRealtime()
        var lastBufferedAheadMs = 0L
        while (isActive) {
            delay(PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS)
            if (hasRenderedFirstFrame) return@launch
            val livePlayer = _exoPlayer ?: return@launch
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - armedAtMs
            val bufferedAheadMs = livePlayer.totalBufferedDuration.coerceAtLeast(0L)
            if (
                PlayerStartupWatchdogPolicy.shouldExtend(
                    elapsedMs = elapsedMs,
                    bufferedAheadMs = bufferedAheadMs,
                    lastBufferedAheadMs = lastBufferedAheadMs,
                )
            ) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "STARTUP_WATCHDOG: no first frame ${elapsedMs}ms after starting_stream " +
                        "but buffered-ahead growing (${lastBufferedAheadMs}ms -> ${bufferedAheadMs}ms); " +
                        "extending (ceiling=${PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS}ms)"
                )
                com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_extended")
                lastBufferedAheadMs = bufferedAheadMs
                continue
            }
            val stateName = livePlayer.playbackState.playbackStateName()
            // T3 (ysosrs 859ecf718): say something true — the fire message reflects
            // whether the container headers ever arrived and whether the video format
            // was accepted, instead of always blaming the decoder.
            val fireReason = PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = hasScannedTextTracksOnce,
                    streamHasVideoTrack = currentStreamHasVideoTrack,
                    videoTrackSelected = currentVideoTrackSelected,
                )
            )
            val fireMessage = context.getString(PlayerStartupWatchdogPolicy.messageRes(fireReason))
            _uiState.update {
                if (it.error == null) {
                    it.copy(
                        error = fireMessage,
                        showLoadingOverlay = false
                    )
                } else {
                    it
                }
            }
            // Committed state decides the wording — if another error already held the
            // slot, the watchdog fired but nothing surfaced (log/trace must not claim
            // an error the user never saw).
            val surfaced = _uiState.value.error == fireMessage
            Log.w(
                PlayerRuntimeController.TAG,
                "STARTUP_WATCHDOG: no first frame ${elapsedMs}ms " +
                    "after starting_stream (state=$stateName bufferedAheadMs=${bufferedAheadMs} " +
                    "pos=${livePlayer.currentPosition} reason=${fireReason.logToken} " +
                    "scannedTracks=$hasScannedTextTracksOnce hasVideoTrack=$currentStreamHasVideoTrack " +
                    "videoSelected=$currentVideoTrackSelected); " +
                    if (surfaced) "surfacing error" else "error already present, not surfacing"
            )
            com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_fired")
            if (surfaced) stopPlayerAfterStartupGrace(livePlayer, fireMessage)
            return@launch
        }
    }
}

/**
 * Spec 56 R1: one interval after a surfaced fire, stop the player if the case is unchanged
 * ([PlayerStartupWatchdogPolicy.shouldStopAfterGrace]). stop(), not release: it frees codecs,
 * loads and buffers (the state a fatal onPlayerError leaves) and keeps the instance for Go
 * Back's normal release. No progress to save: saveWatchProgress needs a first frame. Runs
 * inside the watchdog job, so first frame, reinit and release cancel it like the watchdog.
 */
private suspend fun PlayerRuntimeController.stopPlayerAfterStartupGrace(
    livePlayer: ExoPlayer,
    fireMessage: String,
) {
    delay(PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS)
    if (
        !PlayerStartupWatchdogPolicy.shouldStopAfterGrace(
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            samePlayer = _exoPlayer === livePlayer,
            currentError = _uiState.value.error,
            fireMessage = fireMessage,
        )
    ) {
        return
    }
    Log.w(
        PlayerRuntimeController.TAG,
        "STARTUP_WATCHDOG: still no first frame " +
            "${PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS}ms after the error surfaced " +
            "(state=${livePlayer.playbackState.playbackStateName()} " +
            "bufferedAheadMs=${livePlayer.totalBufferedDuration}); stopping the player"
    )
    livePlayer.stop()
    com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_stopped")
}

/**
 * T3 (ysosrs a3fc28a82 + 859ecf718): the startup watchdog does not stop the player at
 * fire (only one interval later, Spec 56 R1), so a slow-but-healthy start can render its
 * first frame after the watchdog has already surfaced the startup error — leaving a
 * stale error screen over running playback that nothing ever cleared. The rendered frame
 * is ground truth: retract exactly the watchdog's own messages, whichever variant fired.
 * Any other error (a real decode failure racing the frame) is left untouched.
 */
internal fun PlayerRuntimeController.retractStartupWatchdogErrorAfterFirstFrame() {
    val watchdogMessages = PlayerStartupWatchdogPolicy.FireReason.entries
        .map { context.getString(PlayerStartupWatchdogPolicy.messageRes(it)) }
        .toSet()
    // Guard before the CAS loop so the log/trace describe a committed transition,
    // not an attempt that update() could lose to a concurrent writer (the update
    // body still re-checks, so the state stays correct under contention).
    val current = _uiState.value.error
    if (current == null || current !in watchdogMessages) return
    _uiState.update { state ->
        if (state.error != null && state.error in watchdogMessages) {
            state.copy(error = null)
        } else {
            state
        }
    }
    Log.w(
        PlayerRuntimeController.TAG,
        "STARTUP_WATCHDOG: first frame rendered after fire; retracting startup error"
    )
    com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_retracted")
}

/** Re-seeks past the buffered edge when bufferedPosition stops advancing during buffering. */
internal fun PlayerRuntimeController.maybeScheduleStallWatchdog() {
    if (stallWatchdogJob?.isActive == true) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_BUFFERING) return

    stallWatchdogJob = scope.launch {
        var lastBufferedPosition = player.bufferedPosition
        // Monotonic clock (as the startup watchdog): wall-clock NTP steps would skew stall timing.
        var lastAdvanceAtMs = android.os.SystemClock.elapsedRealtime()

        while (isActive) {
            delay(PlayerRuntimeController.STALL_WATCHDOG_POLL_INTERVAL_MS)
            val livePlayer = _exoPlayer ?: return@launch
            if (livePlayer.playbackState != Player.STATE_BUFFERING) {
                // Buffering resolved on its own.
                return@launch
            }

            val nowMs = android.os.SystemClock.elapsedRealtime()
            val bufferedNow = livePlayer.bufferedPosition
            if (bufferedNow > lastBufferedPosition) {
                // Real progress — reset the stall timer.
                lastBufferedPosition = bufferedNow
                lastAdvanceAtMs = nowMs
                continue
            }

            val stalledForMs = nowMs - lastAdvanceAtMs
            when (
                val decision = PlayerStallWatchdogPolicy.evaluate(
                    PlayerStallWatchdogPolicy.Input(
                        bufferedPositionMs = bufferedNow,
                        playheadMs = livePlayer.currentPosition,
                        durationMs = livePlayer.duration,
                        stalledForMs = stalledForMs,
                    )
                )
            ) {
                PlayerStallWatchdogPolicy.Decision.KeepWaiting -> Unit
                PlayerStallWatchdogPolicy.Decision.SkipUnknownDuration -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "skipping self-seek because duration is unknown"
                    )
                    return@launch
                }
                PlayerStallWatchdogPolicy.Decision.SkipBufferedNotAhead -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "skipping self-seek because buffered position is not ahead"
                    )
                    return@launch
                }
                PlayerStallWatchdogPolicy.Decision.SkipTargetNotForward -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "skipping self-seek because target is not forward"
                    )
                    return@launch
                }
                is PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "seeking past buffered edge to ${decision.targetMs} to break stuck request"
                    )
                    livePlayer.seekTo(decision.targetMs)
                    return@launch
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.maybeScheduleFirstFrameWatchdog() {
    if (hasRenderedFirstFrame || !currentStreamHasVideoTrack) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_READY) return
    if (firstFrameWatchdogJob?.isActive == true) return

    firstFrameWatchdogJob = scope.launch {
        delay(PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS)

        val livePlayer = _exoPlayer ?: return@launch
        if (hasRenderedFirstFrame) return@launch
        if (livePlayer.playbackState != Player.STATE_READY) return@launch

        if (PlayerFirstFrameWatchdogPolicy.evaluate(
                PlayerFirstFrameWatchdogPolicy.Input(
                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                    currentStreamHasVideoTrack = currentStreamHasVideoTrack,
                    playbackState = livePlayer.playbackState,
                    playWhenReady = livePlayer.playWhenReady,
                    userPausedManually = userPausedManually,
                )
            ) == PlayerFirstFrameWatchdogPolicy.RecoveryAction.ForcePlayWhenReady
        ) {
            livePlayer.playWhenReady = true
            livePlayer.play()
            return@launch
        }
        if (!livePlayer.playWhenReady) return@launch

        val currentPosition = livePlayer.currentPosition
        when (
            PlayerFirstFrameCodecRecoveryPolicy.evaluateAfterWatchdogTimeout(
                PlayerFirstFrameCodecRecoveryPolicy.Input(
                    playWhenReady = livePlayer.playWhenReady,
                    isManualDv81Mode2Active = isManualDv81Mode2ActiveForCurrentPlayback,
                    dv7Mode1AlreadyForced = dv7Mode1ForcedStreamUrls.contains(currentStreamUrl),
                    currentVideoTrackIsLikelyVc1 = currentVideoTrackIsLikelyVc1,
                    isVc1SoftwareFallbackActive = isVc1SoftwareFallbackActiveForCurrentPlayback,
                    currentVideoTrackSelected = currentVideoTrackSelected,
                    isVc1TrackSelectionBypassActive = isVc1TrackSelectionBypassActiveForCurrentPlayback,
                )
            )
        ) {
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.RetryDv7Mode1 -> {
                dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
                scheduleDeferredPlayerReinitialize("first frame DV7 mode 1", currentPosition, clearResumeProgress = true)
            }
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.RetryVc1Software -> {
                vc1SoftwarePreferredStreamUrls.add(currentStreamUrl)
                scheduleDeferredPlayerReinitialize("first frame VC-1 software", currentPosition)
            }
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.RetryVc1TrackBypass -> {
                vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
                scheduleDeferredPlayerReinitialize("first frame VC-1 track bypass", currentPosition)
            }
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.None -> Unit
        }
    }
}

/**
 * Spec 21 P2: the one same-stream rebuild. Releases the player without flushing (flushPlaybackState=true
 * would stopTorrentStream() and drop the live TorrServer torrent this URL still points at), carries
 * [positionMs] as the pending seek when > 0 (0 leaves any pending seek as is; unlike
 * [scheduleDeferredPlayerReinitialize], where 0 clears it), and starts the current URL again, on
 * [engine] when given. A same-engine rebuild ([engine] null) first seeds the track carry from the
 * effective selections (Spec 21 P7; an engine switch has captured its own carry and must not be
 * overwritten); the seed reads uiState at rebuild time, which an errored Exo player still holds
 * (Media3 1.8.0 stopInternal keeps its track info). Callers own what surrounds it: the overlay,
 * errorRetryJob and any delay or probe before it. Sites that call it from inside a job releasePlayer cancels
 * (errorRetryJob, passthroughReturnCheckJob) rely on it being non-suspending: the rest still runs.
 */
internal fun PlayerRuntimeController.rebuildCurrentStream(
    reason: String,
    positionMs: Long,
    paused: Boolean,
    engine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true
) {
    if (engine == null) seedTrackPreferenceCarryForPlayerRebuild()
    releasePlayer(flushPlaybackState = false)
    if (positionMs > 0L) {
        _uiState.update { it.copy(pendingSeekPosition = positionMs) }
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "PLAYER_REBUILD reason=$reason seek=${_uiState.value.pendingSeekPosition ?: "none"} " +
            "paused=$paused engine=${engine ?: "current"}"
    )
    initializePlayer(
        url = currentStreamUrl,
        headers = currentHeaders,
        overrideInternalPlayerEngine = engine,
        allowEngineFailover = allowEngineFailover,
        startPaused = paused
    )
}

/**
 * The deferred rebuild for recovery rungs (DV/VC-1 fallbacks, safe audio, audio off, 416):
 * overlay and pending seek now (cleared when [fromPositionMs] is 0), then
 * [rebuildCurrentStream] after a yield. Keeps a user pause (Spec 50 I2: startPaused = the user's pause at
 * decision time, as every other rebuild site; was always false). One rebuild at a time: a newer schedule replaces a pending one
 * (its flags and seek are already applied), and releasePlayer cancels it (Spec 21 P7-R2), so an
 * exit or background release inside the yield never rebuilds a released player.
 */
internal fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
    reason: String,
    fromPositionMs: Long,
    clearResumeProgress: Boolean = false
) {
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    cancelStartupWatchdog()
    if (clearResumeProgress) {
        pendingResumeProgress = null
    }
    _uiState.update {
        it.copy(
            pendingSeekPosition = if (fromPositionMs > 0L) fromPositionMs else null,
            error = null,
            showLoadingOverlay = it.loadingOverlayEnabled
        )
    }
    // The runtime-mismatch warning's own pause is not a user pause: a rebuild under it plays, so the check runs
    // again and the warning comes back (red team 5e835278).
    val paused = userPausedManually && _uiState.value.runtimeMismatch == null
    deferredReinitJob?.cancel()
    deferredReinitJob = scope.launch {
        yield()
        runCatching {
            // Pending seek set (or cleared) above.
            rebuildCurrentStream(reason, positionMs = 0L, paused = paused)
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    error = e.toDisplayMessage(context),
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.observeDeviceLocalAspectMode() {
    scope.launch {
        deviceLocalPlayerPreferences.aspectMode
            .distinctUntilChanged()
            .collect { mode ->
                val currentState = _uiState.value
                if (currentState.aspectMode != mode) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "Aspect mode restored from device-local prefs: ${currentState.aspectMode} -> $mode"
                    )
                    _uiState.update { it.copy(aspectMode = mode) }
                }
            }
    }
}
