package com.nuvio.tv.core.player

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.ExtractorsFactory
import com.nuvio.tv.core.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.tv.core.player.autosync.AutoSyncCandidateScope
import com.nuvio.tv.core.player.autosync.AutoSyncExtractorsFactory
import com.nuvio.tv.core.player.autosync.AutoSyncResolvedTimeline
import com.nuvio.tv.core.player.autosync.AutoSyncSubtitleCandidate
import com.nuvio.tv.core.player.autosync.AutoSyncSubtitleTextRetimer
import com.nuvio.tv.core.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.core.player.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.tv.core.player.autosync.applyAutoSyncSidecarTimeline
import com.nuvio.tv.core.player.autosync.awaitAutoSyncFirstFrame
import com.nuvio.tv.core.player.autosync.replaceAutoSyncSidecarSubtitle
import com.nuvio.tv.core.player.autosync.searchAfterSelectedFailed
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spec 108 S3b/S5: the player side of AutoSync (upstream ysosrs 5575b0be0
 * PlayerRuntimeControllerAutomaticSubtitleSync). ExoPlayer: the sidecar; the libass apply is
 * deferred (Q4): an ASS/SSA subtitle drawn by libass is never retimed itself; when none fits, a
 * fitting SRT/VTT replaces it on the sidecar (D6, upstream's fallback). mpv (S5): a retimed copy
 * of the subtitle file, loaded in place of the plain one.
 */

private fun PlayerRuntimeController.autoSyncEnabled(): Boolean =
    currentPlayerSettings.subtitleAutoSyncEnabled && currentPlayerSettings.addonSubtitlesEnabled

/**
 * Wraps the stream's extractors so AutoSync observes embedded subtitle timing (output forwarded
 * unchanged). Creating the wrapper resets the cue store for [url]. Outside
 * [DolbyVisionExtractorsFactory]; the libass Matroska swap keeps it ([RewrappableExtractor]).
 */
internal fun PlayerRuntimeController.autoSyncExtractorsFactory(
    delegate: ExtractorsFactory,
    url: String,
): ExtractorsFactory =
    if (autoSyncEnabled() && url.isHttpUrl()) AutoSyncExtractorsFactory(delegate, sourceKey = url) else delegate

/**
 * Q5: starts the embedded subtitle index download after the first frame (upstream: at stream
 * open), so it never competes with startup; a later run finds it cached or joins it.
 */
internal fun PlayerRuntimeController.prefetchAutoSyncIndex() {
    if (!autoSyncEnabled()) return
    val url = currentStreamUrl
    if (!url.isHttpUrl()) return
    EmbeddedSubtitleTimelineLoader.prefetch(scope, url, currentHeaders.toMap())
}

/** [awaitAutoSyncFirstFrame] on [PlayerRuntimeController.hasRenderedFirstFrame] (a rebuild clears it). */
private suspend fun PlayerRuntimeController.awaitFirstFrameForAutoSync() {
    if (hasRenderedFirstFrame) return
    Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC waiting for first frame")
    awaitAutoSyncFirstFrame(firstFrameRendered = { hasRenderedFirstFrame })
}

/** The user (or a restore of the user's own pick) chose [subtitle]: check only it, never swap it. */
internal fun PlayerRuntimeController.runSelectedAutomaticSubtitleSync(subtitle: Subtitle) =
    maybeRunAutomaticSubtitleSync(subtitle, AutoSyncCandidateScope.SELECTED_ONLY)

/**
 * The viewer left the addon subtitle (built-in pick, subtitles off, episode switch, new stream,
 * release): the run is void and the retimed subtitle is no longer the one shown. On ExoPlayer the
 * sidecar stop clears the chip too; mpv has no sidecar, so it is cleared here.
 */
internal fun PlayerRuntimeController.cancelAutomaticSubtitleSync() {
    automaticSubtitleSyncJob?.cancel()
    automaticSubtitleSyncJob = null
    clearAutoSyncedSubtitle()
}

internal fun PlayerRuntimeController.clearAutoSyncedSubtitle() {
    if (_uiState.value.autoSyncedSubtitleKey != null) {
        _uiState.update { it.copy(autoSyncedSubtitleKey = null) }
    }
}

internal fun PlayerRuntimeController.consumeAutoSyncNoFitNotice() {
    _uiState.update { it.copy(autoSyncNoFitNoticePending = false) }
}

/**
 * Runs AutoSync for [selectedSubtitle], already selected through [selectAddonSubtitle]. ExoPlayer:
 * the sidecar's one download of its body (KE's [downloadSubtitleBody]: charset detection, scoped
 * stream headers) feeds the analysis too; the result is applied in place on the sidecar. mpv:
 * [runMpvAutomaticSubtitleSync].
 */
internal fun PlayerRuntimeController.maybeRunAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    requestedScope: AutoSyncCandidateScope = AutoSyncCandidateScope.STARTUP_SEARCH,
) {
    if (!autoSyncEnabled()) return
    if (selectedSubtitle.lang.isBlank()) return
    val sourceUrlAtStart = currentStreamUrl
    if (!sourceUrlAtStart.isHttpUrl()) return
    val mpv = isUsingMpvEngine()
    if (!mpv && _exoPlayer == null) return
    val libassAss = !mpv && selectedSubtitle.isAss() && (requestedUseLibassByUser || activePlayerUsesLibass)
    if (!mpv && !libassAss && !canAttachAddonSubtitleViaSidecar(selectedSubtitle)) return
    val selectedKey = addonSubtitleKey(selectedSubtitle)
    // Already retimed and on screen (a repeat hook for the same pick): nothing to do, and retiming
    // the retimed cues again would be wrong. mpv's retimed copy carries the plain track's title.
    // The key first: mpv's track check reads the whole track list over JNI.
    if (_uiState.value.autoSyncedSubtitleKey == selectedKey) {
        val retimedOnScreen = when {
            mpv -> isMpvAddonSubtitleTrackActive(selectedSubtitle)
            libassAss -> false
            else -> activeSidecarSubtitleKey == selectedKey
        }
        if (retimedOnScreen) return
    }
    var candidateScope = requestedScope
    if (candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH) {
        val claim = autoSyncStartupClaim
        when {
            claim == sourceUrlAtStart to selectedKey -> return
            claim?.first == sourceUrlAtStart -> candidateScope = AutoSyncCandidateScope.SELECTED_ONLY
            else -> autoSyncStartupClaim = sourceUrlAtStart to selectedKey
        }
    }
    if (mpv) {
        // The same run already going: keep it. A carried pick's restore re-fires on every progress tick
        // until mpv confirms the plain file, and restarting would throw the analysis away each time.
        val runKey = "$selectedKey|${candidateScope.name}"
        if (automaticSubtitleSyncJob?.isActive == true && mpvAutoSyncRunKey == runKey) return
        mpvAutoSyncRunKey = runKey
        runMpvAutomaticSubtitleSync(selectedSubtitle, candidateScope)
        return
    }
    // ASS/SSA drawn by libass: its own path (D6: SRT/VTT fallback; the libass apply stays deferred).
    if (libassAss) {
        runLibassAutomaticSubtitleSync(selectedSubtitle, candidateScope)
        return
    }

    automaticSubtitleSyncJob?.cancel()

    val sourceHeadersAtStart = currentHeaders.toMap()
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle).distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    // One download feeds both the sidecar renderer and the analysis: the sidecar selectAddonSubtitle
    // attached, else one started here. Its share completes with null on failure or cancellation.
    if (activeSidecarSubtitleKey != selectedKey && !startSidecarAddonSubtitle(selectedSubtitle)) return
    val selectedBodyDeferred = currentSidecarRawBodyFor(selectedKey) ?: return

    automaticSubtitleSyncJob = scope.launch {

        // The user can switch to a built-in track, turn subtitles off or open another stream while
        // this runs; the outcome must then leave their choice alone.
        fun streamUnchanged(): Boolean = currentStreamUrl == sourceUrlAtStart && !isUsingMpvEngine()
        fun stillRelevant(): Boolean =
            streamUnchanged() && _uiState.value.selectedAddonSubtitle?.let(::addonSubtitleKey) == selectedKey

        try {
            awaitFirstFrameForAutoSync()
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC start scope=${candidateScope.name} lang=${selectedSubtitle.lang} " +
                    "candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            suspend fun search(withAlternatives: Boolean) = withContext(Dispatchers.Default) {
                AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrlAtStart,
                    sourceHeaders = sourceHeadersAtStart,
                    selectedSubtitleUrl = selectedSubtitle.url,
                    selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                    selectedSubtitleBodyDeferred = selectedBodyDeferred,
                    preferredLanguage = selectedSubtitle.lang,
                    alternativeSubtitles = if (withAlternatives) {
                        candidatesAtStart.map { it.toAutoSyncCandidate() }
                    } else {
                        emptyList()
                    },
                    alternativeSubtitlesProvider = if (withAlternatives) {
                        { _uiState.value.addonSubtitles.map { it.toAutoSyncCandidate() } }
                    } else {
                        null
                    },
                    subtitleRequestHeaders = { url, headers ->
                        subtitleRequestHeadersFor(url, headers, sourceUrlAtStart, sourceHeadersAtStart)
                    },
                    onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
                )
            }
            var resolved = search(withAlternatives = candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH)
            if (resolved == null && stillRelevant() && candidateScope.searchAfterSelectedFailed(analysisOutcome)) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SYNC restored subtitle did not fit (outcome=$analysisOutcome), searching alternatives",
                )
                resolved = search(withAlternatives = true)
            }

            if (resolved == null) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC no match outcome=$analysisOutcome")
                if (!stillRelevant()) return@launch
                if (activeSidecarSubtitleKey == null) startSidecarAddonSubtitle(selectedSubtitle)
                raiseAutoSyncNoFitNotice(analysisOutcome, sourceUrlAtStart, selectedKey)
                return@launch
            }

            if (!streamUnchanged()) return@launch
            // KE: the selected subtitle itself when its URL won (another addon may list the same URL).
            val chosenSubtitle = selectedSubtitle.takeIf { it.url == resolved.subtitleUrl }
                ?: candidateByUrl[resolved.subtitleUrl]
                ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                ?: return@launch
            val chosenKey = addonSubtitleKey(chosenSubtitle)
            val activeKey = _uiState.value.selectedAddonSubtitle?.let(::addonSubtitleKey)
            if (activeKey != selectedKey && activeKey != chosenKey) return@launch

            val applied = when {
                chosenKey == selectedKey -> applyAutoSyncSidecarTimeline(
                    sidecar = this@maybeRunAutomaticSubtitleSync,
                    key = selectedKey,
                    timeline = resolved.timeline,
                )
                activeSidecarSubtitleKey == null -> {
                    // The selected sidecar is gone (its download failed): attach the winner directly.
                    val attached = startSidecarAddonSubtitle(
                        subtitle = chosenSubtitle,
                        rawBodyLoader = resolved.subtitleBody?.let { body -> suspend { body } },
                    )
                    attached && applyAutoSyncSidecarTimeline(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        key = chosenKey,
                        timeline = resolved.timeline,
                    )
                }
                else -> replaceAutoSyncSidecarSubtitle(
                    sidecar = this@maybeRunAutomaticSubtitleSync,
                    expectedCurrentKey = selectedKey,
                    replacement = chosenSubtitle,
                    rawBody = resolved.subtitleBody,
                    timeline = resolved.timeline,
                )
            }

            if (!applied) {
                // The selection is unchanged, so the selected subtitle must be the one on screen.
                if (stillRelevant() && activeSidecarSubtitleKey != selectedKey) {
                    startSidecarAddonSubtitle(selectedSubtitle)
                }
                return@launch
            }

            if (chosenKey != selectedKey) {
                _uiState.update { it.copy(selectedAddonSubtitle = chosenSubtitle, selectedSubtitleTrackIndex = -1) }
                // Also clears the restore latches, so the addon fetch's restore leaves the switch alone.
                rememberAddonSubtitleSelection(chosenSubtitle)
            }
            // A reused result keeps the viewer's own delay since it was first applied.
            if (!resolved.reused) setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            _uiState.update { it.copy(autoSyncedSubtitleKey = chosenKey) }
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC applied switched=${chosenKey != selectedKey} reused=${resolved.reused} " +
                    "source=${resolved.timeline.alignmentSource} scale=${resolved.timeline.alignmentScale} " +
                    "interceptMs=${resolved.timeline.alignmentInterceptMs.toLong()}",
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC failed: ${error.javaClass.simpleName}")
            if (!stillRelevant()) return@launch
            if (activeSidecarSubtitleKey == null) startSidecarAddonSubtitle(selectedSubtitle)
        }
    }
}

