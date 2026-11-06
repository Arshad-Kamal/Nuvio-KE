package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerRuntimeController.PendingEngineSwitchTrackPreference
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle

/**
 * Track-preference restore as pure decisions (Spec 21 P5 / 11/S1a). The controller
 * (`applyPersistedTrackPreference`) runs them on every tracks update of either engine.
 *
 * Precedence:
 * 1. Engine-switch carry: captured for the current stream URL by an engine switch or a
 *    same-stream rebuild; a carry for another URL is stale and dropped ([resolveSource]).
 * 2. Persisted: the title's saved choice, loaded at preparePlayback and trimmed as it applies.
 * 3. Remembered: this session's explicit pick. Not read by the restore: it is saved for the
 *    title, so the next preparePlayback reloads it as the persisted one, and the tunneled
 *    audio rebuild seeds it as a carry; in this object it only keeps the defaults off
 *    ([defaultAudioBlocked]).
 * 4. Defaults: the lossless audio default and the auto subtitle pick
 *    (`tryAutoSelectPreferredSubtitleFromAvailableTracks`, held off by the latches the
 *    subtitle steps set).
 *
 * A restore keeps a field pending until it is confirmed on the player ([remaining]); the
 * controller writes what is left back to the channel it came from. The track matchers read
 * engine state, so the controller passes them in.
 */
internal object TrackSelectionResolver {

    enum class Channel { ENGINE_SWITCH_CARRY, PERSISTED }

    /** [carrySourceEngine] is the engine the carry was captured on; null for [Channel.PERSISTED]. */
    data class Source(
        val channel: Channel,
        val preference: TrackPreference,
        val carrySourceEngine: InternalPlayerEngine?
    )

    /** [source] null = nothing to restore. [dropStaleCarry]: the carry belongs to another URL. */
    data class SourceResolution(val source: Source?, val dropStaleCarry: Boolean)

    fun resolveSource(
        carry: PendingEngineSwitchTrackPreference?,
        persisted: TrackPreference?,
        currentStreamUrl: String
    ): SourceResolution {
        val liveCarry = carry?.takeIf { it.streamUrl == currentStreamUrl }
        val dropStaleCarry = carry != null && liveCarry == null
        val source = when {
            liveCarry != null ->
                Source(Channel.ENGINE_SWITCH_CARRY, liveCarry.preference, liveCarry.sourceEngine)
            persisted != null -> Source(Channel.PERSISTED, persisted, carrySourceEngine = null)
            else -> null
        }
        return SourceResolution(source, dropStaleCarry)
    }

    sealed interface AudioStep {
        /** Keep the audio target pending for the next tracks update. */
        val keepsPending: Boolean

        /** No audio tracks yet. */
        data object Deferred : AudioStep { override val keepsPending = true }

        /** Select [index]; stays pending until a later update sees it selected. */
        data class Select(val index: Int) : AudioStep { override val keepsPending = true }

        data class AlreadySelected(val index: Int) : AudioStep { override val keepsPending = false }

        data object NoMatch : AudioStep { override val keepsPending = false }
    }

    fun audioStep(
        target: RememberedTrackSelection,
        audioTracks: List<TrackInfo>,
        matchIndex: (RememberedTrackSelection) -> Int
    ): AudioStep {
        if (audioTracks.isEmpty()) return AudioStep.Deferred
        val index = matchIndex(target)
        return when {
            index < 0 -> AudioStep.NoMatch
            audioTracks.getOrNull(index)?.isSelected == true -> AudioStep.AlreadySelected(index)
            else -> AudioStep.Select(index)
        }
    }

    sealed interface SubtitleStep {
        /** Keep the subtitle target pending for the next tracks update (see [AddonSelect]). */
        val keepsPending: Boolean

        /** Subtitles off; [reapply] when a track is still selected (then it stays pending). */
        data class Disable(val reapply: Boolean) : SubtitleStep {
            override val keepsPending get() = reapply
        }

        /** No internal subtitle tracks yet. */
        data object InternalDeferred : SubtitleStep { override val keepsPending = true }

        data class InternalSelect(val index: Int) : SubtitleStep { override val keepsPending = false }

        /** Already selected: kept pending for a pipeline restart (libass rebuild). */
        data class InternalAlreadySelected(val index: Int) : SubtitleStep {
            override val keepsPending = true
        }

        /** Exo-to-MPV carry onto MPV's sparse subtitle metadata: wait, change nothing. */
        data object InternalSparseMpvDefer : SubtitleStep { override val keepsPending = true }

        /** No internal match; an addon subtitle in the same language variant stands in. */
        data class InternalAddonFallback(val addon: Subtitle, val variant: String) : SubtitleStep {
            override val keepsPending = false
        }

        data class InternalNoMatch(val variant: String) : SubtitleStep { override val keepsPending = false }

        /**
         * Exact addon match (same addon, same id). [keepUntilMpvConfirms]: a carry onto MPV stays
         * pending until MPV reports the addon track selected. That is only known after the
         * selection, so [keepsPending] is false here and the controller asks
         * [keepsPendingAfterSelect] instead.
         */
        data class AddonSelect(val addon: Subtitle, val keepUntilMpvConfirms: Boolean) : SubtitleStep {
            override val keepsPending = false

