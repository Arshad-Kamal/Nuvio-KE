package com.nuvio.tv.core.assessment

import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DeviceFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DisplayFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.RowText
import com.nuvio.tv.core.player.AudioCapabilityReport.DirectSupport
import com.nuvio.tv.core.player.DeviceQuirks
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.playerSettingsOver
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 33 D8: the first-launch decision, the tuned signal and the welcome card's text. */
class DeviceWelcomeTest {

    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** "name(arg|arg)", so a pin survives id churn (DeviceAssessmentEngineRowsTest precedent). */
    private val text = RowText { id, args ->
        val name = stringNames[id] ?: "#$id"
        if (args.isEmpty()) name else "$name(${args.joinToString("|")})"
    }

    private val fp = "NVIDIA|SHIELD Android TV|mdarcy|2 GB"

    // ── welcomeSetupFor ─────────────────────────────────────────────────────

    @Test
    fun `fresh install applies`() {
        assertEquals(WelcomeSetup.APPLY, welcomeSetupFor(null, fp, tuned = false))
    }

    @Test
    fun `tuned install with no record keeps its values`() {
        assertEquals(WelcomeSetup.KEEP, welcomeSetupFor(null, fp, tuned = true))
    }

    @Test
    fun `a record for this box shows nothing, whatever its kind`() {
        DeviceSetupRecord.Kind.entries.forEach { kind ->
            val record = DeviceSetupRecord(fp, 1L, kind)
            assertEquals(WelcomeSetup.NONE, welcomeSetupFor(record, fp, tuned = true))
            assertEquals(WelcomeSetup.NONE, welcomeSetupFor(record, fp, tuned = false))
        }
    }

    @Test
    fun `a record from another box or RAM tier sets up again, tuned or not`() {
        val other = DeviceSetupRecord("NVIDIA|SHIELD Android TV|mdarcy|3 GB", 1L)
        assertEquals(WelcomeSetup.APPLY, welcomeSetupFor(other, fp, tuned = true))
        assertEquals(WelcomeSetup.APPLY, welcomeSetupFor(other, fp, tuned = false))
    }

    // ── isAlreadyTuned ──────────────────────────────────────────────────────

    @Test
    fun `defaults are not tuned`() {
        assertFalse(isAlreadyTuned(PlayerSettings(), hasUndoSnapshot = false))
    }

    @Test
    fun `a fresh real store reads as not tuned`() = runTest {
        // The real read path and the device migrations, not PlayerSettings(): a drifted read
        // default would turn every fresh install into KEEP and the setup would never run.
        val store = playerSettingsOver(TestPreferencesDataStore())
        assertFalse(isAlreadyTuned(store.playerSettings.first(), hasUndoSnapshot = false))
    }

    @Test
    fun `an undo snapshot alone means tuned`() {
        assertTrue(isAlreadyTuned(PlayerSettings(), hasUndoSnapshot = true))
    }

    @Test
    fun `any non-default buffer or network value means tuned`() {
        val d = PlayerSettings()
        val tuned = listOf(
            d.copy(bufferSettings = BufferSettings(targetBufferSizeMb = 200)),
            d.copy(bufferSettings = BufferSettings(backBufferDurationMs = 0)),
            d.copy(bufferSettings = BufferSettings(retainBackBufferFromKeyframe = false)),
            d.copy(nuvioPerformanceModeEnabled = true),
            d.copy(bufferEngineEnabled = true),
            d.copy(parallelNetworkEnabled = true),
            d.copy(bufferBudgetManaged = false),
            d.copy(allowLargeTargetBuffer = true),
            d.copy(useParallelConnections = true),
            d.copy(parallelConnectionCount = 4),
            d.copy(parallelChunkSizeKb = 8 * 1024),
            d.copy(enableHttp2 = true)
        )
        tuned.forEach { assertTrue(it.toString(), isAlreadyTuned(it, hasUndoSnapshot = false)) }
    }

    @Test
    fun `display and audio values are not the tuned signal`() {
        val s = PlayerSettings(
            frameRateMatchingMode = FrameRateMatchingMode.START_STOP,
            resolutionMatchingEnabled = true,
            tunnelingEnabled = true
        )
        assertFalse(isAlreadyTuned(s, hasUndoSnapshot = false))
    }

    // ── card text ───────────────────────────────────────────────────────────

