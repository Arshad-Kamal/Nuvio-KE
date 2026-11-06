package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerRuntimeController.PendingEngineSwitchTrackPreference
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 21 P6: the track-selection holder's named transitions and restore begin/finish. */
class TrackSelectionStateTest {

    private val url = "https://cdn.example/title.mkv"
    private val content = "tt0000001"
    private val english = RememberedTrackSelection(language = "eng", name = "TrueHD 7.1", trackId = "1:1")
    private val french = RememberedTrackSelection(language = "fre", name = "AC3 5.1", trackId = "1:2")
    private val subtitle = RememberedSubtitleSelection.Internal(RememberedTrackSelection(language = "eng", name = "English"))
    private val addon = Subtitle(id = "os-1", url = "https://subs.example/1.srt", lang = "en", addonName = "OpenSubtitles", addonLogo = null)

    private fun carry(streamUrl: String = url, preference: TrackPreference = TrackPreference(audio = french)) =
        PendingEngineSwitchTrackPreference(streamUrl, preference, InternalPlayerEngine.EXOPLAYER)

    private fun TrackSelectionState.withRestoreLatches() = apply {
        subtitleDisabledByPersistedPreference = true
        subtitleAddonRestoredByPersistedPreference = true
        pendingRestoredAddonSubtitle = addon
    }

    private fun TrackSelectionState.assertRestoreLatchesCleared() {
        assertFalse(subtitleDisabledByPersistedPreference)
        assertFalse(subtitleAddonRestoredByPersistedPreference)
        assertNull(pendingRestoredAddonSubtitle)
    }

    @Test
    fun `loaded preference fills persisted and the loaded copy keyed by content`() {
        val state = TrackSelectionState()
        val loaded = TrackPreference(audio = english, subtitle = subtitle)
        state.preferenceLoaded(content, loaded)
        assertEquals(loaded, state.persistedTrackPreference)
        assertEquals(content to loaded, state.loadedTrackPreference)

        state.preferenceLoaded(content, null)
        assertNull(state.persistedTrackPreference)
        assertNull(state.loadedTrackPreference)
    }

    @Test
    fun `explicit audio pick keeps the subtitle base and clears carry and persisted`() {
        val state = TrackSelectionState()
        state.preferenceLoaded(content, TrackPreference(audio = english, subtitle = subtitle))
        state.persistedTrackPreference = TrackPreference(audio = null, subtitle = null) // trimmed by a restore
        state.pendingEngineSwitchTrackPreference = carry()

        state.explicitAudioPicked(french, content)

        assertEquals(TrackPreference(audio = french, subtitle = subtitle), state.rememberedTrackPreference)
        assertNull(state.persistedTrackPreference)
        assertNull(state.pendingEngineSwitchTrackPreference)
        // The base is read before persisted is cleared: a subtitle held only by persisted survives.
        val untrimmed = TrackSelectionState()
        untrimmed.persistedTrackPreference = TrackPreference(audio = english, subtitle = subtitle)
        untrimmed.explicitAudioPicked(french, content)
        assertEquals(TrackPreference(audio = french, subtitle = subtitle), untrimmed.rememberedTrackPreference)
        // Another title's loaded copy is not a base.
        val other = TrackSelectionState()
        other.preferenceLoaded("tt999", TrackPreference(subtitle = subtitle))
        other.persistedTrackPreference = null
        other.explicitAudioPicked(french, content)
        assertEquals(TrackPreference(audio = french), other.rememberedTrackPreference)
    }

    @Test
    fun `explicit subtitle pick pins it for an engine switch and clears the restore latches`() {
        val state = TrackSelectionState().withRestoreLatches()
        state.persistedTrackPreference = TrackPreference(audio = english)
        state.pendingEngineSwitchTrackPreference = carry()

        state.explicitSubtitlePicked(RememberedSubtitleSelection.Disabled, url, content)

        assertTrue(state.isUserExplicitSubtitleSelection)
        assertNull(state.persistedTrackPreference)
        assertNull(state.pendingEngineSwitchTrackPreference)
        state.assertRestoreLatchesCleared()
        assertEquals(url, state.explicitSubtitleSelectionForEngineSwitch?.streamUrl)
        assertEquals(RememberedSubtitleSelection.Disabled, state.explicitSubtitleSelectionForEngineSwitch?.selection)
        assertEquals(state.explicitSubtitleSelectionForEngineSwitch, state.effectiveSubtitleSelectionForEngineSwitch)
        // Base read before persisted was cleared: the audio survives.
        assertEquals(TrackPreference(audio = english, subtitle = RememberedSubtitleSelection.Disabled), state.rememberedTrackPreference)
    }

