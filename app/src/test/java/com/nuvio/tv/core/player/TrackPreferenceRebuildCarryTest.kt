package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rebuild carry snapshot (subtitle-missing-at-startup bug, capture
 * 2026-09-04 11:33): the libass pipeline adjust releases and rebuilds the
 * player at startup, and the rebuilt player's clean track state dropped a
 * selection restored moments earlier — the persisted preference was consumed
 * and the auto path latched, so nothing re-applied it. The snapshot seeds the
 * engine-switch carry channel, which the restore consumes with top priority
 * on the rebuilt player's first TRACKS update.
 */
class TrackPreferenceRebuildCarryTest {

    private fun internalSub(index: Int, trackId: String, forced: Boolean = false) = TrackInfo(
        index = index,
        name = "English (SRT)",
        language = "eng",
        trackId = trackId,
        isForced = forced
    )

    @Test
    fun `internal subtitle and audio selections are carried with matchable identity`() {
        val state = PlayerUiState(
            audioTracks = listOf(TrackInfo(index = 0, name = "Surround 5.1", language = "eng", trackId = "1:1")),
            selectedAudioTrackIndex = 0,
            subtitleTracks = listOf(internalSub(0, "2:1"), internalSub(1, "2:2"), internalSub(2, "2:3", forced = true)),
            selectedSubtitleTrackIndex = 2
        )

        val preference = buildEffectiveTrackPreferenceSnapshot(state, subtitlesDeliberatelyDisabled = false)

        assertEquals("eng", preference?.audio?.language)
        assertEquals("1:1", preference?.audio?.trackId)
        val subtitle = preference?.subtitle as PlayerRuntimeController.RememberedSubtitleSelection.Internal
        assertEquals("2:3", subtitle.track.trackId)
        assertEquals(2, subtitle.track.indexHint)
        assertEquals(true, subtitle.track.isForcedHint)
    }

    @Test
    fun `addon subtitle selection is carried with its source identity`() {
        val state = PlayerUiState(
            selectedSubtitleTrackIndex = -1,
            selectedAddonSubtitle = Subtitle(
                id = "os-123",
                url = "https://opensubtitles.example/123.srt",
                lang = "eng",
                addonName = "OpenSubtitles",
                addonLogo = null
            )
        )

        val preference = buildEffectiveTrackPreferenceSnapshot(state, subtitlesDeliberatelyDisabled = false)

        val subtitle = preference?.subtitle as PlayerRuntimeController.RememberedSubtitleSelection.Addon
        assertEquals("os-123", subtitle.id)
        assertEquals("eng", subtitle.language)
        assertEquals("OpenSubtitles", subtitle.addonName)
    }

    @Test
    fun `a deliberate subtitle off survives the rebuild`() {
        // Without the Disabled carry, a profile with a preferred language could
        // get subtitles re-enabled on the rebuilt player that the user turned away.
        val preference = buildEffectiveTrackPreferenceSnapshot(
            PlayerUiState(),
            subtitlesDeliberatelyDisabled = true
        )

        assertTrue(preference?.subtitle is PlayerRuntimeController.RememberedSubtitleSelection.Disabled)
    }

    @Test
    fun `idle state with no deliberate off carries nothing`() {
        val preference = buildEffectiveTrackPreferenceSnapshot(
            PlayerUiState(),
            subtitlesDeliberatelyDisabled = false
        )

        assertNull(preference)
    }

    @Test
    fun `audio-only selection still carries for the audio arm`() {
        val state = PlayerUiState(
            audioTracks = listOf(TrackInfo(index = 1, name = "Stereo", language = "spa", trackId = "1:2")),
            selectedAudioTrackIndex = 0,
            selectedSubtitleTrackIndex = -1
        )

        val preference = buildEffectiveTrackPreferenceSnapshot(state, subtitlesDeliberatelyDisabled = false)

        assertEquals("1:2", preference?.audio?.trackId)
        assertNull(preference?.subtitle)
    }

    @Test
    fun `seed decision never clobbers an occupied carry channel`() {
        val state = PlayerUiState(
            subtitleTracks = listOf(internalSub(0, "2:1")),
            selectedSubtitleTrackIndex = 0
        )

        val seed = buildRebuildCarrySeed(
            state = state,
            subtitlesDeliberatelyDisabled = false,
            channelOccupied = true,
            streamUrl = "https://stream.example/file.mkv"
        )

        assertNull(seed)
    }

    @Test
    fun `seed decision no-ops when nothing is worth carrying`() {
        val seed = buildRebuildCarrySeed(
            state = PlayerUiState(),
            subtitlesDeliberatelyDisabled = false,
            channelOccupied = false,
            streamUrl = "https://stream.example/file.mkv"
        )

        assertNull(seed)
    }

    @Test
    fun `seed carries the audio arm alongside the subtitle arm`() {
        // Pins the full-snapshot seed at seed level (not just snapshot level):
        // every recovery rebuild carries the audio pick too — the PCM and
        // denied-audio retries re-map the audio DECODER, not the selection.
        val state = PlayerUiState(
            audioTracks = listOf(TrackInfo(index = 0, name = "Surround 5.1", language = "eng", trackId = "1:1")),
            selectedAudioTrackIndex = 0,
            subtitleTracks = listOf(internalSub(0, "2:1")),
            selectedSubtitleTrackIndex = 0
        )

        val seed = buildRebuildCarrySeed(
            state = state,
            subtitlesDeliberatelyDisabled = false,
            channelOccupied = false,
            streamUrl = "https://stream.example/file.mkv"
        )

        assertEquals("1:1", seed?.preference?.audio?.trackId)
        val subtitle = seed?.preference?.subtitle as PlayerRuntimeController.RememberedSubtitleSelection.Internal
        assertEquals("2:1", subtitle.track.trackId)
    }

    @Test
    fun `seed with an audio-only selection carries the audio arm`() {
        val state = PlayerUiState(
            audioTracks = listOf(TrackInfo(index = 1, name = "Stereo", language = "spa", trackId = "1:2")),
            selectedAudioTrackIndex = 0,
            selectedSubtitleTrackIndex = -1
        )

        val seed = buildRebuildCarrySeed(
            state = state,
            subtitlesDeliberatelyDisabled = false,
            channelOccupied = false,
            streamUrl = "https://stream.example/file.mkv"
        )

        assertEquals("1:2", seed?.preference?.audio?.trackId)
        assertNull(seed?.preference?.subtitle)
    }

    @Test
    fun `seed carries the stream gate and exoplayer source engine`() {
        val state = PlayerUiState(
            subtitleTracks = listOf(internalSub(0, "2:1")),
            selectedSubtitleTrackIndex = 0
        )

        val seed = buildRebuildCarrySeed(
            state = state,
            subtitlesDeliberatelyDisabled = false,
            channelOccupied = false,
            streamUrl = "https://stream.example/file.mkv"
        )

        assertEquals("https://stream.example/file.mkv", seed?.streamUrl)
        assertEquals(InternalPlayerEngine.EXOPLAYER, seed?.sourceEngine)
        assertEquals("2:1", (seed?.preference?.subtitle as PlayerRuntimeController.RememberedSubtitleSelection.Internal).track.trackId)
    }
}