/** Raises the no-fit toast for a confirmed no fit, once per stream and subtitle (a rebuild re-runs). */
private fun PlayerRuntimeController.raiseAutoSyncNoFitNotice(
    outcome: AutoSyncAnalysisOutcome?,
    streamUrl: String,
    subtitleKey: String,
) {
    if (!autoSyncShowsNoFitNotice(outcome)) return
    val notifiedFor = "$streamUrl|$subtitleKey"
    if (autoSyncNoFitNotifiedFor == notifiedFor) return
    autoSyncNoFitNotifiedFor = notifiedFor
    _uiState.update { it.copy(autoSyncNoFitNoticePending = true) }
}

/**
 * ExoPlayer with libass drawing the selected ASS/SSA subtitle (upstream runLibassAutomaticSubtitleSync,
 * Spec 108 D6). The ASS subtitle (and, on a startup search, the other ASS candidates) is checked
 * first. A fitting ASS result is left as it is: retiming libass's own track is deferred (Q4). Only
 * when no ASS subtitle fits does a startup search try the plain SRT/VTT candidates, and a fitting one
 * replaces the ASS subtitle on the sidecar, synced: timing beats styling. A user's own pick
 * (SELECTED_ONLY) is never swapped.
 */
private fun PlayerRuntimeController.runLibassAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    candidateScope: AutoSyncCandidateScope,
) {
    automaticSubtitleSyncJob?.cancel()
    val player = _exoPlayer ?: return

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedKey = addonSubtitleKey(selectedSubtitle)
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle).distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    automaticSubtitleSyncJob = scope.launch {
        fun streamUnchanged(): Boolean =
            currentStreamUrl == sourceUrlAtStart && _exoPlayer === player && !isUsingMpvEngine()
        fun stillRelevant(): Boolean =
            streamUnchanged() && _uiState.value.selectedAddonSubtitle?.let(::addonSubtitleKey) == selectedKey

        // Set once the swap to an SRT/VTT has begun (libass's track off, selection moved).
        var swappedToKey: String? = null
        // The restore latch held while the swap is applied, so an addon fetch finishing then does not
        // re-pick the ASS; put back if the swap fails.
        var heldRestore: Subtitle? = null
        // Puts the ASS subtitle back after a failed swap, unless the viewer picked something else
        // meanwhile (any pick moves the selection off the swap's key; a user pick also cancels this job).
        fun restoreAss() {
            val key = swappedToKey ?: return
            if (!streamUnchanged() || _uiState.value.selectedAddonSubtitle?.let(::addonSubtitleKey) != key) return
            if (trackSelection.pendingRestoredAddonSubtitle == null) trackSelection.pendingRestoredAddonSubtitle = heldRestore
            if (activeSidecarSubtitleKey == key) stopSidecarAddonSubtitle()
            // Cleared first: selectAddonSubtitle skips a subtitle already in the state.
            _uiState.update { it.copy(selectedAddonSubtitle = null) }
            selectAddonSubtitle(selectedSubtitle)
        }

        try {
            awaitFirstFrameForAutoSync()
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC libass start scope=${candidateScope.name} lang=${selectedSubtitle.lang} " +
                    "candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            var startupSearch = candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH
            suspend fun search(ass: Boolean) = withContext(Dispatchers.Default) {
                AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrlAtStart,
                    sourceHeaders = sourceHeadersAtStart,
                    selectedSubtitleUrl = selectedSubtitle.url,
                    selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                    preferredLanguage = selectedSubtitle.lang,
                    alternativeSubtitles = if (startupSearch) {
                        candidatesAtStart.filter { it.isAss() == ass }.map { it.toAutoSyncCandidate() }
                    } else {
                        emptyList()
                    },
                    alternativeSubtitlesProvider = if (startupSearch) {
                        {
                            _uiState.value.addonSubtitles
                                .filter { it.isAss() == ass }
                                .map { it.toAutoSyncCandidate() }
                        }
                    } else {
                        null
                    },
                    subtitleRequestHeaders = { url, headers ->
                        subtitleRequestHeadersFor(url, headers, sourceUrlAtStart, sourceHeadersAtStart)
                    },
                    onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
                )
            }
            var resolved = search(ass = true)
            if (resolved == null && stillRelevant() && candidateScope.searchAfterSelectedFailed(analysisOutcome)) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SYNC libass restored subtitle did not fit (outcome=$analysisOutcome), searching alternatives",
                )
                startupSearch = true
                resolved = search(ass = true)
            }
            if (resolved != null) {
                // Q4: libass apply deferred; an ASS subtitle that fits keeps its own timing.
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC libass ASS fits; apply deferred, kept as is")
                return@launch
            }
            // No ASS/SSA fits: try the other formats, unless the video has nothing to compare with.
            if (
                startupSearch && stillRelevant() &&
                analysisOutcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
                analysisOutcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE &&
                _uiState.value.addonSubtitles.any { !it.isAss() }
            ) {
                resolved = search(ass = false)
            }

            if (resolved == null) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC libass no match outcome=$analysisOutcome")
                if (stillRelevant()) raiseAutoSyncNoFitNotice(analysisOutcome, sourceUrlAtStart, selectedKey)
                return@launch
            }
            // An ASS result here would be the selected one itself (the second search excludes ASS).
            val chosenSubtitle = (
                candidateByUrl[resolved.subtitleUrl]
                    ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                )?.takeIf { !it.isAss() && canAttachAddonSubtitleViaSidecar(it) }
                ?: return@launch
            if (!stillRelevant()) return@launch

            // Swap in one step on Main: libass's text track off, the SRT/VTT shown as the selection
            // (so the auto-pick, seeing an addon selected, does not re-pick the ASS while the
            // retime is applied), then the sidecar attached.
            val chosenKey = addonSubtitleKey(chosenSubtitle)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            _uiState.update { it.copy(selectedAddonSubtitle = chosenSubtitle, selectedSubtitleTrackIndex = -1) }
            swappedToKey = chosenKey
            heldRestore = trackSelection.pendingRestoredAddonSubtitle
            trackSelection.pendingRestoredAddonSubtitle = null
            deferredAutoAddonSubtitle = null
            val attached = startSidecarAddonSubtitle(
                subtitle = chosenSubtitle,
                rawBodyLoader = resolved.subtitleBody?.let { body -> suspend { body } },
            )
            val applied = attached && applyAutoSyncSidecarTimeline(
                sidecar = this@runLibassAutomaticSubtitleSync,
                key = chosenKey,
                timeline = resolved.timeline,
            )
            if (!applied) {
                restoreAss()
                return@launch
            }

            rememberAddonSubtitleSelection(chosenSubtitle)
            if (!resolved.reused) setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            _uiState.update { it.copy(autoSyncedSubtitleKey = chosenKey) }
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC libass switched to SRT/VTT source=${resolved.timeline.alignmentSource} " +
                    "scale=${resolved.timeline.alignmentScale} " +
                    "interceptMs=${resolved.timeline.alignmentInterceptMs.toLong()}",
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC libass failed: ${error.javaClass.simpleName}")
            restoreAss()
        }
    }
}

