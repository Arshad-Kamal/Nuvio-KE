package com.nuvio.tv.core.assessment

import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.AssessmentFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DeviceFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DisplayFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.RowText
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.network.StreamSweepEngine.BufferTrade
import com.nuvio.tv.core.network.StreamSweepEngine.H2Variant
import com.nuvio.tv.core.network.StreamSweepEngine.Recommendation
import com.nuvio.tv.core.network.StreamSweepEngine.SweepOutcome
import com.nuvio.tv.core.network.StreamSweepEngine.VerdictKind
import com.nuvio.tv.core.player.AudioCapabilityReport.DirectSupport
import com.nuvio.tv.core.player.AudioOutputRoute
import com.nuvio.tv.core.player.DeviceQuirks
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pins DeviceAssessmentEngine.assemble() (L10b-40/43): every row (all
 * fields, in order), the full apply plan, profiles, header and sweep fields,
 * per scenario, as golden text under src/test/resources/assessment-golden/.
 * The goldens were captured from the pre-refactor row logic (moved verbatim
 * into assemble()), so the builder slices must keep them byte-identical.
 *
 * A missing or mismatched golden writes the actual text to
 * app/build/assessment-golden-actual/<scenario>.txt; review it before
 * copying it over a golden.
 *
 * Strings render as their R.string entry name plus args, so a pin reads
 * "assessment_grounds_parallel_rec(210.0 Mbps|4|8)" and survives id churn.
 */
class DeviceAssessmentEngineRowsTest {

    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        // "%.1f Mbps".format uses the default locale.
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val text = RowText { id, args ->
        val name = stringNames[id] ?: "#$id"
        if (args.isEmpty()) name else "$name(${args.joinToString("|")})"
    }

