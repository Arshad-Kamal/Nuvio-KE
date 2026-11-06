package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.C
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(
    detailedError: String,
    allowEngineFailover: Boolean
): Boolean {
    if (!allowEngineFailover) return false
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (startupEngineFailoverTriggered) return false
    if (!isStartupPhaseForEngineFailover()) return false

    // Task 1.6 (Option A): automatic engine failover is one-directional. Only
    // an active MPV session (manual switch, the MPV/AUTO engine settings, or a
    // Blu-ray .m2ts start, format-capability G2) may fail back to ExoPlayer
    // (for .m2ts that fails too and next-source failover takes over). ExoPlayer startup failures are handled by
    // the retry ladders and next-source failover instead of silently dropping
    // into MPV, which loses this fork's passthrough/DV pipeline behaviour.
    val mpvActive = currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER ||
        (currentInternalPlayerEngine == InternalPlayerEngine.AUTO && mpvView != null)
    if (!mpvActive) return false
    val targetEngine = InternalPlayerEngine.EXOPLAYER
    rememberCurrentTrackPreferenceForEngineSwitch()
    Log.w(PlayerRuntimeController.TAG, "Startup playback error; auto-switching engine: $detailedError")
    startupEngineFailoverTriggered = true

    val targetEngineLabel = targetEngineLabel(targetEngine)
    val switchMessage = context.getString(R.string.player_engine_switching_message, targetEngineLabel)

    hidePlayerEngineSwitchInfoJob?.cancel()
    showRecoveryOverlay()
    _uiState.update {
        it.copy(
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage
        )
    }

    // Failover always lands on ExoPlayer, so no MPV hard restart is pending
    // (releasePlayer clears delayMpvResumeSeekUntilVideoTrack).
    pendingMpvHardRestartOnNextAttach = false
    rebuildCurrentStream(
        "startup engine failover",
        positionMs = 0L,
        // A paused manual switch to mpv that fails over lands on Exo still paused (N7).
        paused = userPausedManually,
        engine = targetEngine,
        allowEngineFailover = false
    )
    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }

    return true
}

internal fun PlayerRuntimeController.switchInternalPlayerEngineManually() {
    if (currentStreamUrl.isBlank()) return

    val targetEngine = when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
        InternalPlayerEngine.AUTO -> if (mpvView != null) InternalPlayerEngine.EXOPLAYER else InternalPlayerEngine.MVP_PLAYER
    }
    val targetEngineLabel = targetEngineLabel(targetEngine)
    val switchMessage = context.getString(R.string.player_engine_switching_manual_message, targetEngineLabel)

    rememberCurrentTrackPreferenceForEngineSwitch()

    // Window NOTE N7 (2026-10-09): a switch made while paused stays paused (mpv's position-0
    // exception lives in PlayerStartupPlaybackPolicy.mpvStartsPaused).
    val startPaused = userPausedManually

    seedSourceSwitchResumeFromLivePosition()

    startupEngineFailoverTriggered = false
    userPausedManually = false
    resetErrorRetryState()
    hidePlayerEngineSwitchInfoJob?.cancel()
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            showControls = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleDelayOverlay = false,
            showSpeedPanel = false,
            showChaptersPanel = false,
            showMoreDialog = false,
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage,
            // A degraded notice describes the engine being left.
            degradedNotice = null
        )
    }

    val switchingToMpv = targetEngine == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = switchingToMpv
    delayMpvResumeSeekUntilVideoTrack = switchingToMpv

    // Save progress, but keep the TorrServer torrent: the new engine re-opens
    // the same URL (flushPlaybackState=true would stopTorrentStream()).
    flushPlaybackSnapshotForSwitchOrExit()
    rebuildCurrentStream("engine switch", positionMs = 0L, paused = startPaused, engine = targetEngine)

    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }
}

private fun PlayerRuntimeController.isStartupPhaseForEngineFailover(): Boolean {
    val state = _uiState.value
    val currentPosition = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: playbackTimeline.value.currentPosition
    return !hasRenderedFirstFrame && (state.showLoadingOverlay || state.isBuffering || currentPosition <= 0L)
}

private fun PlayerRuntimeController.targetEngineLabel(targetEngine: InternalPlayerEngine): String {
    return when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
        InternalPlayerEngine.AUTO -> context.getString(R.string.playback_player_auto)
    }
}