/**
 * mpv (upstream runMpvAutomaticSubtitleSync, Spec 108 S5). mpv feeds no embedded cues while it plays,
 * so only the container's subtitle index is the reference. The result is a copy of the subtitle file
 * with its times rewritten (ASS too: mpv draws it, nothing to defer), added under the plain track's
 * title so mpv shows it in place of the original and the track snapshot maps it to the same addon
 * subtitle. A user's own pick (SELECTED_ONLY) is never swapped.
 */
private fun PlayerRuntimeController.runMpvAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    candidateScope: AutoSyncCandidateScope,
) {
    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedKey = addonSubtitleKey(selectedSubtitle)
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle).distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    automaticSubtitleSyncJob = scope.launch {
        fun streamUnchanged(): Boolean = currentStreamUrl == sourceUrlAtStart && isUsingMpvEngine()
        // mpv's plain selection sets selectedAddonSubtitle only once its file is loaded, so callers
        // await it first.
        fun stillRelevant(): Boolean =
            streamUnchanged() && _uiState.value.selectedAddonSubtitle?.let(::addonSubtitleKey) == selectedKey
        // Waits until no plain selection is loading. A cancelled job ends its join too, and a newer
        // pick may have replaced it meanwhile: wait for that one as well.
        suspend fun awaitPlainSelection() {
            while (true) {
                val job = mpvAddonSubtitleSelectJob ?: return
                job.join()
                if (mpvAddonSubtitleSelectJob === job) return
            }
        }

        try {
            awaitFirstFrameForAutoSync()
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC mpv start scope=${candidateScope.name} lang=${selectedSubtitle.lang} " +
                    "candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            suspend fun search(withAlternatives: Boolean) = withContext(Dispatchers.Default) {
                AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrlAtStart,
                    sourceHeaders = sourceHeadersAtStart,
                    selectedSubtitleUrl = selectedSubtitle.url,
                    selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                    preferredLanguage = selectedSubtitle.lang,
                    alternativeSubtitles = if (withAlternatives) {
                        candidatesAtStart.map { it.toAutoSyncCandidate() }
                    } else {
                        emptyList()
                    },
                    alternativeSubtitlesProvider = if (withAlternatives) {
                        { _uiState.value.addonSubtitles.map { it.toAutoSyncCandidate() } }
                    } else {
                        null
                    },
                    subtitleRequestHeaders = { url, headers ->
                        subtitleRequestHeadersFor(url, headers, sourceUrlAtStart, sourceHeadersAtStart)
                    },
                    onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
                    allowLiveReference = false,
                )
            }
            var resolved = search(withAlternatives = candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH)
            // The plain selection loads the original file; let it land, so it is the selection checked
            // below and cannot replace the synced copy afterwards.
            awaitPlainSelection()
            if (resolved == null && stillRelevant() && candidateScope.searchAfterSelectedFailed(analysisOutcome)) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SYNC mpv restored subtitle did not fit (outcome=$analysisOutcome), searching alternatives",
                )
                resolved = search(withAlternatives = true)
                awaitPlainSelection()
            }

            if (resolved == null) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC mpv no match outcome=$analysisOutcome")
                if (stillRelevant()) raiseAutoSyncNoFitNotice(analysisOutcome, sourceUrlAtStart, selectedKey)
                return@launch
            }
            if (!stillRelevant()) return@launch
            // KE: the selected subtitle itself when its URL won (another addon may list the same URL).
            val chosenSubtitle = selectedSubtitle.takeIf { it.url == resolved.subtitleUrl }
                ?: candidateByUrl[resolved.subtitleUrl]
                ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                ?: return@launch
            val chosenKey = addonSubtitleKey(chosenSubtitle)

            val path = writeMpvAutoSyncedSubtitle(chosenSubtitle, resolved)
                ?: return@launch
            // A plain selection started while the copy was written would load after it; no suspension
            // follows this check, so the add below is the last word.
            awaitPlainSelection()
            if (!stillRelevant()) return@launch
            val wasPlaying = isPlaybackCurrentlyPlaying()
            val added = mpvView?.addAndSelectExternalSubtitle(
                url = path,
                title = buildAddonSubtitleTrackId(chosenSubtitle),
                language = PlayerSubtitleUtils.normalizeLanguageCode(chosenSubtitle.lang),
            ) == true
            if (!added) return@launch
            keepMpvPlayingIfNeeded(wasPlaying)

            if (chosenKey != selectedKey) {
                _uiState.update { it.copy(selectedAddonSubtitle = chosenSubtitle, selectedSubtitleTrackIndex = -1) }
                // Also clears the restore latches, so the addon fetch's restore leaves the switch alone.
                rememberAddonSubtitleSelection(chosenSubtitle)
            }
            // Before the track refresh: it can run inline (Main.immediate) and re-enter the auto-pick,
            // whose AutoSync hook must see this copy as retimed already.
            _uiState.update { it.copy(autoSyncedSubtitleKey = chosenKey) }
            updateMpvAvailableTracks()
            // A reused result keeps the viewer's own delay since it was first applied.
            if (!resolved.reused) setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC mpv applied switched=${chosenKey != selectedKey} reused=${resolved.reused} " +
                    "source=${resolved.timeline.alignmentSource} scale=${resolved.timeline.alignmentScale} " +
                    "interceptMs=${resolved.timeline.alignmentInterceptMs.toLong()}",
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // The plain subtitle stays on screen.
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC mpv failed: ${error.javaClass.simpleName}")
        }
    }
}