    private val policy = DolbyVisionBaseLayerPolicy.Result(
        decision = DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7,
        hdrCapsKnown = true,
        displayDv = true,
        displayHdr10 = true,
        displayHdr10Plus = false,
        displayHlg = true,
        codecSupportsDvheDtb = true,
        codecSupportsDvheStn = true,
        codecSupportsDvheSt = true,
        isAmazonFireTv = false,
        isSamsung = false,
        isXiaomi = false,
        bridgeReady = true,
        apiLevel = 30
    )

    private val display4k = DisplayFacts(
        apiSupported = true,
        supportsFrameRateSwitching = true,
        supportsResolutionSwitching = true,
        ratesLabel = "23.976/24/60",
        resolutionsLabel = "3840x2160/1920x1080",
        currentResolutionLabel = "3840x2160",
        largestWidth = 3840
    )

    private val shield = DeviceFacts(
        quirks = DeviceQuirks("NVIDIA", "SHIELD Android TV"),
        fingerprint = fp,
        apiLevel = 30,
        ramLabel = "2 GB",
        safeLimitMb = 250,
        warningLimitMb = 325,
        tierMaxChunkMb = 16,
        display = display4k,
        policy = policy
    )

    /** The Shield preset's applied state (D2 test pin). */
    private val shieldSettings = PlayerSettings(
        bufferSettings = BufferSettings(
            minBufferMs = 30_000,
            maxBufferMs = 120_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 8_000,
            targetBufferSizeMb = 150,
            backBufferDurationMs = 0
        ),
        frameRateMatchingMode = FrameRateMatchingMode.START_STOP,
        resolutionMatchingEnabled = true,
        bufferEngineEnabled = true,
        parallelNetworkEnabled = true,
        bufferBudgetManaged = false,
        useParallelConnections = true,
        parallelConnectionCount = 4,
        parallelChunkSizeKb = 16 * 1024,
        enableHttp2 = true
    )

