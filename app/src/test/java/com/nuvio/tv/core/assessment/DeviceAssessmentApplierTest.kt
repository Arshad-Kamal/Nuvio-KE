package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.assessment.DeviceAssessmentApplier.ChangeSource
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the snapshot/revert manifest invariants (G6 2026-08-30): the key set
 * is exactly what shipped snapshots carry, every field is written AND
 * restored with its value (both directions of the manifest), and every
 * AssessmentApplyPlan field is covered by a manifest entry (minus the three
 * buffer-trio fields the plan delivers through IntentProfileOption instead,
 * and the two advisory-only DV keys kept for the persisted snapshot format).
 * A new plan field that forgets its SNAPSHOT_FIELDS entry fails the coverage
 * test; a new manifest key without updating this file fails the key-set
 * test; a wrong setter/getter pairing fails the round-trip tests — either
 * way the change is forced to be conscious.
 *
 * Bool sentinels alternate polarity and ints/enums are pairwise distinct, so
 * a swapped manifest entry is caught unless the two swapped fields happen to
 * share a bool value; 17 booleans cannot all be unique, so adjacent
 * alternation is the practical floor.
 */
class DeviceAssessmentApplierTest {

    private val expectedKeys = setOf(
        "nuvioPerformanceModeEnabled", "bufferEngineEnabled", "parallelNetworkEnabled",
        "bufferBudgetManaged", "allowLargeTargetBuffer", "targetBufferSizeMb",
        "minBufferMs", "maxBufferMs", "backBufferDurationMs", "retainBackBufferFromKeyframe",
        "bufferForPlaybackMs", "bufferForPlaybackAfterRebufferMs", "useParallelConnections",
        "parallelConnectionCount", "parallelChunkSizeKb", "enableHttp2",
        "frameRateMatchingMode", "resolutionMatchingEnabled",
        "dv7HandlingMode", "dv5ToDv81Enabled", "stripHdr10PlusSei", "forceOpticalPassthrough",
        "deniedCodecHandling", "allowAc3Passthrough", "allowEac3Passthrough",
        "allowTrueHdPassthrough", "allowDtsPassthrough", "allowDtsHdPassthrough",
        "matPassthroughEnabled", "autoSwitchInternalPlayerOnError"
    )

    /**
     * Sentinel settings plus the exact JSON the snapshot must carry — built
     * together so write assertions, revert verification, and the settings
     * object can never disagree.
     */
    private class Sentinel(settings: PlayerSettings, expectedJson: Map<String, Any>) {
        val settings = settings
        val json = expectedJson
    }

