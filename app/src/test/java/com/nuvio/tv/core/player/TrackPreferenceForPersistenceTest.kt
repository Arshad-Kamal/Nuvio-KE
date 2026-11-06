package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPreferenceForPersistenceTest {

    private val germanAudio = RememberedTrackSelection(language = "de", name = "German 5.1", trackId = "2")
    private val englishSubs = RememberedSubtitleSelection.Internal(
        RememberedTrackSelection(language = "en", name = "English", trackId = "5")
    )
    private val loaded = TrackPreference(audio = germanAudio, subtitle = englishSubs)

    @Test
    fun `a save after a full restore keeps both tracks`() {
        val result = trackPreferenceForPersistence(remembered = null, pending = null, loaded = loaded)
        assertEquals(loaded, result)
    }

    @Test
    fun `a save after a partial restore keeps the applied field`() {
        val pending = TrackPreference(audio = null, subtitle = englishSubs)
        val result = trackPreferenceForPersistence(remembered = null, pending = pending, loaded = loaded)
        assertEquals(germanAudio, result.audio)
        assertEquals(englishSubs, result.subtitle)
    }

    @Test
    fun `subtitles off stays remembered after it was restored`() {
        val offLoaded = TrackPreference(audio = germanAudio, subtitle = RememberedSubtitleSelection.Disabled)
        val result = trackPreferenceForPersistence(remembered = null, pending = null, loaded = offLoaded)
        assertEquals(RememberedSubtitleSelection.Disabled, result.subtitle)
    }

    @Test
    fun `a choice made in the player wins over the loaded one`() {
        val frenchSubs = RememberedSubtitleSelection.Addon(id = "x", url = "u", language = "fr", addonName = "A")
        val remembered = TrackPreference(audio = germanAudio, subtitle = frenchSubs)
        val result = trackPreferenceForPersistence(remembered = remembered, pending = null, loaded = loaded)
        assertEquals(frenchSubs, result.subtitle)
    }

    @Test
    fun `a field the player cleared stays cleared, the loaded one does not refill it`() {
        val remembered = TrackPreference(audio = germanAudio, subtitle = null)
        val result = trackPreferenceForPersistence(remembered = remembered, pending = null, loaded = loaded)
        assertEquals(remembered, result)
    }

    @Test
    fun `nothing saved and nothing chosen stays empty`() {
        val result = trackPreferenceForPersistence(remembered = null, pending = null, loaded = null)
        assertNull(result.audio)
        assertNull(result.subtitle)
    }
}