/**
 * Writes [subtitle] with the AutoSync timeline applied next to the plain mpv subtitle cache and
 * returns its path, or null when its format cannot be retimed. The name follows the content, so mpv's
 * `cached` sub-add reuses the track when the same result is applied again; older copies of the same
 * subtitle are deleted.
 */
private suspend fun PlayerRuntimeController.writeMpvAutoSyncedSubtitle(
    subtitle: Subtitle,
    resolved: AutoSyncResolvedTimeline,
): String? {
    val body = resolved.subtitleBody
        ?: downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
    val retimed = withContext(Dispatchers.Default) {
        AutoSyncSubtitleTextRetimer.retime(
            body = SubtitleMojibakeSanitizer.sanitize(body).toString(),
            timeline = resolved.timeline,
        )
    } ?: return null
    return withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "subtitles").also { it.mkdirs() }
        val prefix = mpvAutoSyncedFilePrefix(addonSubtitleKey(subtitle))
        val file = File(cacheDir, mpvAutoSyncedFileName(prefix, retimed))
        // Every older copy goes, other subtitles' too: mpv parsed each one at its sub-add, and a
        // `cached` re-add matches the name, so nothing reads them again.
        cacheDir.listFiles { candidate -> candidate.name.startsWith(MPV_AUTOSYNC_FILE_PREFIX) && candidate != file }
            ?.forEach { it.delete() }
        file.writeText(retimed, Charsets.UTF_8)
        file.absolutePath
    }
}