    private fun sentinel(): Sentinel {
        val s = PlayerSettings(
            nuvioPerformanceModeEnabled = true,
            bufferEngineEnabled = false,
            parallelNetworkEnabled = true,
            bufferBudgetManaged = false,
            allowLargeTargetBuffer = true,
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeKb = 1025,
            enableHttp2 = false,
            frameRateMatchingMode = FrameRateMatchingMode.entries[1],
            resolutionMatchingEnabled = true,
            dv7HandlingMode = Dv7HandlingMode.entries[1],
            dv5ToDv81Enabled = false,
            stripHdr10PlusSei = true,
            forceOpticalPassthrough = false,
            deniedCodecHandling = DeniedCodecHandling.entries[1],
            allowAc3Passthrough = true,
            allowEac3Passthrough = false,
            allowTrueHdPassthrough = true,
            allowDtsPassthrough = false,
            allowDtsHdPassthrough = true,
            matPassthroughEnabled = false,
            autoSwitchInternalPlayerOnError = true,
            bufferSettings = BufferSettings(
                minBufferMs = 11_111,
                maxBufferMs = 22_222,
                bufferForPlaybackMs = 3_333,
                bufferForPlaybackAfterRebufferMs = 4_444,
                targetBufferSizeMb = 77,
                backBufferDurationMs = 5_555,
                retainBackBufferFromKeyframe = false
            )
        )
        val json = mapOf(
            "nuvioPerformanceModeEnabled" to true,
            "bufferEngineEnabled" to false,
            "parallelNetworkEnabled" to true,
            "bufferBudgetManaged" to false,
            "allowLargeTargetBuffer" to true,
            "targetBufferSizeMb" to 77,
            "minBufferMs" to 11_111,
            "maxBufferMs" to 22_222,
            "backBufferDurationMs" to 5_555,
            "retainBackBufferFromKeyframe" to false,
            "bufferForPlaybackMs" to 3_333,
            "bufferForPlaybackAfterRebufferMs" to 4_444,
            "useParallelConnections" to true,
            "parallelConnectionCount" to 4,
            "parallelChunkSizeKb" to 1025,
            "enableHttp2" to false,
            "frameRateMatchingMode" to FrameRateMatchingMode.entries[1].name,
            "resolutionMatchingEnabled" to true,
            "dv7HandlingMode" to Dv7HandlingMode.entries[1].name,
            "dv5ToDv81Enabled" to false,
            "stripHdr10PlusSei" to true,
            "forceOpticalPassthrough" to false,
            "deniedCodecHandling" to DeniedCodecHandling.entries[1].name,
            "allowAc3Passthrough" to true,
            "allowEac3Passthrough" to false,
            "allowTrueHdPassthrough" to true,
            "allowDtsPassthrough" to false,
            "allowDtsHdPassthrough" to true,
            "matPassthroughEnabled" to false,
            "autoSwitchInternalPlayerOnError" to true
        )
        return Sentinel(s, json)
    }

    @Test
    fun manifestKeysAreUniqueAndMatchShippedSnapshotFormat() {
        val keys = DeviceAssessmentApplier.SNAPSHOT_KEYS
        assertEquals(expectedKeys.size, keys.size) // no duplicates: raw size, not deduped
        assertEquals(expectedKeys, keys.toSet())
        // Load-bearing order: perf mode first, min before max.
        assertEquals("nuvioPerformanceModeEnabled", keys.first())
        assertTrue(keys.indexOf("minBufferMs") < keys.indexOf("maxBufferMs"))
    }

    @Test
    fun snapshotWritesEveryFieldWithItsValue() {
        val sent = sentinel()
        val json = JSONObject(DeviceAssessmentApplier.snapshotJson(sent.settings, ChangeSource.DEVICE_ASSESSMENT))
        assertEquals(2 + expectedKeys.size, json.length()) // + timestampMs + source, no extras
        sent.json.forEach { (key, value) -> assertEquals("key $key", value, json.get(key)) }
    }

    @Test
    fun revertRestoresEveryFieldWithItsValue() = runTest {
        val sent = sentinel()
        val dataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { dataStore.assessmentRevertSnapshot } returns
            flowOf(DeviceAssessmentApplier.snapshotJson(sent.settings, ChangeSource.DEVICE_ASSESSMENT))

        assertTrue(DeviceAssessmentApplier.revert(dataStore))

        coVerify {
            dataStore.setNuvioPerformanceModeEnabled(true)
            dataStore.setBufferEngineEnabled(false)
            dataStore.setParallelNetworkEnabled(true)
            dataStore.setBufferBudgetManaged(false)
            dataStore.setAllowLargeTargetBuffer(true)
            dataStore.setBufferTargetSizeMb(77)
            dataStore.setBufferMinBufferMs(11_111)
            dataStore.setBufferMaxBufferMs(22_222)
            dataStore.setBufferBackBufferDurationMs(5_555)
            dataStore.setBufferRetainBackBufferFromKeyframe(false)
            dataStore.setBufferForPlaybackMs(3_333)
            dataStore.setBufferForPlaybackAfterRebufferMs(4_444)
            dataStore.setUseParallelConnections(true)
            dataStore.setParallelConnectionCount(4)
            dataStore.setParallelChunkSizeKb(1025)
            dataStore.setEnableHttp2(false)
            dataStore.setFrameRateMatchingMode(FrameRateMatchingMode.entries[1])
            dataStore.setResolutionMatchingEnabled(true)
            dataStore.setDv7HandlingMode(Dv7HandlingMode.entries[1])
            dataStore.setDv5ToDv81Enabled(false)
            dataStore.setStripHdr10PlusSei(true)
            dataStore.setForceOpticalPassthrough(false)
            dataStore.setDeniedCodecHandling(DeniedCodecHandling.entries[1])
            dataStore.setAllowAc3Passthrough(true)
            dataStore.setAllowEac3Passthrough(false)
            dataStore.setAllowTrueHdPassthrough(true)
            dataStore.setAllowDtsPassthrough(false)
            dataStore.setAllowDtsHdPassthrough(true)
            dataStore.setMatPassthroughEnabled(false)
            dataStore.setAutoSwitchInternalPlayerOnError(true)
        }
        coVerify { dataStore.setAssessmentRevertSnapshot(null) }
    }