    private val hdmi = AudioOutputRoute("type:hdmi|name:avr", "AVR", false)
    private val arc = AudioOutputRoute("type:hdmi_arc|name:tv", "TV", false)
    private val allDirect = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)

    private val policyDvDisplay = DolbyVisionBaseLayerPolicy.Result(
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

    private val recSafe = Recommendation(
        connections = 4, chunkMb = 8, mbps = 210.0, meetsTarget = true, fitsSafeBudget = true
    )

    private fun sweep(
        kind: VerdictKind,
        rec: Recommendation? = recSafe,
        errorText: String? = null,
        cov: Double? = 0.1,
        h2: H2Variant? = null
    ) = SweepOutcome(
        verdictKind = kind,
        verdictText = "verdict:$kind",
        recommendation = rec,
        baselineMbps = 95.0,
        targetMbps = 160.0,
        measured = emptyList(),
        errorText = errorText,
        stabilityCoV = cov,
        stabilityPassCount = 3,
        h2Variant = h2
    )

    /** High-RAM (4 GB tier) Shield-like box on an HDMI AVR, RECOMMEND sweep. */
    private val device = DeviceFacts(
        quirks = DeviceQuirks("NVIDIA", "SHIELD Android TV"),
        fingerprint = "NVIDIA|SHIELD Android TV|mdarcy|4 GB",
        apiLevel = 30,
        ramLabel = "4 GB",
        safeLimitMb = 1000,
        warningLimitMb = 1200,
        tierMaxChunkMb = 32,
        display = display4k,
        policy = policyDvDisplay
    )

    private val base = AssessmentFacts(
        nowMs = 1_700_000_000_000L,
        device = device,
        diagnostics = LastPlaybackDiagnostics(
            host = "cdn.example.net",
            filename = "Movie.2160p.REMUX.mkv",
            dvSourceProfile = "7",
            videoHdrType = "DV"
        ),
        bitrateMbps = 80.0,
        sweep = sweep(VerdictKind.RECOMMEND_CONFIG),
        audioRoute = hdmi,
        maxPcmChannels = 8,
        direct = allDirect
    )

    /** [AssessmentFacts] with its device display / policy swapped. */
    private fun AssessmentFacts.withDevice(
        display: DisplayFacts = this.device.display,
        policy: DolbyVisionBaseLayerPolicy.Result = this.device.policy
    ) = copy(device = this.device.copy(display = display, policy = policy))

    private val lowRam = base.copy(device = device.copy(safeLimitMb = 350, warningLimitMb = 450, ramLabel = "3 GB"))

    private val defaults = PlayerSettings()

    private data class Scenario(val name: String, val facts: AssessmentFacts, val settings: PlayerSettings)

    private fun sc(name: String, facts: AssessmentFacts = base, settings: PlayerSettings = defaults) =
        Scenario(name, facts, settings)

    private val scenarios: List<Scenario> = listOf(
        // sweep
        sc("sweep_recommend_safe"),
        sc("sweep_null", base.copy(sweep = null, bitrateMbps = null)),
        sc(
            "sweep_leave_parallel_off_while_on",
            base.copy(sweep = sweep(VerdictKind.LEAVE_PARALLEL_OFF, rec = null, cov = null)),
            defaults.copy(useParallelConnections = true)
        ),
        sc(
            "sweep_recommend_unsafe_already_matching",
            base.copy(sweep = sweep(VerdictKind.RECOMMEND_CONFIG, rec = recSafe.copy(fitsSafeBudget = false))),
            defaults.copy(useParallelConnections = true, parallelConnectionCount = 4, parallelChunkSizeKb = 8 * 1024)
        ),
        sc(
            "sweep_buffer_trade",
            base.copy(
                sweep = sweep(
                    VerdictKind.RECOMMEND_CONFIG,
                    rec = recSafe.copy(
                        connections = 3, chunkMb = 4, mbps = 130.0,
                        bufferTrade = BufferTrade(
                            overConnections = 6, overChunkMb = 16, overMbps = 170.0,
                            chosenBufferMb = 850, chosenBufferS = 85, overBufferMb = 600, overBufferS = 60
                        )
                    )
                )
            )
        ),
        sc("sweep_marginal", base.copy(sweep = sweep(VerdictKind.MARGINAL, rec = recSafe.copy(mbps = 120.0)))),
        sc("sweep_cannot_sustain", base.copy(sweep = sweep(VerdictKind.CANNOT_SUSTAIN, rec = recSafe.copy(mbps = 60.0)))),
        sc(
            "sweep_fastest_no_bitrate",
            base.copy(bitrateMbps = null, sweep = sweep(VerdictKind.FASTEST_NO_BITRATE))
        ),
        sc(
            "sweep_error_ignores_h2",
            base.copy(
                sweep = sweep(
                    VerdictKind.NONE, rec = null, errorText = "boom", cov = null,
                    h2 = H2Variant.of(4, 8, h2Mbps = 100.0, h1Mbps = 200.0)
                )
            )
        ),
        sc(
            "sweep_h1_wins",
            base.copy(sweep = sweep(VerdictKind.RECOMMEND_CONFIG, h2 = H2Variant.of(4, 8, h2Mbps = 100.0, h1Mbps = 200.0)))
        ),
        sc(
            "sweep_h2_ok_http2_off",
            base.copy(sweep = sweep(VerdictKind.RECOMMEND_CONFIG, h2 = H2Variant.of(4, 8, h2Mbps = 210.0, h1Mbps = 205.0))),
            defaults.copy(enableHttp2 = false)
        ),
        // audio
        sc("audio_no_route", base.copy(audioRoute = null, maxPcmChannels = null)),
        sc("audio_arc_no_pcm_profile", base.copy(audioRoute = arc, maxPcmChannels = null)),
        sc(
            "audio_hdmi_2ch_ac3_absent",
            base.copy(maxPcmChannels = 2, direct = allDirect.copy(ac3 = false))
        ),
        sc("audio_hdmi_2ch_ac3_ok", base.copy(maxPcmChannels = 2)),
        sc("audio_force_ac3_on_hdmi", settings = defaults.copy(forceOpticalPassthrough = true)),
        sc(
            "audio_force_ac3_on_unknown_route",
            base.copy(audioRoute = null),
            defaults.copy(forceOpticalPassthrough = true)
        ),
        sc(
            "audio_mvp_engine",
            base.copy(direct = null),
            defaults.copy(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER)
        ),
        sc("audio_device_only_decoders", base.copy(direct = null), defaults.copy(decoderPriority = 0)),
        sc("audio_probe_failed", base.copy(direct = null)),
        sc(
            "audio_truehd_ac3_absent_mat_on",
            base.copy(direct = allDirect.copy(ac3 = false, trueHd = false)),
            defaults.copy(matPassthroughEnabled = true, deniedCodecHandling = DeniedCodecHandling.TRANSCODE_AC3)
        ),
        sc(
            "audio_truehd_off_already_mat_on",
            settings = defaults.copy(allowTrueHdPassthrough = false, matPassthroughEnabled = true)
        ),
        sc(
            "audio_dts_core_fallback_2ch",
            base.copy(maxPcmChannels = 2, direct = allDirect.copy(dtsHd = false))
        ),
        sc("audio_dts_core_fallback_8ch", base.copy(direct = allDirect.copy(dtsHd = false))),
        sc(
            "audio_dts_absent_entirely",
            base.copy(direct = allDirect.copy(dts = false, dtsHd = false))
        ),
        // buffer
        sc("buffer_low_ram_tier", lowRam),
        sc(
            "buffer_low_ram_tier_already_lean",
            lowRam,
            defaults.copy(bufferSettings = BufferSettings(backBufferDurationMs = 0, retainBackBufferFromKeyframe = false))
        ),
        sc(
            "buffer_no_bitrate_max_buffer_soft_divergence",
            base.copy(bitrateMbps = null, sweep = null)
        ),
        sc(
            "buffer_current_warning_band",
            lowRam.copy(sweep = null),
            defaults.copy(
                useParallelConnections = true, parallelConnectionCount = 2, parallelChunkSizeKb = 4 * 1024,
                bufferSettings = BufferSettings(targetBufferSizeMb = 350)
            )
        ),
        sc(
            "buffer_current_danger_band",
            lowRam.copy(sweep = null),
            defaults.copy(
                useParallelConnections = true, parallelConnectionCount = 4, parallelChunkSizeKb = 16 * 1024,
                bufferSettings = BufferSettings(targetBufferSizeMb = 500)
            )
        ),
        sc(
            "buffer_all_endorsed",
            base,
            defaults.copy(
                bufferEngineEnabled = true, parallelNetworkEnabled = true, bufferBudgetManaged = false,
                nuvioPerformanceModeEnabled = true
            )
        ),
        sc("perf_mode_unsupported", base.copy(device = device.copy(apiLevel = 25)), defaults.copy(nuvioPerformanceModeEnabled = true)),
        // display
        sc("display_unknown", base.copy(device = device.copy(display = DisplayFacts(false, false, false, "", "", null)))),
        sc("display_afr_already_start", settings = defaults.copy(frameRateMatchingMode = FrameRateMatchingMode.START)),
        sc("display_afr_already_start_stop", settings = defaults.copy(frameRateMatchingMode = FrameRateMatchingMode.START_STOP)),
        sc(
            "display_single_rate_afr_start",
            base.withDevice(
                display = display4k.copy(
                    supportsFrameRateSwitching = false, supportsResolutionSwitching = false,
                    ratesLabel = "60", resolutionsLabel = "1920x1080", currentResolutionLabel = "1920x1080"
                )
            ),
            defaults.copy(frameRateMatchingMode = FrameRateMatchingMode.START, resolutionMatchingEnabled = true)
        ),
        // DV + tunnelling
        sc(
            "dv_caps_unknown_mode_set",
            base.withDevice(
                policy = policyDvDisplay.copy(
                    hdrCapsKnown = false, displayDv = false, displayHdr10Plus = false,
                    decision = DolbyVisionBaseLayerPolicy.Decision.STRIP_BEST_EFFORT
                )
            ),
            defaults.copy(dv7HandlingMode = Dv7HandlingMode.HDR10_BASE_LAYER)
        ),
        sc(
            "dv_known_mode_set_dv5_path_strip_on",
            base.withDevice(
                policy = policyDvDisplay.copy(
                    displayDv = false, displayHdr10Plus = false,
                    decision = DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81
                )
            ),
            defaults.copy(dv7HandlingMode = Dv7HandlingMode.DV81_LIBDOVI, tunnelingEnabled = true)
        ),
        // profiles
        sc(
            "profiles_unstable_link_capped_min",
            base.copy(bitrateMbps = 400.0, sweep = sweep(VerdictKind.RECOMMEND_CONFIG, cov = 0.3))
        ),
        // review 2026-10-09-185917 coverage additions
        sc(
            "sweep_h1_wins_http2_on",
            base.copy(sweep = sweep(VerdictKind.RECOMMEND_CONFIG, h2 = H2Variant.of(4, 8, h2Mbps = 100.0, h1Mbps = 200.0))),
            defaults.copy(enableHttp2 = true)
        ),
        sc(
            "sweep_h2_ok_http2_on",
            base.copy(sweep = sweep(VerdictKind.RECOMMEND_CONFIG, h2 = H2Variant.of(4, 8, h2Mbps = 210.0, h1Mbps = 205.0))),
            defaults.copy(enableHttp2 = true)
        ),
        sc(
            "sweep_none_no_error",
            base.copy(sweep = sweep(VerdictKind.NONE, rec = null, cov = null)),
            defaults.copy(useParallelConnections = true)
        ),
        sc(
            "buffer_endorsed_targets_auto_engine",
            base,
            defaults.copy(
                internalPlayerEngine = InternalPlayerEngine.AUTO,
                allowLargeTargetBuffer = true,
                bufferSettings = BufferSettings(targetBufferSizeMb = 950, maxBufferMs = 95_000)
            )
        ),
        sc("buffer_low_ram_allow_large_on", lowRam, defaults.copy(allowLargeTargetBuffer = true)),
        sc(
            "audio_eac3_absent_already_off",
            base.copy(direct = allDirect.copy(eac3 = false)),
            defaults.copy(allowEac3Passthrough = false)
        ),
        sc(
            "display_single_rate_afr_off_no_current_mode",
            base.withDevice(
                display = display4k.copy(
                    supportsFrameRateSwitching = false, supportsResolutionSwitching = false,
                    ratesLabel = "", resolutionsLabel = "1920x1080", currentResolutionLabel = null
                )
            )
        ),
        sc(
            "dv_no_bridge_tonemap",
            base.withDevice(
                policy = policyDvDisplay.copy(
                    displayDv = false, bridgeReady = false,
                    decision = DolbyVisionBaseLayerPolicy.Decision.STRIP_AND_TONEMAP
                )
            )
        ),
        sc(
            "dv_strip_to_hdr10",
            base.withDevice(
                policy = policyDvDisplay.copy(
                    displayDv = false, codecSupportsDvheSt = false,
                    decision = DolbyVisionBaseLayerPolicy.Decision.STRIP_TO_HDR10
                )
            )
        ),
        sc(
            "dv_strip_best_effort_caps_known",
            base.withDevice(
                policy = policyDvDisplay.copy(
                    displayDv = false, decision = DolbyVisionBaseLayerPolicy.Decision.STRIP_BEST_EFFORT
                )
            )
        )
    )

    // ── rendering ───────────────────────────────────────────────────────────

    private fun esc(v: String?): String = v?.replace("\n", "\\n") ?: "null"

    private fun render(r: AssessmentResult): String = buildString {
        appendLine("timestampMs=${r.timestampMs}")
        appendLine("header=${r.header}")
        appendLine("sweepRan=${r.sweepRan} verdict=${esc(r.sweepVerdictText)} errorText=${esc(r.errorText)}")
        appendLine("stability=${r.stabilityCoV}/${r.stabilityPassCount} suggested=${r.suggestedProfile}")
        appendLine("items:")
        r.items.forEach { i ->
            appendLine(
                "  ${i.key} | ${i.tier} | change=${i.changeNeeded} | mem=${i.memoryStatus}" +
                    " | title=${i.title} | current=${esc(i.currentValue)}" +
                    " | rec=${esc(i.recommendedValue)} | grounds=${esc(i.grounds)}"
            )
        }
        appendLine("profiles:")
        r.profiles.forEach { appendLine("  $it") }
        appendLine("plan (non-null fields; touched=${r.applyPlan.touchedCount}):")
        // Data-class toString lists fields in constructor order (stable);
        // getDeclaredFields order is JVM-unspecified. Values never hold ", ".
        r.applyPlan.toString()
            .substringAfter('(').removeSuffix(")")
            .split(", ")
            .filterNot { it.endsWith("=null") }
            .forEach { appendLine("  $it") }
    }

    private fun planFields() = AssessmentApplyPlan::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    fun `assemble output matches the golden for every scenario`() {
        val mismatched = mutableListOf<String>()
        for (s in scenarios) {
            val actual = render(DeviceAssessmentEngine.assemble(s.facts, s.settings, text))
            val expected = javaClass.getResource("/assessment-golden/${s.name}.txt")?.readText()
            if (expected != actual) {
                mismatched += s.name
                File("build/assessment-golden-actual").apply { mkdirs() }
                    .resolve("${s.name}.txt").writeText(actual)
            }
        }
        if (mismatched.isNotEmpty()) {
            fail("golden mismatch (actual in app/build/assessment-golden-actual/): $mismatched")
        }
    }

    @Test
    fun `scenario names are unique`() {
        assertEquals(scenarios.size, scenarios.map { it.name }.toSet().size)
    }

    /**
     * Two-homes drift guard: a plan field may be set only when its owning row
     * exists, says change-needed, and is not VERIFY. Fields with no engine
     * row map to null and must never be set by the engine.
     */
    @Test
    fun `every set plan field is owned by a change-needed non-VERIFY row`() {
        val owner = mapOf(
            "nuvioPerformanceModeEnabled" to "perf_mode",
            "bufferEngineEnabled" to "buffer_master",
            "parallelNetworkEnabled" to "network_master",
            "bufferBudgetManaged" to "managed_budget",
            "allowLargeTargetBuffer" to "allow_large",
            "targetBufferSizeMb" to "target_buffer",
            "maxBufferMs" to "max_buffer",
            "useParallelConnections" to "parallel",
            "parallelConnectionCount" to "parallel",
            "parallelChunkSizeKb" to "chunk",
            "enableHttp2" to "http2",
            "frameRateMatchingMode" to "afr",
            "resolutionMatchingEnabled" to null,
            "dv7HandlingMode" to "dv7_mode",
            "forceOpticalPassthrough" to "force_ac3",
            "deniedCodecHandling" to "denied_handling",
            "allowAc3Passthrough" to "pt_ac3",
            "allowEac3Passthrough" to "pt_eac3",
            "allowTrueHdPassthrough" to "pt_truehd",
            "allowDtsPassthrough" to "pt_dts",
            "allowDtsHdPassthrough" to "pt_dtshd",
            "matPassthroughEnabled" to "pt_mat",
            "backBufferDurationMs" to "back_buffer",
            "retainBackBufferFromKeyframe" to "retain_keyframe",
            "autoSwitchInternalPlayerOnError" to null
        )
        val fields = planFields()
        assertEquals("owner map must cover every plan field", fields.map { it.name }.toSet(), owner.keys)
        var setSeen = 0
        for (s in scenarios) {
            val r = DeviceAssessmentEngine.assemble(s.facts, s.settings, text)
            val rows = r.items.associateBy { it.key }
            for (f in fields) {
                if (f.get(r.applyPlan) == null) continue
                setSeen++
                val key = owner.getValue(f.name)
                if (key == null) {
                    fail("${s.name}: ${f.name} has no engine row but was set")
                    continue
                }
                val row = rows[key]
                assertTrue("${s.name}: ${f.name} set without row $key", row != null)
                assertTrue("${s.name}: ${f.name} set but $key says no change", row!!.changeNeeded)
                assertTrue("${s.name}: ${f.name} set from VERIFY row $key", row.tier != AssessmentTier.VERIFY)
            }
        }
        assertTrue("scenarios exercise plan writes", setSeen > 0)
    }

    @Test
    fun `exoPassthroughApplies needs an Exo engine and software decoders`() {
        for (engine in InternalPlayerEngine.entries) {
            for (priority in 0..2) {
                val expected = engine != InternalPlayerEngine.MVP_PLAYER && priority != 0
                assertEquals(
                    "$engine/$priority",
                    expected,
                    DeviceAssessmentEngine.exoPassthroughApplies(
                        PlayerSettings(internalPlayerEngine = engine, decoderPriority = priority)
                    )
                )
            }
        }
    }
}
