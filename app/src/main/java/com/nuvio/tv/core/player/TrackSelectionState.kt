package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch
import com.nuvio.tv.core.player.PlayerRuntimeController.PendingEngineSwitchTrackPreference
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle

/**
 * The player's track-selection state (Spec 21 P6 / 11/S1b): the preference channels the restore
 * reads ([TrackSelectionResolver]) and the latches that gate the restore, the lossless audio
 * default and the auto subtitle pick. Writes that move several fields together go through the
 * named transitions below, with two direct exceptions: single-latch writes on the auto-pick paths,
 * and the per-step latches the restore executor (`applyPersistedTrackPreference`) sets for each
 * [TrackSelectionResolver] step. Main thread only, like the controller that owns it.
 */
internal class TrackSelectionState {

    // Preference channels.
    /** This session's explicit pick (audio and/or subtitle); saved for the title. */
    var rememberedTrackPreference: TrackPreference? = null
    /** The title's saved choice, trimmed as the restore applies it. */
    var persistedTrackPreference: TrackPreference? = null
    /** The saved choice as loaded for a content id; the restore trims [persistedTrackPreference] but not this. */
    var loadedTrackPreference: Pair<String, TrackPreference>? = null
    /** Engine-switch / same-stream-rebuild carry; outranks [persistedTrackPreference] for its URL. */
    var pendingEngineSwitchTrackPreference: PendingEngineSwitchTrackPreference? = null
    var explicitSubtitleSelectionForEngineSwitch: ExplicitSubtitleSelectionForEngineSwitch? = null
    var effectiveSubtitleSelectionForEngineSwitch: ExplicitSubtitleSelectionForEngineSwitch? = null

    // Latches.
    var autoSubtitleSelected: Boolean = false
    var isUserExplicitSubtitleSelection: Boolean = false
    var subtitleDisabledByPersistedPreference: Boolean = false
    var subtitleAddonRestoredByPersistedPreference: Boolean = false
    var pendingRestoredAddonSubtitle: Subtitle? = null
    /**
     * Task 3.9: the lossless audio default runs at most once per stream, and only when
     * no remembered/persisted/carry-over audio preference was seen for it.
     */
    var losslessAudioDefaultAppliedForStream: Boolean = false
    /** Content-scoped despite the name: a source switch keeps it, an episode switch resets it. */
    var persistedAudioPreferenceSeenForStream: Boolean = false

    /** The saved choice finished loading for [contentId] at preparePlayback. */
    fun preferenceLoaded(contentId: String, loaded: TrackPreference?) {
        persistedTrackPreference = loaded
        loadedTrackPreference = loaded?.let { contentId to it }
    }

    /** What gets saved for the title (see [trackPreferenceForPersistence]). */
    fun preferenceForPersistence(contentId: String?): TrackPreference = trackPreferenceForPersistence(
        remembered = rememberedTrackPreference,
        pending = persistedTrackPreference,
        loaded = loadedTrackPreference?.takeIf { it.first == contentId }?.second
    )

    /** The user picked an audio track: it becomes the remembered pick and outranks both channels. */
    fun explicitAudioPicked(audio: RememberedTrackSelection, contentId: String?) {
        val base = preferenceForPersistence(contentId)
        pendingEngineSwitchTrackPreference = null
        persistedTrackPreference = null
        rememberedTrackPreference = base.copy(audio = audio)
    }

    /**
     * The user picked a subtitle (or turned them off): clears the persisted/restored latches, pins
     * it across an engine switch on [streamUrl] and remembers it. The base preference is read
     * before the persisted one is cleared.
     */
    fun explicitSubtitlePicked(selection: RememberedSubtitleSelection, streamUrl: String, contentId: String?) {
        val base = preferenceForPersistence(contentId)
        isUserExplicitSubtitleSelection = true
        pendingEngineSwitchTrackPreference = null
        persistedTrackPreference = null
        clearRestoreLatches()
        val pinned = ExplicitSubtitleSelectionForEngineSwitch(streamUrl = streamUrl, selection = selection)
        explicitSubtitleSelectionForEngineSwitch = pinned
        effectiveSubtitleSelectionForEngineSwitch = pinned
        rememberedTrackPreference = base.copy(subtitle = selection)
    }