    @Test
    fun revertSkipsMissingKeysAndStillClearsTheSnapshot() = runTest {
        // A snapshot written before enableHttp2 joined the field set (it was
        // formerly an UNGUARDED key — old code threw out of revert entirely).
        val sent = sentinel()
        val full = JSONObject(DeviceAssessmentApplier.snapshotJson(sent.settings, ChangeSource.DEVICE_ASSESSMENT))
        full.remove("enableHttp2")
        val dataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { dataStore.assessmentRevertSnapshot } returns flowOf(full.toString())

        assertTrue(DeviceAssessmentApplier.revert(dataStore))

        coVerify(exactly = 0) { dataStore.setEnableHttp2(any()) }
        // Everything else still restores.
        coVerify { dataStore.setNuvioPerformanceModeEnabled(true) }
        coVerify { dataStore.setBufferMaxBufferMs(22_222) }
        coVerify { dataStore.setAssessmentRevertSnapshot(null) }
    }

    @Test
    fun revertReturnsFalseWithoutASnapshot() = runTest {
        val dataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { dataStore.assessmentRevertSnapshot } returns flowOf(null)

        assertFalse(DeviceAssessmentApplier.revert(dataStore))

        coVerify(exactly = 0) { dataStore.setNuvioPerformanceModeEnabled(any()) }
        coVerify(exactly = 0) { dataStore.setAssessmentRevertSnapshot(null) }
    }

    @Test
    fun everyPlanFieldIsCoveredByTheManifest() {
        // All-null plan: nothing touched.
        assertEquals(0, AssessmentApplyPlan().touchedCount)
        // Every plan field set: touchedCount must equal manifest size minus the
        // three buffer-trio fields the plan carries via IntentProfileOption.
        val full = AssessmentApplyPlan(
            nuvioPerformanceModeEnabled = true,
            bufferEngineEnabled = true,
            parallelNetworkEnabled = true,
            bufferBudgetManaged = true,
            allowLargeTargetBuffer = true,
            targetBufferSizeMb = 100,
            maxBufferMs = 40_000,
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeKb = 2048,
            enableHttp2 = true,
            frameRateMatchingMode = FrameRateMatchingMode.entries.first(),
            resolutionMatchingEnabled = true,
            dv7HandlingMode = Dv7HandlingMode.entries.first(),
            forceOpticalPassthrough = true,
            deniedCodecHandling = DeniedCodecHandling.entries.first(),
            allowAc3Passthrough = false,
            allowEac3Passthrough = false,
            allowTrueHdPassthrough = false,
            allowDtsPassthrough = false,
            allowDtsHdPassthrough = false,
            matPassthroughEnabled = false,
            backBufferDurationMs = 0,
            retainBackBufferFromKeyframe = false,
            autoSwitchInternalPlayerOnError = true
        )
        val trioFieldsNotInPlan = 3
        // dv5ToDv81Enabled / stripHdr10PlusSei: advisory-only, no plan field,
        // but still snapshotted and restored (persisted revert format).
        val snapshotOnlyKeys = 2
        assertEquals(
            DeviceAssessmentApplier.SNAPSHOT_KEYS.size - trioFieldsNotInPlan - snapshotOnlyKeys,
            full.touchedCount
        )
    }