internal fun PlayerRuntimeController.clearPendingEngineSwitchTrackPreference() {
    trackSelection.pendingEngineSwitchTrackPreference = null
}

private fun PlayerRuntimeController.rememberCurrentTrackPreferenceForEngineSwitch() {
    val state = _uiState.value
    val sourceEngine = currentInternalPlayerEngine

    val rememberedAudio = captureCurrentAudioSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )
    val rememberedSubtitle = resolveCurrentSubtitleSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )

    val mergedPreference = PlayerRuntimeController.TrackPreference(
        audio = rememberedAudio,
        subtitle = rememberedSubtitle
    )
    val capturedPreference = mergedPreference.takeUnless { it.audio == null && it.subtitle == null }
    trackSelection.engineSwitchCaptured(
        capturedPreference?.let { preference ->
            PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                streamUrl = currentStreamUrl,
                preference = preference,
                sourceEngine = sourceEngine
            )
        }
    )
}

private fun PlayerRuntimeController.captureCurrentAudioSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedTrackSelection? {
    val mpvSelected = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        mpvView?.readTrackSnapshot()?.audioTracks?.firstOrNull { it.isSelected }
    } else {
        null
    }
    if (mpvSelected != null) {
        return PlayerRuntimeController.RememberedTrackSelection(
            language = mpvSelected.language,
            name = mpvSelected.name,
            trackId = mpvSelected.id.toString()
        )
    }

    val exoSelected = run {
        val player = _exoPlayer ?: return@run null
        player.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                return@run PlayerRuntimeController.RememberedTrackSelection(
                    language = format.language,
                    name = format.label ?: format.language ?: "Audio",
                    trackId = format.id
                )
            }
        }
        null
    }
    if (exoSelected != null) {
        return exoSelected
    }

    val uiTrack = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }

    return uiTrack?.let { track ->
        PlayerRuntimeController.RememberedTrackSelection(
            language = track.language,
            name = track.name,
            trackId = track.trackId
        )
    }
}