    @Test
    fun `engine switch capture sets the carry and clears the restore latches`() {
        val state = TrackSelectionState().withRestoreLatches()
        state.engineSwitchCaptured(carry())
        assertEquals(carry(), state.pendingEngineSwitchTrackPreference)
        state.assertRestoreLatchesCleared()

        state.engineSwitchCaptured(null)
        assertNull(state.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun `restore drops a stale carry and writes what is left back to its channel`() {
        val state = TrackSelectionState()
        state.persistedTrackPreference = TrackPreference(audio = english, subtitle = subtitle)
        state.pendingEngineSwitchTrackPreference = carry(streamUrl = "https://other/x.mkv")

        val persistedSource = state.beginRestore(url)!!
        assertNull("stale carry dropped", state.pendingEngineSwitchTrackPreference)
        assertEquals(TrackSelectionResolver.Channel.PERSISTED, persistedSource.channel)
        state.finishRestore(persistedSource, TrackPreference(subtitle = subtitle), url, InternalPlayerEngine.MVP_PLAYER)
        assertEquals(TrackPreference(subtitle = subtitle), state.persistedTrackPreference)
        assertNull(state.pendingEngineSwitchTrackPreference)

        state.pendingEngineSwitchTrackPreference = carry()
        val carrySource = state.beginRestore(url)!!
        assertEquals("a carry for this URL is kept", carry(), state.pendingEngineSwitchTrackPreference)
        assertEquals(TrackSelectionResolver.Channel.ENGINE_SWITCH_CARRY, carrySource.channel)
        state.finishRestore(carrySource, TrackPreference(audio = french), url, InternalPlayerEngine.MVP_PLAYER)
        assertEquals(
            "carry keeps its source engine",
            PendingEngineSwitchTrackPreference(url, TrackPreference(audio = french), InternalPlayerEngine.EXOPLAYER),
            state.pendingEngineSwitchTrackPreference
        )
        assertEquals("persisted untouched by a carry restore", TrackPreference(subtitle = subtitle), state.persistedTrackPreference)

        state.finishRestore(carrySource, null, url, InternalPlayerEngine.MVP_PLAYER)
        assertNull(state.pendingEngineSwitchTrackPreference)

        val empty = TrackSelectionState()
        assertNull(empty.beginRestore(url))
    }

    @Test
    fun `new stream re-arms the lossless default and keeps the content-scoped audio latch`() {
        val state = TrackSelectionState().withRestoreLatches()
        state.losslessAudioDefaultAppliedForStream = true
        state.persistedAudioPreferenceSeenForStream = true
        state.persistedTrackPreference = TrackPreference(audio = english)

        state.newStream()

        assertFalse(state.losslessAudioDefaultAppliedForStream)
        assertTrue(state.persistedAudioPreferenceSeenForStream)
        assertEquals(TrackPreference(audio = english), state.persistedTrackPreference)
        state.assertRestoreLatchesCleared()
    }

    @Test
    fun `new content drops the persisted choice and the audio-seen latch only`() {
        val state = TrackSelectionState()
        state.persistedTrackPreference = TrackPreference(audio = english)
        state.persistedAudioPreferenceSeenForStream = true
        state.rememberedTrackPreference = TrackPreference(subtitle = subtitle)

        state.newContent()

        assertNull(state.persistedTrackPreference)
        assertFalse(state.persistedAudioPreferenceSeenForStream)
        assertEquals(TrackPreference(subtitle = subtitle), state.rememberedTrackPreference)
    }

    @Test
    fun `episode switch keeps subtitles off only when the remembered pick is off`() {
        for (remembered in listOf(RememberedSubtitleSelection.Disabled, subtitle, null)) {
            val state = TrackSelectionState().withRestoreLatches()
            state.isUserExplicitSubtitleSelection = true
            state.autoSubtitleSelected = !(remembered == RememberedSubtitleSelection.Disabled)
            state.rememberedTrackPreference = TrackPreference(subtitle = remembered)

            state.episodeSwitchSubtitlesReset()

            val off = remembered == RememberedSubtitleSelection.Disabled
            assertEquals("$remembered", off, state.autoSubtitleSelected)
            assertEquals("$remembered", off, state.subtitleDisabledByPersistedPreference)
            assertFalse(state.isUserExplicitSubtitleSelection)
            assertFalse(state.subtitleAddonRestoredByPersistedPreference)
            assertNull(state.pendingRestoredAddonSubtitle)
            assertEquals("remembered carries unchanged", TrackPreference(subtitle = remembered), state.rememberedTrackPreference)
        }
    }

    @Test
    fun `stream start holds the auto pick off only for a persisted off or addon restore`() {
        fun after(disabled: Boolean, restored: Boolean): TrackSelectionState = TrackSelectionState().apply {
            subtitleDisabledByPersistedPreference = disabled
            subtitleAddonRestoredByPersistedPreference = restored
            autoSubtitleSelected = !(disabled || restored)
            isUserExplicitSubtitleSelection = true
            explicitSubtitleSelectionForEngineSwitch =
                PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(url, subtitle)
            effectiveSubtitleSelectionForEngineSwitch = explicitSubtitleSelectionForEngineSwitch
            streamStartSubtitlesReset()
        }
        assertFalse(after(disabled = false, restored = false).autoSubtitleSelected)
        assertTrue(after(disabled = true, restored = false).autoSubtitleSelected)
        assertTrue(after(disabled = false, restored = true).autoSubtitleSelected)
        val reset = after(disabled = true, restored = true)
        assertFalse(reset.isUserExplicitSubtitleSelection)
        assertNull(reset.explicitSubtitleSelectionForEngineSwitch)
        assertNull(reset.effectiveSubtitleSelectionForEngineSwitch)
    }

    @Test
    fun `default audio blocked reads all four channels`() {
        val state = TrackSelectionState()
        assertFalse(state.defaultAudioBlocked())
        state.pendingEngineSwitchTrackPreference = carry(preference = TrackPreference(subtitle = subtitle))
        assertFalse("subtitle-only carry protects nothing", state.defaultAudioBlocked())
        state.pendingEngineSwitchTrackPreference = carry()
        assertTrue(state.defaultAudioBlocked())
        state.pendingEngineSwitchTrackPreference = null
        state.persistedAudioPreferenceSeenForStream = true
        assertTrue(state.defaultAudioBlocked())
        state.persistedAudioPreferenceSeenForStream = false
        state.rememberedTrackPreference = TrackPreference(audio = english)
        assertTrue(state.defaultAudioBlocked())
        state.rememberedTrackPreference = null
        state.persistedTrackPreference = TrackPreference(audio = english)
        assertTrue(state.defaultAudioBlocked())
    }
}