            /** [activeInMpv] is read only when the keep is in question (it queries MPV). */
            fun keepsPendingAfterSelect(activeInMpv: () -> Boolean): Boolean =
                keepUntilMpvConfirms && !activeInMpv()
        }

        /** Addon list still loading or empty: keep it and hold the auto pick off. */
        data object AddonAwaitList : SubtitleStep { override val keepsPending = true }

        /** List loaded, no exact match: drop it and let the auto pick run. */
        data object AddonNoMatch : SubtitleStep { override val keepsPending = false }
    }

    /**
     * [carrySourceEngine] is the [Source]'s: non-null only for a carry, which then matches
     * internal tracks with the engine-switch matcher (the controller's [matchInternal]).
     */
    fun subtitleStep(
        selection: RememberedSubtitleSelection,
        subtitleTracks: List<TrackInfo>,
        addonSubtitles: List<Subtitle>,
        addonSubtitlesLoading: Boolean,
        carrySourceEngine: InternalPlayerEngine?,
        usingMpv: Boolean,
        sparseMpvSubtitleMetadata: Boolean,
        matchInternal: (RememberedTrackSelection, InternalPlayerEngine?) -> Int
    ): SubtitleStep = when (selection) {
        RememberedSubtitleSelection.Disabled ->
            SubtitleStep.Disable(reapply = subtitleTracks.any { it.isSelected })
        is RememberedSubtitleSelection.Internal -> internalSubtitleStep(
            target = selection.track,
            subtitleTracks = subtitleTracks,
            addonSubtitles = addonSubtitles,
            carrySourceEngine = carrySourceEngine,
            usingMpv = usingMpv,
            sparseMpvSubtitleMetadata = sparseMpvSubtitleMetadata,
            matchInternal = matchInternal
        )
        is RememberedSubtitleSelection.Addon -> {
            // Exact match only: a looser one (same language) must not override the auto pick,
            // which prefers embedded tracks over addon subtitles.
            val match = addonSubtitles.firstOrNull {
                it.addonName == selection.addonName && it.id == selection.id
            }
            when {
                match != null -> SubtitleStep.AddonSelect(
                    addon = match,
                    keepUntilMpvConfirms = carrySourceEngine != null && usingMpv
                )
                addonSubtitlesLoading || addonSubtitles.isEmpty() -> SubtitleStep.AddonAwaitList
                else -> SubtitleStep.AddonNoMatch
            }
        }
    }

    private fun internalSubtitleStep(
        target: RememberedTrackSelection,
        subtitleTracks: List<TrackInfo>,
        addonSubtitles: List<Subtitle>,
        carrySourceEngine: InternalPlayerEngine?,
        usingMpv: Boolean,
        sparseMpvSubtitleMetadata: Boolean,
        matchInternal: (RememberedTrackSelection, InternalPlayerEngine?) -> Int
    ): SubtitleStep {
        if (subtitleTracks.isEmpty()) return SubtitleStep.InternalDeferred
        val index = matchInternal(target, carrySourceEngine)
        if (index >= 0) {
            return if (subtitleTracks.getOrNull(index)?.isSelected == true) {
                SubtitleStep.InternalAlreadySelected(index)
            } else {
                SubtitleStep.InternalSelect(index)
            }
        }
        if (carrySourceEngine == InternalPlayerEngine.EXOPLAYER && usingMpv && sparseMpvSubtitleMetadata) {
            return SubtitleStep.InternalSparseMpvDefer
        }
        val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = target.language,
            name = target.name,
            trackId = target.trackId
        )
        val fallback = addonSubtitles.firstOrNull {
            PlayerSubtitleUtils.matchesLanguageCode(it.lang, variant)
        }
        return if (fallback != null) {
            SubtitleStep.InternalAddonFallback(fallback, variant)
        } else {
            SubtitleStep.InternalNoMatch(variant)
        }
    }

    /** What stays pending after the steps; null when nothing does. */
    fun remaining(
        pending: TrackPreference,
        keepAudio: Boolean,
        keepSubtitle: Boolean
    ): TrackPreference? = TrackPreference(
        audio = pending.audio?.takeIf { keepAudio },
        subtitle = pending.subtitle?.takeIf { keepSubtitle }
    ).takeUnless { it.audio == null && it.subtitle == null }

    /**
     * The lossless audio default stays off when any channel holds an audio pick, or an audio
     * preference was seen for this content. A subtitle-only carry protects nothing (review
     * 2026-09-04-212511 m1: it left a rebuilt player with no audio pick and no default).
     */
    fun defaultAudioBlocked(
        rememberedAudio: RememberedTrackSelection?,
        persistedAudio: RememberedTrackSelection?,
        persistedAudioSeenForContent: Boolean,
        carryAudio: RememberedTrackSelection?
    ): Boolean = rememberedAudio != null || persistedAudio != null ||
        persistedAudioSeenForContent || carryAudio != null
}