private fun PlayerRuntimeController.captureCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    data class SelectedExoTextTrack(
        val id: String?,
        val language: String?,
        val label: String?,
        val isForced: Boolean,
        val indexHint: Int?,
        val languageIndexHint: Int?
    )

    val mpvSnapshot = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) mpvView?.readTrackSnapshot() else null
    if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        val mpvSelectedSubtitle = mpvSnapshot?.subtitleTracks?.firstOrNull { it.isSelected }
        if (mpvSelectedSubtitle != null) {
            if (mpvSelectedSubtitle.isExternal) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = mpvSelectedSubtitle.name,
                    language = mpvSelectedSubtitle.language
                )
                if (addon != null) {
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
                state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = selectedAddonFromUi.id,
                        url = selectedAddonFromUi.url,
                        language = selectedAddonFromUi.lang,
                        addonName = selectedAddonFromUi.addonName
                    )
                }
            }
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = mpvSelectedSubtitle.language,
                    name = mpvSelectedSubtitle.name,
                    trackId = mpvSelectedSubtitle.id.toString(),
                    isForced = mpvSelectedSubtitle.isForced,
                    selectedUiTrackOverride = resolveUiSubtitleTrackForEngineSwitchHints(
                        state = state,
                        trackId = mpvSelectedSubtitle.id.toString(),
                        language = mpvSelectedSubtitle.language,
                        name = mpvSelectedSubtitle.name
                    ),
                    allowUiStateFallbackForHints = false
                )
            )
        }
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val exoPlayer = _exoPlayer
        val exoTextTrackDisabled =
            exoPlayer?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        val exoSelectedText = run {
            val player = exoPlayer ?: return@run null
            val internalTracks = mutableListOf<SelectedExoTextTrack>()
            var selectedTrack: SelectedExoTextTrack? = null
            player.currentTracks.groups.forEach { group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEach
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val trackId = format.id
                    val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val isAddonTrack =
                        trackId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true
                    val currentTrack = SelectedExoTextTrack(
                        id = trackId,
                        language = format.language,
                        label = format.label,
                        isForced = isForced,
                        indexHint = if (isAddonTrack) null else internalTracks.size,
                        languageIndexHint = null
                    )
                    if (!isAddonTrack) {
                        internalTracks += currentTrack
                    }
                    if (group.isTrackSelected(i)) {
                        selectedTrack = currentTrack
                    }
                }
            }
            val selected = selectedTrack ?: run {
                val preferredTargets = subtitleLanguageTargets()
                internalTracks.firstOrNull { track ->
                    preferredTargets.any { target ->
                        val normalizedTarget = target.trim().lowercase()
                        if (normalizedTarget == "forced") {
                            track.isForced
                        } else {
                            PlayerSubtitleUtils.matchesLanguageCode(track.language, target)
                        }
                    }
                } ?: internalTracks.firstOrNull { !it.isForced }
                    ?: internalTracks.firstOrNull()
            } ?: return@run null
            val selectedIndex = selected.indexHint ?: return@run selected
            val selectedInternalTrack = internalTracks.getOrNull(selectedIndex) ?: return@run selected
            val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                language = selectedInternalTrack.language,
                name = selectedInternalTrack.label ?: selectedInternalTrack.language,
                trackId = selectedInternalTrack.id
            )
            val languageCandidates = internalTracks.indices.filter { index ->
                val candidate = internalTracks[index]
                val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = candidate.language,
                    name = candidate.label ?: candidate.language,
                    trackId = candidate.id
                )
                candidateVariant == selectedVariant ||
                    (!selectedInternalTrack.language.isNullOrBlank() &&
                        PlayerSubtitleUtils.matchesLanguageCode(
                            candidate.language,
                            selectedInternalTrack.language
                        ))
            }
            selected.copy(
                languageIndexHint = languageCandidates.indexOf(selectedIndex).takeIf { it >= 0 }
            )
        }
        if (exoSelectedText == null && exoTextTrackDisabled) {
            return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        }
        if (exoSelectedText != null) {
            val formatId = exoSelectedText.id
            val formatLanguage = exoSelectedText.language
            val formatLabel = exoSelectedText.label
            if (formatId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = formatId,
                    language = formatLanguage
                )
                if (addon != null) {
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
                state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = selectedAddonFromUi.id,
                        url = selectedAddonFromUi.url,
                        language = selectedAddonFromUi.lang,
                        addonName = selectedAddonFromUi.addonName
                    )
                }
            }

            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = PlayerRuntimeController.RememberedTrackSelection(
                    language = formatLanguage,
                    name = formatLabel ?: formatLanguage ?: "Subtitle",
                    trackId = formatId,
                    indexHint = exoSelectedText.indexHint,
                    languageIndexHint = exoSelectedText.languageIndexHint,
                    isForcedHint = exoSelectedText.isForced
                )
            )
        }
    }

    val uiTrack = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
        ?: state.subtitleTracks.firstOrNull { it.isSelected }
    if (uiTrack != null) {
        return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                state = state,
                language = uiTrack.language,
                name = uiTrack.name,
                trackId = uiTrack.trackId,
                isForced = uiTrack.isForced
            )
        )
    }
    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val uiFallbackTrack = pickUiInternalSubtitleTrackForEngineSwitchFallback(state)
        if (uiFallbackTrack != null) {
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = uiFallbackTrack.language,
                    name = uiFallbackTrack.name,
                    trackId = uiFallbackTrack.trackId,
                    isForced = uiFallbackTrack.isForced,
                    selectedUiTrackOverride = uiFallbackTrack
                )
            )
        }
    }

    state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
        return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = selectedAddonFromUi.id,
            url = selectedAddonFromUi.url,
            language = selectedAddonFromUi.lang,
            addonName = selectedAddonFromUi.addonName
        )
    }

    val mpvHasSubtitleTracks = mpvSnapshot?.subtitleTracks?.isNotEmpty() == true
    val exoHasSubtitleTracks = if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        run {
            val player = _exoPlayer ?: return@run false
            player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && group.length > 0
            }
        }
    } else {
        false
    }
    if (mpvHasSubtitleTracks || exoHasSubtitleTracks) {
        return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    }

    return null
}

private fun PlayerRuntimeController.resolveCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    val capturedSelection = captureCurrentSubtitleSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )
    val shouldUseFallback =
        capturedSelection == null || capturedSelection == PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    if (!shouldUseFallback) {
        return capturedSelection
    }

    trackSelection.effectiveSubtitleSelectionForEngineSwitch
        ?.takeIf { effective -> effective.streamUrl == currentStreamUrl }
        ?.selection
        ?.let { effectiveSelection ->
            return effectiveSelection
        }

    trackSelection.explicitSubtitleSelectionForEngineSwitch
        ?.takeIf { explicit -> explicit.streamUrl == currentStreamUrl }
        ?.selection
        ?.let { explicitSelection ->
            return explicitSelection
        }

    trackSelection.rememberedTrackPreference?.subtitle?.let { rememberedSelection ->
        return rememberedSelection
    }

    return capturedSelection
}