    @Test
    fun maxBelowTheMinPerformanceModeWritesAlsoLowersTheMin() = runTest {
        // Pre-apply min (30 s) is below the planned max, but enabling performance
        // mode in step 1 presets min 200 s: the max write must still land at the
        // planned value instead of being clamped up to the new min.
        val stored = MutableStateFlow(
            PlayerSettings(bufferSettings = BufferSettings(minBufferMs = 30_000, maxBufferMs = 60_000))
        )
        val dataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { dataStore.playerSettings } returns stored
        coEvery { dataStore.setNuvioPerformanceModeEnabled(true) } answers {
            stored.value = stored.value.copy(
                nuvioPerformanceModeEnabled = true,
                bufferSettings = stored.value.bufferSettings.copy(minBufferMs = 200_000, maxBufferMs = 280_000)
            )
        }

        DeviceAssessmentApplier.apply(
            dataStore = dataStore,
            plan = AssessmentApplyPlan(nuvioPerformanceModeEnabled = true, maxBufferMs = 100_000),
            profile = null,
            safeLimitMb = 10_000,
            source = ChangeSource.DEVICE_ASSESSMENT
        )

        coVerifyOrder {
            dataStore.setNuvioPerformanceModeEnabled(true)
            dataStore.setBufferMinBufferMs(100_000)
            dataStore.setBufferMaxBufferMs(100_000)
        }
    }

    @Test
    fun applyStampsTheSnapshotWithItsSource() = runTest {
        val dataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { dataStore.playerSettings } returns flowOf(PlayerSettings())
        val written = mutableListOf<String?>()
        coEvery { dataStore.setAssessmentRevertSnapshot(captureNullable(written)) } returns Unit

        DeviceAssessmentApplier.apply(
            dataStore = dataStore,
            plan = AssessmentApplyPlan(),
            profile = null,
            safeLimitMb = 10_000,
            source = ChangeSource.NETWORK_TEST
        )

        assertEquals("network_test", JSONObject(written.single()!!).getString("source"))
    }

    @Test
    fun undoSlotReadsSourceAndTime() {
        val json = DeviceAssessmentApplier.snapshotJson(PlayerSettings(), ChangeSource.SETUP_AGAIN, nowMs = 1_700_000_000_000L)
        assertEquals(
            DeviceAssessmentApplier.UndoSlot(ChangeSource.SETUP_AGAIN, 1_700_000_000_000L),
            DeviceAssessmentApplier.undoSlotOf(json)
        )
    }

    @Test
    fun undoSlotOfAPreSourceSnapshotHasNoSource() {
        // Snapshots written before Spec 33 D5 carry a timestamp but no source.
        val legacy = JSONObject(DeviceAssessmentApplier.snapshotJson(PlayerSettings(), ChangeSource.SHIELD_PRESET, 5L))
        legacy.remove("source")
        assertEquals(DeviceAssessmentApplier.UndoSlot(null, 5L), DeviceAssessmentApplier.undoSlotOf(legacy.toString()))
        // An unknown source (a later build's) and a missing timestamp read as unknown, not an error.
        assertEquals(
            DeviceAssessmentApplier.UndoSlot(null, null),
            DeviceAssessmentApplier.undoSlotOf("""{"source":"from_the_future"}""")
        )
    }

    @Test
    fun noUndoSlotWithoutAParseableSnapshot() {
        assertEquals(null, DeviceAssessmentApplier.undoSlotOf(null))
        assertEquals(null, DeviceAssessmentApplier.undoSlotOf("not json"))
    }

    @Test
    fun sourceKeysAreStableAndRoundTrip() {
        // Persisted in the snapshot: renaming a key orphans stored labels.
        assertEquals(
            listOf("first_launch", "setup_again", "network_test", "device_assessment", "shield_preset"),
            ChangeSource.entries.map { it.key }
        )
        ChangeSource.entries.forEach { assertEquals(it, ChangeSource.fromKey(it.key)) }
    }
}