    private val avr = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)

    @Test
    fun `Shield on a 4K DV TV and an AVR reads like the approved mockup`() {
        val s = welcomeSummary(shield, shieldSettings, "11", avr, text)
        assertEquals("NVIDIA SHIELD Android TV", s.deviceName)
        assertEquals(
            "welcome_device_memory(2 GB) \u00b7 welcome_device_android(11) \u00b7 " +
                "welcome_device_tv_dv(4K HDR) \u00b7 welcome_device_receiver(welcome_join_and(TrueHD|DTS-HD))",
            s.deviceLine
        )
        assertEquals(
            listOf(
                WelcomeRow("welcome_row_buffer_memory", "welcome_value_buffer_memory(150|19)"),
                WelcomeRow("welcome_row_buffering", "welcome_value_buffering(5|8|30)"),
                WelcomeRow("welcome_row_frame_rate", "welcome_value_frame_rate_start_stop"),
                WelcomeRow("welcome_row_resolution", "assessment_value_on"),
                WelcomeRow(
                    "welcome_row_network",
                    "welcome_value_network(welcome_value_network_parallel(4|assessment_unit_mb(16)|welcome_http2_on))"
                ),
                WelcomeRow("welcome_row_dv_audio", "welcome_value_dv_audio", applied = false)
            ),
            s.rows
        )
        assertTrue(s.showNetworkNote)
    }

    @Test
    fun `rows the formula leaves alone are hidden off a Shield without rate switching`() {
        val box = shield.copy(
            quirks = DeviceQuirks("Google", "Chromecast HD"),
            display = display4k.copy(supportsFrameRateSwitching = false)
        )
        val labels = welcomeSummary(box, shieldSettings, "12", null, text).rows.map { it.label }
        assertEquals(
            listOf("welcome_row_buffer_memory", "welcome_row_buffering", "welcome_row_network", "welcome_row_dv_audio"),
            labels
        )
    }

    @Test
    fun `frame rate and resolution rows have separate gates`() {
        val nonShieldSwitching = shield.copy(quirks = DeviceQuirks("Google", "Chromecast HD"))
        val a = welcomeSummary(nonShieldSwitching, shieldSettings, "12", null, text).rows.map { it.label }
        assertTrue("welcome_row_frame_rate" in a)
        assertFalse("welcome_row_resolution" in a)
        val shieldNoApi = shield.copy(display = display4k.copy(apiSupported = false))
        val b = welcomeSummary(shieldNoApi, shieldSettings, "11", avr, text).rows.map { it.label }
        assertFalse("welcome_row_frame_rate" in b)
        assertTrue("welcome_row_resolution" in b)
    }

    @Test
    fun `a kept install shows only what plays`() {
        // KEEP on HTTP/2 alone: masters off, budget managed (defaults) -> no buffer rows, no promise.
        val s = welcomeSummary(shield, PlayerSettings(enableHttp2 = true), "11", avr, text)
        assertEquals(
            listOf("welcome_row_frame_rate", "welcome_row_resolution", "welcome_row_network", "welcome_row_dv_audio"),
            s.rows.map { it.label }
        )
        assertEquals(
            "welcome_value_network_single(welcome_http2_on)",
            s.rows.single { it.label == "welcome_row_network" }.value
        )
        assertFalse(s.showNetworkNote)
    }

    @Test
    fun `a managed budget hides buffer memory but keeps the trio`() {
        val labels = welcomeSummary(shield, shieldSettings.copy(bufferBudgetManaged = true), "11", avr, text)
            .rows.map { it.label }
        assertFalse("welcome_row_buffer_memory" in labels)
        assertTrue("welcome_row_buffering" in labels)
    }

    @Test
    fun `mpv hides buffer and network rows and the promise`() {
        val s = welcomeSummary(
            shield,
            shieldSettings.copy(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER),
            "11",
            avr,
            text
        )
        assertEquals(
            listOf("welcome_row_frame_rate", "welcome_row_resolution", "welcome_row_dv_audio"),
            s.rows.map { it.label }
        )
        assertFalse(s.showNetworkNote)
    }

    @Test
    fun `frame rate values`() {
        fun value(mode: FrameRateMatchingMode) =
            welcomeSummary(shield, shieldSettings.copy(frameRateMatchingMode = mode), "11", avr, text)
                .rows.single { it.label == "welcome_row_frame_rate" }.value
        assertEquals("assessment_value_off", value(FrameRateMatchingMode.OFF))
        assertEquals("welcome_value_frame_rate_start", value(FrameRateMatchingMode.START))
    }

    @Test
    fun `one connection and HTTP 2 off when parallel is off`() {
        val network = welcomeSummary(shield, shieldSettings.copy(useParallelConnections = false, enableHttp2 = false), "11", avr, text)
            .rows.single { it.label == "welcome_row_network" }.value
        assertEquals("welcome_value_network(welcome_value_network_single(welcome_http2_off))", network)
    }

    @Test
    fun `parallel set but the network master off counts one connection`() {
        val network = welcomeSummary(shield, shieldSettings.copy(parallelNetworkEnabled = false), "11", avr, text)
            .rows.single { it.label == "welcome_row_network" }.value
        // Network master off is outside the automatic shape: no network-test promise either.
        assertEquals("welcome_value_network_single(welcome_http2_on)", network)
    }

    @Test
    fun `a sub-megabyte chunk reads in KB`() {
        val network = welcomeSummary(shield, shieldSettings.copy(parallelChunkSizeKb = 512), "11", avr, text)
            .rows.single { it.label == "welcome_row_network" }.value
        assertTrue(network, network.contains("4|assessment_unit_kb(512)|"))
    }

    @Test
    fun `unknown parts are left out of the device line`() {
        val box = shield.copy(
            ramLabel = NuvioExoPlayerPerformanceHelper.UNKNOWN_RAM_LABEL,
            display = DisplayFacts(false, false, false, "", "", null),
            policy = policy.copy(
                hdrCapsKnown = false, displayDv = false, displayHdr10 = false, displayHlg = false
            )
        )
        assertEquals("", welcomeSummary(box, PlayerSettings(), "", null, text).deviceLine)
    }

    @Test
    fun `SDR 1080p TV and a lossy chain`() {
        val box = shield.copy(
            display = display4k.copy(resolutionsLabel = "1920x1080/1280x720", largestWidth = 1920),
            policy = policy.copy(displayDv = false, displayHdr10 = false, displayHlg = false)
        )
        val lossy = DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = true, dtsHd = false)
        val line = welcomeSummary(box, PlayerSettings(), "11", lossy, text).deviceLine
        assertTrue(line, line.endsWith(
            "welcome_device_tv(1080p) \u00b7 welcome_device_passthrough(welcome_join_and(Dolby Digital Plus|DTS))"
        ))
    }

    @Test
    fun `HDR without DV, HDR alone below 1080p, AC3 only, one lossless format`() {
        val hdr10 = policy.copy(displayDv = false)
        val line4k = welcomeSummary(shield.copy(policy = hdr10), PlayerSettings(), "11",
            DirectSupport(ac3 = true, eac3 = false, trueHd = false, dts = false, dtsHd = false), text).deviceLine
        assertTrue(line4k, line4k.endsWith("welcome_device_tv(4K HDR) \u00b7 welcome_device_passthrough(Dolby Digital)"))
        val small = shield.copy(policy = hdr10, display = display4k.copy(largestWidth = 1280))
        val lineSmall = welcomeSummary(small, PlayerSettings(), "11",
            DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = false), text).deviceLine
        assertTrue(lineSmall, lineSmall.endsWith("welcome_device_tv(HDR) \u00b7 welcome_device_receiver(TrueHD)"))
    }

    @Test
    fun `no passthrough at all leaves audio out`() {
        val none = DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)
        val line = welcomeSummary(shield, PlayerSettings(), "11", none, text).deviceLine
        assertFalse(line, line.contains("receiver") || line.contains("passthrough"))
    }

    @Test
    fun `device name avoids a doubled manufacturer`() {
        assertEquals("NVIDIA SHIELD Android TV", deviceName("NVIDIA", "SHIELD Android TV"))
        assertEquals("Chromecast", deviceName("", "Chromecast"))
        assertEquals("Google Chromecast", deviceName("Google", "Chromecast"))
        assertEquals("Xiaomi MiBOX4", deviceName("xiaomi", "MiBOX4"))
        assertEquals("Amazon AFTKA", deviceName("Amazon", "AFTKA"))
        assertEquals("onn. 4K Streaming Box", deviceName("onn.", "onn. 4K Streaming Box"))
        assertEquals("Sony", deviceName("Sony", null))
    }

    @Test
    fun `seconds label keeps a half second`() {
        assertEquals("5", secondsLabel(5_000))
        assertEquals("1.5", secondsLabel(1_500))
    }

    // ── DeviceSetupCoordinator.firstLaunch over the real store ─────────────

    @Test
    fun `first launch on a fresh store applies and records FIRST_LAUNCH`() = runTest {
        val store = playerSettingsOver(TestPreferencesDataStore())
        val result = DeviceSetupCoordinator.firstLaunch(shield, store, nowMs = 42L)!!
        val record = DeviceSetupRecord.decode(store.deviceSetupRecordJson.first())
        assertEquals(DeviceSetupRecord(fp, 42L, DeviceSetupRecord.Kind.FIRST_LAUNCH), record)
        assertTrue(store.assessmentRevertSnapshot.first() != null)
        assertNotEquals(PlayerSettings().bufferSettings, result.settings.bufferSettings)
        assertEquals(store.playerSettings.first(), result.settings)
        // Applied, so the next start is NONE.
        assertNull(DeviceSetupCoordinator.firstLaunch(shield, store))
    }

    @Test
    fun `first launch on a tuned store records KEPT and writes nothing else`() = runTest {
        val store = playerSettingsOver(TestPreferencesDataStore())
        store.setParallelChunkSizeKb(8 * 1024)
        val before = store.playerSettings.first()
        val result = DeviceSetupCoordinator.firstLaunch(shield, store, nowMs = 7L)!!
        assertEquals(
            DeviceSetupRecord(fp, 7L, DeviceSetupRecord.Kind.KEPT),
            DeviceSetupRecord.decode(store.deviceSetupRecordJson.first())
        )
        assertNull(store.assessmentRevertSnapshot.first())
        assertEquals(before, result.settings)
    }

    @Test
    fun `first launch with this box recorded returns null and writes nothing`() = runTest {
        val store = playerSettingsOver(TestPreferencesDataStore())
        val record = DeviceSetupRecord(fp, 1L, DeviceSetupRecord.Kind.AGAIN).encode()
        store.setDeviceSetupRecordJson(record)
        val before = store.playerSettings.first()
        assertNull(DeviceSetupCoordinator.firstLaunch(shield, store))
        assertEquals(record, store.deviceSetupRecordJson.first())
        assertNull(store.assessmentRevertSnapshot.first())
        assertEquals(before, store.playerSettings.first())
    }
}
