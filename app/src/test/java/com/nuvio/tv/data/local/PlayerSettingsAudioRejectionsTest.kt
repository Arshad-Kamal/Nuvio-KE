package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F3 clear path (upstream-sync Register A2 P1): changing a codec's passthrough switch forgets
 * that group's learned rejections, seen and confirmed, on every route; other groups and a
 * same-value re-apply (device-assessment apply) leave them alone.
 */
class PlayerSettingsAudioRejectionsTest {
    private val seen = stringSetPreferencesKey("audio_rejections_seen")
    private val confirmed = stringSetPreferencesKey("audio_rejections_confirmed")

    private val resetDone = booleanPreferencesKey("migration_audio_rejections_reset_done")

    private val learned = preferencesOf(
        resetDone to true,
        seen to setOf("type:hdmi|name:avr::DTS_HD", "type:hdmi|name:tv::DTS_HD", "type:hdmi|name:avr::DTS", "type:hdmi|name:avr::EAC3"),
        confirmed to setOf("type:hdmi|name:avr::DTS_HD", "type:hdmi|name:avr::AC3")
    )

    /** Rejections are device keys (Spec 33 D1): [store] is the device store, migrations applied. */
    private fun settingsOver(store: TestPreferencesDataStore): PlayerSettingsDataStore =
        playerSettingsOver(profile = TestPreferencesDataStore(), device = store)

    @Test
    fun `entries learned before T12 are forgotten once, later ones survive the migration`() = runTest {
        val preT12 = learned.toMutablePreferences().apply { remove(resetDone) }
        // The reset runs inside migratedDeviceStore, as the real store runs it before its first read.
        val store = migratedDeviceStore(preT12)
        val settings = settingsOver(store)

        assertEquals(null, store.value[seen])
        assertEquals(null, store.value[confirmed])
        assertEquals(true, store.value[resetDone])

        settings.recordAudioRejection("type:hdmi|name:avr::TRUEHD")
        settings.playerSettings.first()
        assertEquals(setOf("type:hdmi|name:avr::TRUEHD"), store.value[seen])
    }

    @Test
    fun `turning DTS-HD off forgets DTS-HD on every route and nothing else`() = runTest {
        val store = migratedDeviceStore(learned)
        val settings = settingsOver(store)

        settings.setAllowDtsHdPassthrough(false)

        assertEquals(setOf("type:hdmi|name:avr::DTS", "type:hdmi|name:avr::EAC3"), store.value[seen])
        assertEquals(setOf("type:hdmi|name:avr::AC3"), store.value[confirmed])
    }

    @Test
    fun `turning a switch back on also forgets, AC3 leaves EAC3 alone`() = runTest {
        val store = migratedDeviceStore(
            learned.toMutablePreferences().apply { this[booleanPreferencesKey("allow_ac3_passthrough")] = false }
        )
        val settings = settingsOver(store)

        settings.setAllowAc3Passthrough(true)

        assertEquals(learned[seen], store.value[seen])
        assertEquals(setOf("type:hdmi|name:avr::DTS_HD"), store.value[confirmed])
    }

    @Test
    fun `re-applying the current value keeps learned rejections`() = runTest {
        val store = migratedDeviceStore(learned)
        val settings = settingsOver(store)

        settings.setAllowTrueHdPassthrough(true)
        settings.setAllowDtsHdPassthrough(true)

        assertEquals(learned[seen], store.value[seen])
        assertEquals(learned[confirmed], store.value[confirmed])
    }
}
