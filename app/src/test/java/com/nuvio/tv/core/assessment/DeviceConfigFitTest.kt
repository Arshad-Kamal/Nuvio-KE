package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DeviceFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DisplayFacts
import com.nuvio.tv.core.player.DeviceQuirks
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 33 D2: the first-launch formula, the Shield overlay and the whole-config fit gate. */
class DeviceConfigFitTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    private val policy = DolbyVisionBaseLayerPolicy.Result(
        decision = DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7,
        hdrCapsKnown = true,
        displayDv = true,
        displayHdr10 = true,
        displayHdr10Plus = true,
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
        ratesLabel = "23.976/24/50/59.94/60",
        resolutionsLabel = "3840x2160/1920x1080",
        currentResolutionLabel = "3840x2160"
    )

    private fun device(
        safe: Int,
        warn: Int,
        manufacturer: String = "Google",
        model: String = "Chromecast",
        tierMaxChunkMb: Int = 16,
        apiLevel: Int = 30,
        display: DisplayFacts = display4k
    ) = DeviceFacts(
        quirks = DeviceQuirks(manufacturer, model),
        fingerprint = "$manufacturer|$model|x|tier",
        apiLevel = apiLevel,
        ramLabel = "tier",
        safeLimitMb = safe,
        warningLimitMb = warn,
        tierMaxChunkMb = tierMaxChunkMb,
        display = display,
        policy = policy
    )

    /** The reference device: 2019 non-Pro Shield, 2 GB rung (safe 250 / warn 325). */
    private val shield2gb = device(250, 325, "NVIDIA", "SHIELD Android TV")

    /** Every RAM rung's safe / warn MB (NuvioExoPlayerPerformanceHelper RAM_TIERS). */
    private val tiers = listOf(
        150 to 180, 200 to 250, 250 to 325, 350 to 450, 1000 to 1200, 1600 to 2000, 2000 to 2500
    )

    private fun config(
        target: Int = 150,
        min: Int = 20_000,
        max: Int = 120_000,
        back: Int = 0,
        retain: Boolean = false,
        parallel: Boolean = true,
        connections: Int = 4,
        chunkKb: Int = 16_384
    ) = PlaybackConfig(
        performanceMode = true,
        targetBufferMb = target,
        minBufferMs = min,
        maxBufferMs = max,
        bufferForPlaybackMs = 3_000,
        bufferForPlaybackAfterRebufferMs = 5_000,
        backBufferMs = back,
        retainBackBufferFromKeyframe = retain,
        parallel = parallel,
        connections = connections,
        chunkKb = chunkKb,
        http2 = false,
        frameRateMatching = FrameRateMatchingMode.START_STOP
    )

    private fun limits(safe: Int, tierMaxChunkMb: Int = 32) =
        DeviceLimits(safe, tierMaxChunkMb, perfModeSupported = true, afrSupported = true)

    private fun PlaybackConfig.nativeMb() = targetBufferMb + parallelOverheadMb

    // ── Shield pin ──────────────────────────────────────────────────────────

    @Test
    fun `Shield setup on the 2 GB rung is today's applied preset`() {
        val setup = DeviceSetupFormula.setup(shield2gb, PlayerSettings())
        // The preset as apply settles it: the applier's clamp takes 200 to 150 on safe 250.
        assertEquals(ShieldPreset.plan.copy(targetBufferSizeMb = 150), setup.plan)
        val p = setup.plan
        assertEquals(150, p.targetBufferSizeMb)
        assertEquals(4, p.parallelConnectionCount)
        assertEquals(16_384, p.parallelChunkSizeKb)
        assertEquals(false, p.enableHttp2)
        assertEquals(FrameRateMatchingMode.START_STOP, p.frameRateMatchingMode)
        assertEquals(true, p.resolutionMatchingEnabled)
        assertEquals(0, p.backBufferDurationMs)
        assertEquals(false, p.retainBackBufferFromKeyframe)
        assertEquals(120_000, p.maxBufferMs)
        assertEquals(ShieldPreset.profile, setup.profile)
        assertEquals(5_000, setup.profile.initialBufferMs)
        assertEquals(8_000, setup.profile.rebufferMs)
        assertEquals(30_000, setup.profile.minBufferMs)
    }

    @Test
    fun `Shield setup ignores the current buffer and network settings`() {
        val tuned = PlayerSettings(
            useParallelConnections = false,
            parallelConnectionCount = 2,
            parallelChunkSizeKb = 1024,
            enableHttp2 = false,
            frameRateMatchingMode = FrameRateMatchingMode.OFF,
            bufferSettings = BufferSettings(backBufferDurationMs = 30_000, retainBackBufferFromKeyframe = true)
        )
        assertEquals(
            DeviceSetupFormula.setup(shield2gb, PlayerSettings()),
            DeviceSetupFormula.setup(shield2gb, tuned)
        )
    }

    @Test
    fun `Shield overlay passes the no-film fit gate unchanged on every RAM rung`() {
        for ((safe, warn) in tiers) {
            val d = shield2gb.copy(safeLimitMb = safe, warningLimitMb = warn)
            val overlaid = ShieldPreset.overlay(DeviceSetupFormula.formula(d, PlayerSettings()), d.limits())
            assertEquals("safe $safe", overlaid, fitPlaybackConfig(overlaid, d.limits(), filmMbps = null))
            assertEquals(ShieldPreset.effectiveTargetMb(safe), overlaid.targetBufferMb)
        }
    }

    // ── formula ─────────────────────────────────────────────────────────────

    @Test
    fun `formula fills the no-stream defaults and leaves DV, audio and resolution alone`() {
        val current = PlayerSettings(
            useParallelConnections = true,
            parallelConnectionCount = 3,
            parallelChunkSizeKb = 8_192,
            enableHttp2 = true,
            frameRateMatchingMode = FrameRateMatchingMode.OFF
        )
        val setup = DeviceSetupFormula.setup(device(1000, 1200), current)
        val p = setup.plan
        assertEquals(true, p.nuvioPerformanceModeEnabled)
        assertEquals(true, p.bufferEngineEnabled)
        assertEquals(true, p.parallelNetworkEnabled)
        assertEquals(false, p.bufferBudgetManaged)
        assertEquals(true, p.allowLargeTargetBuffer)
        // 3 x 8 MB pipe = (3+2) x 8 = 40 MB; largest 25 MB step under 1000 - 40.
        assertEquals(950, p.targetBufferSizeMb)
        assertEquals(120_000, p.maxBufferMs)
        assertEquals(true, p.useParallelConnections)
        assertEquals(3, p.parallelConnectionCount)
        assertEquals(8_192, p.parallelChunkSizeKb)
        assertEquals(true, p.enableHttp2)
        assertEquals(FrameRateMatchingMode.START_STOP, p.frameRateMatchingMode)
        // 4 GB rung: back buffer stays as it is.
        assertEquals(current.bufferSettings.backBufferDurationMs, p.backBufferDurationMs)
        assertEquals(current.bufferSettings.retainBackBufferFromKeyframe, p.retainBackBufferFromKeyframe)
        assertNull(p.resolutionMatchingEnabled)
        assertNull(p.autoSwitchInternalPlayerOnError)
        assertNull(p.dv7HandlingMode)
        assertNull(p.forceOpticalPassthrough)
        assertNull(p.deniedCodecHandling)
        assertNull(p.allowTrueHdPassthrough)
        assertNull(p.matPassthroughEnabled)
        assertEquals(ProfileKind.BALANCED, setup.profile.kind)
        assertEquals(3_000, setup.profile.initialBufferMs)
        assertEquals(5_000, setup.profile.rebufferMs)
        assertEquals(20_000, setup.profile.minBufferMs)
    }

    @Test
    fun `formula turns the back buffer off on the low-RAM rungs`() {
        val current = PlayerSettings(
            bufferSettings = BufferSettings(backBufferDurationMs = 30_000, retainBackBufferFromKeyframe = true)
        )
        for ((safe, warn) in tiers.filter { it.first <= 500 }) {
            val p = DeviceSetupFormula.setup(device(safe, warn), current).plan
            assertEquals(0, p.backBufferDurationMs)
            assertEquals(false, p.retainBackBufferFromKeyframe)
        }
    }

    @Test
    fun `formula keeps a chosen AFR mode, and turns AFR off where the display cannot switch`() {
        val start = PlayerSettings(frameRateMatchingMode = FrameRateMatchingMode.START)
        assertEquals(
            FrameRateMatchingMode.START,
            DeviceSetupFormula.setup(device(1000, 1200), start).plan.frameRateMatchingMode
        )
        val singleRate = device(1000, 1200, display = display4k.copy(supportsFrameRateSwitching = false))
        assertEquals(
            FrameRateMatchingMode.OFF,
            DeviceSetupFormula.setup(singleRate, start).plan.frameRateMatchingMode
        )
        // Display not inspectable: nothing is known, so the current mode stays.
        val unknown = device(1000, 1200, display = display4k.copy(apiSupported = false))
        assertEquals(
            FrameRateMatchingMode.START,
            DeviceSetupFormula.setup(unknown, start).plan.frameRateMatchingMode
        )
        assertEquals(
            FrameRateMatchingMode.OFF,
            DeviceSetupFormula.setup(unknown, PlayerSettings(frameRateMatchingMode = FrameRateMatchingMode.OFF))
                .plan.frameRateMatchingMode
        )
    }

    @Test
    fun `formula sizes the target beside the tier-capped chunk`() {
        // 2 x 32 MB stored on a 16 MB heap tier: the target fits beside (2+2) x 16 = 64, not 128.
        val current = PlayerSettings(
            useParallelConnections = true, parallelConnectionCount = 2, parallelChunkSizeKb = 32_768
        )
        val p = DeviceSetupFormula.setup(device(350, 450, tierMaxChunkMb = 16), current).plan
        assertEquals(16_384, p.parallelChunkSizeKb)
        assertEquals(275, p.targetBufferSizeMb)
    }

    @Test
    fun `fingerprint joins the trimmed identity and RAM rung`() {
        assertEquals(
            "NVIDIA|SHIELD Android TV|mdarcy|2 GB",
            DeviceFingerprint.of("NVIDIA", "SHIELD Android TV", "mdarcy", "2 GB")
        )
        assertEquals("NVIDIA||mdarcy|2 GB", DeviceFingerprint.of(" NVIDIA ", null, "mdarcy", "2 GB"))
    }

    @Test
    fun `formula leaves performance mode off below API 26`() {
        assertEquals(false, DeviceSetupFormula.setup(device(250, 325, apiLevel = 25), PlayerSettings()).plan.nuvioPerformanceModeEnabled)
    }

    // ── fit gate ────────────────────────────────────────────────────────────

    @Test
    fun `gate never exceeds the safe limit, never raises a value and is idempotent on every RAM rung`() {
        val films = listOf<Double?>(null, 20.0, 63.0, 100.0, 400.0)
        var checked = 0
        for ((safe, _) in tiers) for (tierChunk in listOf(16, 32)) {
            val lim = limits(safe, tierChunk)
            for (parallel in listOf(true, false)) for (connections in 2..4)
                for (chunkKb in PlayerSettings.PARALLEL_CHUNK_SIZES_KB)
                    for (target in listOf(25, 150, 350, 1000, 2048))
                        for (back in listOf(0, 30_000)) for (film in films) {
                            val input = config(
                                target = target, back = back, retain = back > 0,
                                parallel = parallel, connections = connections, chunkKb = chunkKb
                            )
                            val fitted = fitPlaybackConfig(input, lim, film)
                            val label = "safe $safe tier $tierChunk $input film $film -> $fitted"
                            assertTrue(label, fitted.nativeMb() <= safe)
                            assertEquals(label, fitted, fitPlaybackConfig(fitted, lim, film))
                            assertTrue(label, fitted.targetBufferMb <= input.targetBufferMb)
                            assertTrue(label, fitted.chunkKb <= input.chunkKb)
                            assertTrue(label, fitted.chunkKb <= tierChunk * 1024)
                            assertTrue(label, fitted.maxBufferMs in fitted.minBufferMs..input.maxBufferMs)
                            assertEquals(label, input.minBufferMs, fitted.minBufferMs)
                            assertTrue(label, fitted.connections <= input.connections)
                            assertTrue(label, fitted.backBufferMs <= input.backBufferMs)
                            assertTrue(label, !fitted.parallel || input.parallel)
                            checked++
                        }
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun `gate gives up chunk size first, then back buffer, then target`() {
        // 250 + (4+2) x 16 = 346 on safe 250: chunk to 8 MB (48), back off, target to 200.
        val fitted = fitPlaybackConfig(config(target = 250, back = 10_000, retain = true), limits(250), null)
        assertEquals(8_192, fitted.chunkKb)
        assertEquals(4, fitted.connections)
        assertEquals(0, fitted.backBufferMs)
        assertFalse(fitted.retainBackBufferFromKeyframe)
        assertEquals(200, fitted.targetBufferMb)
    }

    @Test
    fun `gate keeps a back buffer that fits and leaves the floor forward`() {
        val input = config(target = 500, back = 10_000, retain = false, connections = 2, chunkKb = 8_192)
        // 500 + 32 fits 1000; 10 s of 20 Mbps is ~23 MB, the forward 477 MB is far above the floor.
        assertEquals(10_000, fitPlaybackConfig(input, limits(1000), 20.0).backBufferMs)
        // The same config with no film: the back buffer is not charged, so it stays.
        assertEquals(10_000, fitPlaybackConfig(input, limits(1000), null).backBufferMs)
    }

    @Test
    fun `gate drops a back buffer that eats the forward floor`() {
        // 100 MB target, 30 s retained at 20 Mbps (~143 MB doubled) leaves nothing forward.
        val input = config(target = 100, back = 30_000, retain = true, connections = 2, chunkKb = 8_192)
        assertEquals(0, fitPlaybackConfig(input, limits(1000), 20.0).backBufferMs)
    }

    @Test
    fun `gate holds the forward floor and gives up connections instead`() {
        // safe 195 at 100 Mbps: the floor caps at the depth beside a 2 x 8 MB pipe, 150 MB.
        // 150 + (4+2) x 8 = 198 > 195, so a connection goes: 150 + 5 x 8 = 190.
        val fitted = fitPlaybackConfig(config(target = 300), limits(195), 100.0)
        assertEquals(150, fitted.targetBufferMb)
        assertEquals(8_192, fitted.chunkKb)
        assertEquals(3, fitted.connections)
        assertTrue(fitted.parallel)
    }

    @Test
    fun `below every RAM rung the gate turns parallel off`() {
        // safe 50: 25 + (2+2) x 8 = 57 still over, so the pipe goes; 25 alone fits.
        val fitted = fitPlaybackConfig(config(target = 150), limits(50), null)
        assertFalse(fitted.parallel)
        assertEquals(MemoryBudget.MIN_BUFFER_MB, fitted.targetBufferMb)
        // Under MIN_BUFFER_MB the limit cannot be met: the target stays at the minimum.
        assertEquals(MemoryBudget.MIN_BUFFER_MB, fitPlaybackConfig(config(target = 150), limits(20), null).targetBufferMb)
    }

    @Test
    fun `gate caps max buffer seconds to what the forward bytes hold`() {
        // 150 MiB at 63 Mbps holds ~19.97 s; min 10 s stays below it.
        val fitted = fitPlaybackConfig(config(target = 150, min = 10_000, connections = 2, chunkKb = 8_192), limits(1000), 63.0)
        assertEquals(19_972, fitted.maxBufferMs)
        // Never below min buffer (Media3 asserts max >= min).
        val minAbove = fitPlaybackConfig(config(target = 150, min = 30_000, connections = 2, chunkKb = 8_192), limits(1000), 63.0)
        assertEquals(30_000, minAbove.maxBufferMs)
    }

    @Test
    fun `gate clamps perf mode, connections, tier chunk and AFR to the device`() {
        val lim = DeviceLimits(1000, 16, perfModeSupported = false, afrSupported = false)
        val fitted = fitPlaybackConfig(config(connections = 6, chunkKb = 32_768), lim, null)
        assertFalse(fitted.performanceMode)
        assertEquals(MemoryBudget.MAX_CONNECTIONS, fitted.connections)
        assertEquals(16_384, fitted.chunkKb)
        assertEquals(FrameRateMatchingMode.OFF, fitted.frameRateMatching)
        // Unknown AFR support keeps the mode.
        val unknown = lim.copy(afrSupported = null)
        assertEquals(FrameRateMatchingMode.START_STOP, fitPlaybackConfig(config(), unknown, null).frameRateMatching)
    }
}