    /** A manual or startup engine switch captured the effective selections ([carry] null: nothing to carry). */
    fun engineSwitchCaptured(carry: PendingEngineSwitchTrackPreference?) {
        pendingEngineSwitchTrackPreference = carry
        clearRestoreLatches()
    }

    /**
     * Start of a restore pass on [streamUrl]: drops a carry captured for another URL and returns the
     * channel to restore from, or null when there is nothing to restore.
     */
    fun beginRestore(streamUrl: String): TrackSelectionResolver.Source? {
        val resolution = TrackSelectionResolver.resolveSource(
            carry = pendingEngineSwitchTrackPreference,
            persisted = persistedTrackPreference,
            currentStreamUrl = streamUrl
        )
        if (resolution.dropStaleCarry) {
            pendingEngineSwitchTrackPreference = null
        }
        return resolution.source
    }

    /** End of a restore pass: what stays pending goes back to the channel it came from. */
    fun finishRestore(
        source: TrackSelectionResolver.Source,
        remaining: TrackPreference?,
        streamUrl: String,
        currentEngine: InternalPlayerEngine
    ) {
        when (source.channel) {
            TrackSelectionResolver.Channel.ENGINE_SWITCH_CARRY ->
                pendingEngineSwitchTrackPreference = remaining?.let { preference ->
                    PendingEngineSwitchTrackPreference(
                        streamUrl = streamUrl,
                        preference = preference,
                        sourceEngine = source.carrySourceEngine ?: currentEngine
                    )
                }
            TrackSelectionResolver.Channel.PERSISTED -> persistedTrackPreference = remaining
        }
    }

    /** Whether the lossless audio default must stay off (see [TrackSelectionResolver.defaultAudioBlocked]). */
    fun defaultAudioBlocked(): Boolean = TrackSelectionResolver.defaultAudioBlocked(
        rememberedAudio = rememberedTrackPreference?.audio,
        persistedAudio = persistedTrackPreference?.audio,
        persistedAudioSeenForContent = persistedAudioPreferenceSeenForStream,
        carryAudio = pendingEngineSwitchTrackPreference?.preference?.audio
    )

    /**
     * Any in-player switch to a new stream (source or episode): the lossless default may run again
     * on the new file. [persistedAudioPreferenceSeenForStream] is content-scoped and kept: on a
     * same-content switch a persisted audio pick consumed on the old file still outranks the default.
     */
    fun newStream() {
        losslessAudioDefaultAppliedForStream = false
        clearRestoreLatches()
    }

    /** New content (episode switch): the persisted choice and the persisted-audio-seen latch reset. */
    fun newContent() {
        persistedTrackPreference = null
        persistedAudioPreferenceSeenForStream = false
    }

    /**
     * Subtitle latches for an episode switch: subtitles stay off when the user turned them off;
     * the remembered subtitle carries unchanged (Spec 21 P4).
     */
    fun episodeSwitchSubtitlesReset() {
        val keepDisabled = rememberedTrackPreference?.subtitle == RememberedSubtitleSelection.Disabled
        autoSubtitleSelected = keepDisabled
        isUserExplicitSubtitleSelection = false
        subtitleDisabledByPersistedPreference = keepDisabled
        subtitleAddonRestoredByPersistedPreference = false
        pendingRestoredAddonSubtitle = null
    }

    /**
     * Each player start (initializePlayer's stream-start subtitle prep): the auto pick stays held
     * off only by a persisted off or addon restore; explicit picks and their engine-switch pins drop.
     */
    fun streamStartSubtitlesReset() {
        autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
        isUserExplicitSubtitleSelection = false
        explicitSubtitleSelectionForEngineSwitch = null
        effectiveSubtitleSelectionForEngineSwitch = null
    }

    private fun clearRestoreLatches() {
        subtitleDisabledByPersistedPreference = false
        subtitleAddonRestoredByPersistedPreference = false
        pendingRestoredAddonSubtitle = null
    }
}