private fun PlayerRuntimeController.pickUiInternalSubtitleTrackForEngineSwitchFallback(
    state: PlayerUiState
): TrackInfo? {
    if (state.subtitleTracks.isEmpty()) {
        return null
    }

    state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let {
        return it
    }
    state.subtitleTracks.firstOrNull { it.isSelected }?.let {
        return it
    }

    val preferredIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = subtitleLanguageTargets()
    )
    if (preferredIndex >= 0) {
        state.subtitleTracks.getOrNull(preferredIndex)?.let {
            return it
        }
    }

    state.subtitleTracks.firstOrNull { !it.isForced }?.let {
        return it
    }
    return state.subtitleTracks.firstOrNull()
}

internal fun PlayerRuntimeController.buildRememberedInternalSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    language: String?,
    name: String?,
    trackId: String?,
    isForced: Boolean,
    selectedUiTrackOverride: TrackInfo? = null,
    allowUiStateFallbackForHints: Boolean = true
): PlayerRuntimeController.RememberedTrackSelection {
    val selectedUiTrack = selectedUiTrackOverride
        ?: if (allowUiStateFallbackForHints) {
            state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
                ?: state.subtitleTracks.firstOrNull { it.isSelected }
        } else {
            null
        }

    val selection = PlayerRuntimeController.RememberedTrackSelection(
        language = language,
        name = name,
        trackId = trackId,
        indexHint = selectedUiTrack?.index?.takeIf { it >= 0 },
        languageIndexHint = selectedUiTrack?.let { track ->
            subtitleLanguageOrdinalHintForEngineSwitch(state.subtitleTracks, track)
        },
        isForcedHint = selectedUiTrack?.isForced ?: isForced
    )
    return selection
}

private fun PlayerRuntimeController.resolveUiSubtitleTrackForEngineSwitchHints(
    state: PlayerUiState,
    trackId: String?,
    language: String?,
    name: String?
): TrackInfo? {
    val normalizedTrackId = normalizeTrackMatchValue(trackId)
    if (!normalizedTrackId.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.trackId) == normalizedTrackId
        }?.let {
            return it
        }
    }

    val normalizedLanguage = normalizeTrackMatchValue(language)
    val normalizedName = normalizeTrackMatchValue(name)

    if (!normalizedLanguage.isNullOrBlank() && !normalizedName.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLanguage &&
                normalizeTrackMatchValue(track.name) == normalizedName
        }?.let {
            return it
        }
    }

    if (!normalizedLanguage.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLanguage && track.isSelected
        }?.let {
            return it
        }
    }

    return null
}

internal fun PlayerRuntimeController.subtitleLanguageOrdinalHintForEngineSwitch(
    tracks: List<TrackInfo>,
    selectedTrack: TrackInfo
): Int? {
    val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = selectedTrack.language,
        name = selectedTrack.name,
        trackId = selectedTrack.trackId
    )
    val selectedTrackPosition = tracks.indexOfFirst { it.index == selectedTrack.index }
    if (selectedTrackPosition < 0) return null

    val candidates = tracks.indices.filter { index ->
        val candidate = tracks[index]
        val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = candidate.language,
            name = candidate.name,
            trackId = candidate.trackId
        )
        candidateVariant == selectedVariant ||
            (!selectedTrack.language.isNullOrBlank() &&
                PlayerSubtitleUtils.matchesLanguageCode(candidate.language, selectedTrack.language))
    }

    val ordinal = candidates.indexOf(selectedTrackPosition).takeIf { it >= 0 }
    return ordinal
}

private fun PlayerRuntimeController.findAddonSubtitleByTrackIdOrLanguage(
    state: PlayerUiState,
    trackId: String?,
    language: String?
): com.nuvio.tv.domain.model.Subtitle? {
    val normalizedTrackId = trackId?.trim()
    if (!normalizedTrackId.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            val addonTrackId = buildAddonSubtitleTrackId(subtitle)
            addonTrackId.equals(normalizedTrackId, ignoreCase = true) ||
                normalizedTrackId.contains(addonTrackId, ignoreCase = true)
        }?.let {
            return it
        }
    }
    val lang = language?.trim()
    if (!lang.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, lang)
        }?.let {
            return it
        }
    }
    return null
}