/** One prefix per addon subtitle, so two subtitles' copies never share a name. */
@VisibleForTesting
internal fun mpvAutoSyncedFilePrefix(subtitleKey: String): String =
    "$MPV_AUTOSYNC_FILE_PREFIX${Integer.toHexString(subtitleKey.hashCode())}_"

private const val MPV_AUTOSYNC_FILE_PREFIX = "mpv_autosync_"

/** The name follows the content (mpv's `cached` sub-add then reuses the track); the extension its format. */
@VisibleForTesting
internal fun mpvAutoSyncedFileName(prefix: String, retimed: String): String {
    val extension = when {
        retimed.trimStart().startsWith("WEBVTT") -> "vtt"
        AutoSyncSubtitleTextRetimer.isAss(retimed) -> "ass"
        else -> "srt"
    }
    return "$prefix${Integer.toHexString(retimed.hashCode())}.$extension"
}

private fun Subtitle.isAss(): Boolean = PlayerSubtitleUtils.mimeTypeFromUrl(url) == MimeTypes.TEXT_SSA

private fun Subtitle.toAutoSyncCandidate() = AutoSyncSubtitleCandidate(
    url = url,
    language = lang,
    name = addonName.ifBlank { id },
    headers = headers.orEmpty(),
)

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

/**
 * The one AutoSync result worth telling the viewer (D4 Q4): the subtitle was checked against this
 * video's own subtitles and does not fit them. Every other outcome stays silent; the viewer can
 * still use Manual sync.
 */
@VisibleForTesting
internal fun autoSyncShowsNoFitNotice(analysisOutcome: AutoSyncAnalysisOutcome?): Boolean =
    when (analysisOutcome) {
        AutoSyncAnalysisOutcome.NO_FIT -> true
        AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE,
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
        AutoSyncAnalysisOutcome.INCONCLUSIVE,
        null,
        -> false
    }
