package com.nuvio.tv.core.assessment

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.DeviceQuirks
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.core.player.AudioCapabilityReport
import com.nuvio.tv.core.player.AudioOutputRoute
import com.nuvio.tv.core.player.AudioOutputRouteDetector
import com.nuvio.tv.core.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.core.player.MemoryBudget
import org.json.JSONObject

/**
 * Device Settings Assessment engine.
 *
 * One flow that gathers queryable device facts, resolves the live DV policy,
 * runs the shared stream sweep against the real last-played stream, and turns
 * the lot into per-setting recommendations labelled by confidence tier
 * (see AssessmentTier) plus the matching [AssessmentApplyPlan]. Nothing here
 * writes a setting: DeviceAssessmentApplier applies a plan (revert snapshot,
 * safe-limit re-check) when the user confirms.
 *
 * [run] = [gatherFacts] (every device/stream read, suspend) + [assemble]
 * (pure: facts + settings + string lookup -> rows and plan), so the row and
 * plan logic is unit-testable without a Context.
 *
 * Grounding rules, agreed and enforced:
 *  - Tier 1 rows come only from the sweep verdict (cheapest configuration
 *    meeting 2x the title bitrate within the safe memory budget - nt38).
 *  - Tier 2 rows cite the queryable fact they were derived from. The dv7
 *    row demotes itself to VERIFY whenever hdrCapsKnown is false; the dv5
 *    and strip-HDR10+ rows are ALWAYS VERIFY and never enter the apply
 *    plan (nt6): their deciding facts ride the display chain's EDID (a
 *    soundbar in the path answers for the TV), so the probe can be
 *    confidently wrong, and strip adds per-sample work on the demux hot
 *    path - field-observed destabilising a 3 GB Tegra on 100 Mbit/s
 *    remuxes. Advisory text stays; one-tap apply does not touch them.
 *  - dv7LibdoviModeOverride is deliberately ABSENT: no queryable fact
 *    decides it (panel-dependent, eyes-on only), so the assessment does
 *    not mention it at all.
 *  - Audio decode probing is out of scope (the only honest AVR decode probe
 *    makes sound), but the platform's direct-playback report is silently
 *    queryable and is the same oracle media3's sink uses. Per-format rows
 *    therefore only ever recommend a switch OFF for a chain-ABSENT format:
 *    that can never remove passthrough that would have worked, and it moves
 *    the format from the vendor decoder onto the FFmpeg path and into the
 *    Transcode-to-AC-3 candidate set. ON is never recommended (the probe
 *    cannot see an F3-learned rejection or a deliberate user choice).
 *  - Tier 3 buffer profiles are user intent; the stability signal only
 *    SUGGESTS one, it never auto-applies. Profile numbers and the stability
 *    threshold are [inferred] initial values, marked as such here.
 *  - Recommendations stay inside the SAFE limit even though the sweep may
 *    PROBE up to the warning limit (probe-to-warning / recommend-to-safe
 *    asymmetry is deliberate).
 */
@UnstableApi
object DeviceAssessmentEngine {

    // Tier-3 intent-profile values, ms. [inferred] starting points, clamped to
    // the byte-derived buffer-seconds ceiling below; the applier writes the
    // user-selected profile's trio.
    private const val FAST_INITIAL_MS = 2_000
    private const val FAST_REBUFFER_MS = 3_000
    private const val FAST_MIN_MS = 10_000
    internal const val BAL_INITIAL_MS = 3_000
    internal const val BAL_REBUFFER_MS = 5_000
    internal const val BAL_MIN_MS = 20_000
    private const val STALL_INITIAL_MS = 5_000
    private const val STALL_REBUFFER_MS = 8_000
    private const val STALL_MIN_MS = 30_000

    // Stability -> suggested profile threshold. [inferred] initial value: a
    // median sub-window CoV above this reads as a link that dips enough to
    // reward deeper buffers.
    private const val STABILITY_STALL_COV = 0.25

    // Max-buffer recommendation bounds, seconds.
    private const val MAX_BUFFER_FLOOR_S = 10
    internal const val MAX_BUFFER_CAP_S = 120

    /**
     * The physical-RAM rungs under 4 GB (safe 150/200/250/350) against the >= 4 GB rungs
     * (1000+), still separated after the F3 re-tiering (3 GB: 500 -> 350). Back buffer goes
     * to 0 there. Not MemoryBudget.isLowRamTier, which is the <= 2 GB rungs only (chunk cap).
     */
    internal fun isUnder4GbRung(safeLimitMb: Int): Boolean = safeLimitMb <= 500

    /** Display facts with the framework Display.Mode labels precomputed. */
    internal data class DisplayFacts(
        val apiSupported: Boolean,
        val supportsFrameRateSwitching: Boolean,
        val supportsResolutionSwitching: Boolean,
        /** Refresh rates at the current resolution, e.g. "23.976/24/60". */
        val ratesLabel: String,
        /** Every resolution, largest first, e.g. "3840x2160/1920x1080". */
        val resolutionsLabel: String,
        /** Current mode's resolution, null when the current mode is absent. */
        val currentResolutionLabel: String?,
        /** Width of the largest mode ([resolutionsLabel]'s first entry), null without modes. */
        val largestWidth: Int? = null
    )

    /**
     * The device reads that need no stream (Spec 33 D2): the assessment and the first-launch
     * formula (DeviceSetupFormula) share them. Audio reads stay out: they must follow the sweep.
     */
    internal data class DeviceFacts(
        val quirks: DeviceQuirks,
        /** [DeviceFingerprint] key the first-launch setup is recorded against. */
        val fingerprint: String,
        val apiLevel: Int,
        val ramLabel: String,
        val safeLimitMb: Int,
        val warningLimitMb: Int,
        /** [MemoryBudget.tierMaxChunkMb] (RAM tier), passed in so tests choose it. */
        val tierMaxChunkMb: Int,
        val display: DisplayFacts,
        val policy: DolbyVisionBaseLayerPolicy.Result
    ) {
        /** Performance mode needs API 26+ (the setter forces it off below). */
        val perfModeSupported: Boolean get() = apiLevel >= Build.VERSION_CODES.O

        fun limits(): DeviceLimits = DeviceLimits(
            safeMb = safeLimitMb,
            tierMaxChunkMb = tierMaxChunkMb,
            perfModeSupported = perfModeSupported,
            afrSupported = display.supportsFrameRateSwitching.takeIf { display.apiSupported }
        )
    }

    /** Every device/stream read [run] makes, so [assemble] stays pure. */
    internal data class AssessmentFacts(
        val nowMs: Long,
        val device: DeviceFacts,
        val diagnostics: LastPlaybackDiagnostics,
        val bitrateMbps: Double?,
        val sweep: StreamSweepEngine.SweepOutcome?,
        val audioRoute: AudioOutputRoute?,
        val maxPcmChannels: Int?,
        /** Null when the probe failed or did not apply ([exoPassthroughApplies]). */
        val direct: AudioCapabilityReport.DirectSupport?
    ) {
        /** The one trustworthiness gate for every sweep-derived row and plan
         *  write: a sweep that ended in error is not evidence. */
        val okSweep: StreamSweepEngine.SweepOutcome?
            get() = sweep?.takeIf { it.errorText == null }
    }

    /** One builder's rows plus the running plan: only this builder's fields
     *  changed, every earlier builder's fields passed through. */
    internal data class Section(val items: List<AssessmentItem>, val plan: AssessmentApplyPlan)

    /** String lookup seam: [run] binds Context.getString, tests bind a fake. */
    internal class RowText(private val lookup: (Int, Array<out Any>) -> String) {
        fun s(resId: Int, vararg args: Any): String = lookup(resId, args)
        val on: String get() = s(R.string.assessment_value_on)
        val off: String get() = s(R.string.assessment_value_off)
        fun onOff(value: Boolean): String = if (value) on else off
    }

    suspend fun run(
        context: Context,
        activity: Activity?,
        settings: PlayerSettings,
        diagnostics: LastPlaybackDiagnostics,
        onSweepState: (String) -> Unit,
        onSweepPassAdded: (String) -> Unit,
        onSweepPassResult: (String, Double?, com.nuvio.tv.core.network.StreamSweepEngine.PassNote?) -> Unit
    ): AssessmentResult {
        val facts = gatherFacts(
            context, activity, settings, diagnostics,
            onSweepState, onSweepPassAdded, onSweepPassResult
        )
        return assemble(facts, settings, RowText { id, args -> context.getString(id, *args) })
    }

    /**
     * All I/O, in a fixed order: device facts, bitrate, the sweep (tens of
     * seconds), then the audio route/PCM/direct reads, which must stay AFTER
     * the sweep so they describe the chain as it is when the rows are built.
     */
    private suspend fun gatherFacts(
        context: Context,
        activity: Activity?,
        settings: PlayerSettings,
        diagnostics: LastPlaybackDiagnostics,
        onSweepState: (String) -> Unit,
        onSweepPassAdded: (String) -> Unit,
        onSweepPassResult: (String, Double?, com.nuvio.tv.core.network.StreamSweepEngine.PassNote?) -> Unit
    ): AssessmentFacts {
        val now = System.currentTimeMillis()
        val device = gatherDeviceFacts(context, activity)

        // ── Last-stream facts + bitrate (same derivation as the stream test) ─
        val streamUrl = diagnostics.streamUrl
        val headers = parseHeaders(diagnostics.headersJson)
        val estimatedBitrate = StreamSweepEngine.estimateBitrate(diagnostics, headers)
        val bitrateMbps = estimatedBitrate?.takeIf { it > 0 }?.let { it / 1_000_000.0 }

        // ── Tier 1: the shared sweep against the real stream ────────────────
        val sweep: StreamSweepEngine.SweepOutcome? = if (!streamUrl.isNullOrBlank()) {
            StreamSweepEngine.run(
                context = context,
                streamUrl = streamUrl,
                headers = headers,
                estimatedBitrate = estimatedBitrate,
                onState = onSweepState,
                onPassAdded = onSweepPassAdded,
                onPassResult = onSweepPassResult
            )
        } else {
            null
        }

        // ── Audio chain (after the sweep) ───────────────────────────────────
        val audioRoute = AudioOutputRouteDetector.detect(context)
        val maxPcmChannels = runCatching { AudioCapabilityReport.readMaxPcmChannelCount(context) }.getOrNull()
        // Probe only when the per-format switches apply, so a device-only or
        // MPV chain never does the (unused) isDirectPlaybackSupported calls.
        val direct: AudioCapabilityReport.DirectSupport? =
            if (exoPassthroughApplies(settings)) {
                runCatching { AudioCapabilityReport.probeDirectSupport() }.getOrNull()
            } else null

        return AssessmentFacts(
            nowMs = now,
            device = device,
            diagnostics = diagnostics,
            bitrateMbps = bitrateMbps,
            sweep = sweep,
            audioRoute = audioRoute,
            maxPcmChannels = maxPcmChannels,
            direct = direct
        )
    }

    /** The queryable device facts, no stream and no audio route (Spec 33 D2). */
    internal fun gatherDeviceFacts(context: Context, activity: Activity?): DeviceFacts {
        val ramLabel = NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel(context)
        val display = activity?.let { DisplayCapabilities.detect(it) }
            ?: DisplayCapabilities.Snapshot.Unknown
        return DeviceFacts(
            quirks = DeviceQuirks.current(),
            fingerprint = DeviceFingerprint.of(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, ramLabel),
            apiLevel = Build.VERSION.SDK_INT,
            ramLabel = ramLabel,
            safeLimitMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context),
            warningLimitMb = NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context),
            tierMaxChunkMb = MemoryBudget.tierMaxChunkMb,
            display = DisplayFacts(
                apiSupported = display.apiSupported,
                supportsFrameRateSwitching = display.supportsFrameRateSwitching,
                supportsResolutionSwitching = display.supportsResolutionSwitching,
                ratesLabel = ratesAtCurrentResolution(display),
                resolutionsLabel = resolutionsLabel(display),
                currentResolutionLabel = currentResolutionLabel(display),
                largestWidth = largestMode(display)?.physicalWidth
            ),
            policy = DolbyVisionBaseLayerPolicy.resolve(context, DoviBridge.isLibraryLoaded)
        )
    }

    /** The assessed feature set (buffers, parallel network, DV pipeline) is the ExoPlayer path. */
    internal fun exoEngineSelected(settings: PlayerSettings): Boolean =
        settings.internalPlayerEngine.usesExoPlayer

    /**
     * Per-format passthrough switches only exist on the Exo engine and are
     * inert when Decoder Priority is Device only (no fallback decoder).
     */
    internal fun exoPassthroughApplies(settings: PlayerSettings): Boolean =
        exoEngineSelected(settings) && settings.decoderPriority != 0

    /** Pure: rows, profiles, header and apply plan from gathered [f]. */
    internal fun assemble(f: AssessmentFacts, settings: PlayerSettings, t: RowText): AssessmentResult {
        val safeLimitMb = f.device.safeLimitMb
        val warningLimitMb = f.device.warningLimitMb
        val ramLabel = f.device.ramLabel
        val display = f.device.display
        val diagnostics = f.diagnostics
        val bitrateMbps = f.bitrateMbps
        val sweep = f.sweep

        val items = mutableListOf<AssessmentItem>()

        // Builders append rows in list order (the UI keeps list order within
        // a tier) and thread the plan through; each sets only its own fields.
        var plan = AssessmentApplyPlan()
        fun add(section: Section) {
            items += section.items
            plan = section.plan
        }

        // ── Tier 1 rows ─────────────────────────────────────────────────────
        add(sweepRows(f, settings, t, plan))

        // ── Tier 2: calculated from device facts (VERIFY where not queryable) ─
        add(networkRows(f, settings, t, plan))
        val buffer = bufferBasis(f, settings)
        add(bufferRows(f, settings, t, buffer, plan))
        add(audioRows(f, settings, t, plan))
        add(displayRows(f, settings, t, plan))
        add(dvRows(f, settings, t, plan))

        // ── Tier 3: intent profiles (user chooses; stability only suggests) ─
        val maxMinMs = buffer.recMaxS * 1000
        fun profile(kind: ProfileKind, titleRes: Int, subRes: Int, i: Int, r: Int, m: Int): IntentProfileOption {
            val minMs = m.coerceAtMost(maxMinMs)
            return IntentProfileOption(
                kind = kind,
                title = t.s(titleRes),
                subtitle = t.s(subRes, i / 1000),
                initialBufferMs = i,
                rebufferMs = r,
                minBufferMs = minMs,
                minCappedFromMs = m.takeIf { minMs < it }
            )
        }
        val profiles = listOf(
            profile(
                ProfileKind.FAST_START,
                R.string.assessment_profile_fast_title,
                R.string.assessment_profile_fast_sub,
                FAST_INITIAL_MS, FAST_REBUFFER_MS, FAST_MIN_MS
            ),
            profile(
                ProfileKind.BALANCED,
                R.string.assessment_profile_balanced_title,
                R.string.assessment_profile_balanced_sub,
                BAL_INITIAL_MS, BAL_REBUFFER_MS, BAL_MIN_MS
            ),
            profile(
                ProfileKind.STALL_RESISTANT,
                R.string.assessment_profile_stall_title,
                R.string.assessment_profile_stall_sub,
                STALL_INITIAL_MS, STALL_REBUFFER_MS, STALL_MIN_MS
            )
        )
        val suggestedProfile = sweep?.stabilityCoV?.let { cov ->
            if (cov > STABILITY_STALL_COV) ProfileKind.STALL_RESISTANT else ProfileKind.BALANCED
        }

        // ── Header facts ────────────────────────────────────────────────────
        val displaySummary = if (display.apiSupported) {
            val res = display.currentResolutionLabel
            val rates = display.ratesLabel
            listOfNotNull(res, rates.takeIf { it.isNotBlank() }?.plus(" Hz"))
                .joinToString(" \u00b7 ")
                .ifBlank { null }
        } else {
            null
        }
        val header = AssessmentHeaderFacts(
            deviceRamLabel = ramLabel,
            safeLimitMb = safeLimitMb,
            warningLimitMb = warningLimitMb,
            displaySummary = displaySummary,
            streamLabel = diagnostics.filename ?: diagnostics.host.takeIf { it.isNotBlank() },
            streamBitrateMbps = bitrateMbps,
            streamDvProfile = diagnostics.dvSourceProfile,
            streamHdrType = diagnostics.videoHdrType
        )

        return AssessmentResult(
            timestampMs = f.nowMs,
            header = header,
            sweepRan = sweep != null,
            sweepVerdictText = sweep?.verdictText,
            items = items.toList(),
            profiles = profiles,
            suggestedProfile = suggestedProfile,
            stabilityCoV = sweep?.stabilityCoV,
            stabilityPassCount = sweep?.stabilityPassCount ?: 0,
            applyPlan = plan,
            errorText = sweep?.errorText
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Tier 1 builder: the parallel + chunk rows and their three plan fields,
     * from a trustworthy sweep only. Each decision is computed once and feeds
     * both the row's changeNeeded and its plan write; rows and writes share
     * one verdict branch, so a recommendation on a LEAVE/NONE verdict (the
     * sweep never attaches one) writes no count/chunk without a row (LEAVE
     * still writes its own useParallelConnections=false).
     */
    internal fun sweepRows(
        f: AssessmentFacts,
        settings: PlayerSettings,
        t: RowText,
        plan: AssessmentApplyPlan
    ): Section {
        val sweep = f.okSweep ?: return Section(emptyList(), plan)
        val currentChunkLabel = chunkLabel(t, settings.parallelChunkSizeKb)
        val currentParallelLabel = if (settings.useParallelConnections) {
            t.s(R.string.assessment_current_parallel_on, settings.parallelConnectionCount, currentChunkLabel)
        } else {
            t.off
        }
        return when (sweep.verdictKind) {
            StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF -> {
                val turnOff = settings.useParallelConnections
                Section(
                    listOf(
                        AssessmentItem(
                            key = "parallel",
                            title = t.s(R.string.assessment_item_parallel),
                            currentValue = currentParallelLabel,
                            recommendedValue = t.off,
                            grounds = t.s(
                                R.string.assessment_grounds_parallel_off,
                                "%.1f Mbps".format(sweep.baselineMbps)
                            ),
                            tier = AssessmentTier.MEASURED,
                            changeNeeded = turnOff
                        )
                    ),
                    plan.copy(useParallelConnections = false.takeIf { turnOff })
                )
            }
            StreamSweepEngine.VerdictKind.RECOMMEND_CONFIG,
            StreamSweepEngine.VerdictKind.MARGINAL,
            StreamSweepEngine.VerdictKind.CANNOT_SUSTAIN,
            StreamSweepEngine.VerdictKind.FASTEST_NO_BITRATE -> {
                val rec = sweep.recommendation ?: return Section(emptyList(), plan)
                val groundsRes = when (sweep.verdictKind) {
                    StreamSweepEngine.VerdictKind.RECOMMEND_CONFIG ->
                        if (rec.fitsSafeBudget) R.string.assessment_grounds_parallel_rec
                        else R.string.assessment_grounds_parallel_rec_unsafe
                    StreamSweepEngine.VerdictKind.MARGINAL ->
                        R.string.assessment_grounds_parallel_marginal
                    StreamSweepEngine.VerdictKind.CANNOT_SUSTAIN ->
                        R.string.assessment_grounds_parallel_cannot
                    else -> R.string.assessment_grounds_parallel_fastest
                }
                val overhead = MemoryBudget.parallelOverheadMb(rec.connections, rec.chunkMb)
                val turnOn = !settings.useParallelConnections
                val countWrite = rec.connections.takeIf { settings.parallelConnectionCount != it }
                val chunkWrite = (rec.chunkMb * 1024).takeIf { settings.parallelChunkSizeKb != it }
                Section(
                    listOf(
                        AssessmentItem(
                            key = "parallel",
                            title = t.s(R.string.assessment_item_parallel),
                            currentValue = currentParallelLabel,
                            recommendedValue = t.s(
                                R.string.assessment_current_parallel_on,
                                rec.connections,
                                t.s(R.string.assessment_unit_mb, rec.chunkMb)
                            ),
                            grounds = rec.bufferTrade?.let { trade ->
                                t.s(
                                    R.string.assessment_grounds_parallel_trade,
                                    "%.1f Mbps".format(rec.mbps),
                                    rec.connections,
                                    rec.chunkMb,
                                    trade.overConnections,
                                    trade.overChunkMb,
                                    trade.chosenBufferMb,
                                    trade.chosenBufferS,
                                    trade.overBufferMb,
                                    trade.overBufferS
                                )
                            } ?: t.s(
                                groundsRes,
                                "%.1f Mbps".format(rec.mbps),
                                rec.connections,
                                rec.chunkMb
                            ),
                            tier = AssessmentTier.MEASURED,
                            changeNeeded = turnOn || countWrite != null || chunkWrite != null,
                            memoryStatus = MemoryBudget.getUsageStatus(
                                overhead, f.device.safeLimitMb, f.device.warningLimitMb
                            )
                        ),
                        AssessmentItem(
                            key = "chunk",
                            title = t.s(R.string.assessment_item_chunk),
                            currentValue = currentChunkLabel,
                            recommendedValue = t.s(R.string.assessment_unit_mb, rec.chunkMb),
                            grounds = t.s(R.string.assessment_grounds_chunk),
                            tier = AssessmentTier.MEASURED,
                            changeNeeded = chunkWrite != null
                        )
                    ),
                    plan.copy(
                        useParallelConnections = true.takeIf { turnOn },
                        parallelConnectionCount = countWrite,
                        parallelChunkSizeKb = chunkWrite
                    )
                )
            }
            StreamSweepEngine.VerdictKind.NONE -> Section(emptyList(), plan)
        }
    }

    /**
     * Network builder: the engine row (advisory, never in the plan: switching
     * players is not a knob), network_master and http2 with their writes.
     */
    internal fun networkRows(
        f: AssessmentFacts,
        settings: PlayerSettings,
        t: RowText,
        plan: AssessmentApplyPlan
    ): Section {
        // The whole assessed feature set (buffers, parallel network, DV
        // pipeline) is the ExoPlayer path; under the MVP player
        // none of it takes effect, so say so before recommending any of it.
        val engineOk = exoEngineSelected(settings)
        val engineLabel = when (settings.internalPlayerEngine) {
            InternalPlayerEngine.EXOPLAYER -> t.s(R.string.assessment_value_engine_exo)
            InternalPlayerEngine.AUTO -> t.s(R.string.assessment_value_engine_auto)
            InternalPlayerEngine.MVP_PLAYER -> t.s(R.string.assessment_value_engine_mvp)
        }
        val engine = AssessmentItem(
            key = "engine",
            title = t.s(R.string.assessment_item_engine),
            currentValue = engineLabel,
            // When the engine is right, the row ENDORSES the current value
            // (green tick) instead of a vague no-change.
            recommendedValue = if (engineOk) engineLabel
            else t.s(R.string.assessment_value_engine_exo),
            grounds = if (engineOk) t.s(R.string.assessment_grounds_engine_ok)
            else t.s(R.string.assessment_grounds_engine_switch),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = !engineOk
        )

        val turnNetworkOn = !settings.parallelNetworkEnabled
        val networkMaster = AssessmentItem(
            key = "network_master",
            title = t.s(R.string.assessment_item_network_master),
            currentValue = t.onOff(settings.parallelNetworkEnabled),
            recommendedValue = t.on,
            grounds = t.s(R.string.assessment_grounds_network_master),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = turnNetworkOn
        )

        // F4: the sweep's h2 A/B cell decides this row when it ran. h2
        // multiplexes the parallel streams over one pooled socket; when
        // per-socket h1.1 measured materially faster at the swept best
        // config, this source throttles per connection and the setting
        // should go off. Without the variant the row recommends the fork
        // default, HTTP/1.1 (Spec 36/S6), with the interaction caveat
        // spelled out; it never turns h2 on unmeasured.
        // Gated on okSweep like the sibling sweep-derived rows and writes.
        val h2Decision = http2RowDecision(f.okSweep?.h2Variant)
        val http2Write = h2Decision.recommendOn.takeIf { settings.enableHttp2 != it }
        val http2 = AssessmentItem(
            key = "http2",
            title = t.s(R.string.assessment_item_http2),
            currentValue = t.onOff(settings.enableHttp2),
            recommendedValue = t.onOff(h2Decision.recommendOn),
            grounds = when (val g = h2Decision.grounds) {
                is Http2Grounds.Caveat -> t.s(R.string.assessment_grounds_http2)
                is Http2Grounds.Measured -> t.s(
                    if (h2Decision.recommendOn) R.string.assessment_grounds_http2_h2_ok
                    else R.string.assessment_grounds_http2_h1_wins,
                    g.connections,
                    g.chunkMb,
                    "%.1f Mbps".format(g.recommendedMbps),
                    "%.1f Mbps".format(g.otherMbps)
                )
            },
            tier = if (h2Decision.measured) AssessmentTier.MEASURED else AssessmentTier.CALCULATED,
            changeNeeded = http2Write != null
        )

        return Section(
            listOf(engine, networkMaster, http2),
            plan.copy(
                parallelNetworkEnabled = true.takeIf { turnNetworkOn },
                enableHttp2 = http2Write
            )
        )
    }

    /** The buffer rows' shared numbers, computed once; profiles read [recMaxS]. */
    internal data class BufferBasis(
        val overheadForTargetMb: Int,
        /** Largest 25 MB step where target + overhead stays <= the safe limit. */
        val recTargetMb: Int,
        /** Seconds [recTargetMb] holds at the title bitrate; null without one. */
        val capacityS: Int?,
        val recMaxS: Int,
        /** Under 4 GB ([isUnder4GbRung]); not MemoryBudget.isLowRamTier (<= 2 GB). */
        val lowRamTier: Boolean
    )

    internal fun bufferBasis(f: AssessmentFacts, settings: PlayerSettings): BufferBasis {
        val sweep = f.okSweep
        val rec = sweep?.recommendation
        val overheadForTargetMb = when {
            rec != null -> MemoryBudget.parallelOverheadMb(rec.connections, rec.chunkMb)
            sweep?.verdictKind == StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF -> 0
            settings.useParallelConnections ->
                MemoryBudget.parallelOverheadMb(settings.parallelConnectionCount, currentChunkMb(settings))
            else -> 0
        }
        val recTargetMb = MemoryBudget.maxSafeTargetMb(f.device.safeLimitMb, overheadForTargetMb)
            .coerceAtMost(PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB)
        // Max buffer: byte-derived ceiling - seconds the recommended target can
        // actually hold at this title's bitrate.
        val capacityS = f.bitrateMbps?.takeIf { it > 0 }?.let { (recTargetMb * 8.0 / it).toInt() }
        return BufferBasis(
            overheadForTargetMb = overheadForTargetMb,
            recTargetMb = recTargetMb,
            capacityS = capacityS,
            recMaxS = (capacityS ?: MAX_BUFFER_CAP_S).coerceIn(MAX_BUFFER_FLOOR_S, MAX_BUFFER_CAP_S),
            lowRamTier = isUnder4GbRung(f.device.safeLimitMb)
        )
    }

    /** Current chunk size in whole MB, rounded up, for overhead accounting. */
    private fun currentChunkMb(settings: PlayerSettings): Int = MemoryBudget.chunkMbOf(settings.parallelChunkSizeKb)

    /** Buffer builder: perf_mode through retain_keyframe and their eight writes. */
    internal fun bufferRows(
        f: AssessmentFacts,
        settings: PlayerSettings,
        t: RowText,
        b: BufferBasis,
        plan: AssessmentApplyPlan
    ): Section {
        val safeLimitMb = f.device.safeLimitMb
        val warningLimitMb = f.device.warningLimitMb
        val buf = settings.bufferSettings
        val items = mutableListOf<AssessmentItem>()

        val perfModeSupported = f.device.perfModeSupported
        val perfChange = settings.nuvioPerformanceModeEnabled != perfModeSupported
        items += AssessmentItem(
            key = "perf_mode",
            title = t.s(R.string.assessment_item_perf_mode),
            currentValue = t.onOff(settings.nuvioPerformanceModeEnabled),
            recommendedValue = t.onOff(perfModeSupported),
            grounds = if (perfModeSupported) {
                t.s(
                    R.string.assessment_grounds_perf_mode,
                    f.device.ramLabel,
                    safeLimitMb,
                    warningLimitMb,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                )
            } else {
                t.s(R.string.assessment_grounds_perf_mode_unsupported)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = perfChange
        )

        val turnEngineOn = !settings.bufferEngineEnabled
        items += AssessmentItem(
            key = "buffer_master",
            title = t.s(R.string.assessment_item_buffer_master),
            currentValue = t.onOff(settings.bufferEngineEnabled),
            recommendedValue = t.on,
            grounds = t.s(R.string.assessment_grounds_buffer_master),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = turnEngineOn
        )
        val turnBudgetOff = settings.bufferBudgetManaged
        items += AssessmentItem(
            key = "managed_budget",
            title = t.s(R.string.assessment_item_managed_budget),
            currentValue = t.onOff(settings.bufferBudgetManaged),
            recommendedValue = t.off,
            grounds = t.s(R.string.assessment_grounds_managed_budget),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = turnBudgetOff
        )

        val recTargetMb = b.recTargetMb
        val overheadForTargetMb = b.overheadForTargetMb
        val targetChange = buf.targetBufferSizeMb != recTargetMb
        items += AssessmentItem(
            key = "target_buffer",
            title = t.s(R.string.assessment_item_target_buffer),
            currentValue = t.s(R.string.assessment_unit_mb, buf.targetBufferSizeMb),
            recommendedValue = t.s(R.string.assessment_unit_mb, recTargetMb),
            grounds = buildString {
                append(
                    t.s(
                        R.string.assessment_grounds_target_buffer,
                        recTargetMb,
                        overheadForTargetMb,
                        recTargetMb + overheadForTargetMb,
                        safeLimitMb,
                        MemoryBudget.BUFFER_STEP_MB
                    )
                )
                // Acknowledge a working current config honestly instead of
                // implying it is broken: warning band = reduced headroom,
                // not failure; past the warning limit = genuine kill risk.
                val currentOverheadMb = if (settings.useParallelConnections) {
                    MemoryBudget.parallelOverheadMb(settings.parallelConnectionCount, currentChunkMb(settings))
                } else 0
                val currentSumMb = buf.targetBufferSizeMb + currentOverheadMb
                if (currentSumMb > warningLimitMb) {
                    append('\n')
                    append(
                        t.s(
                            R.string.assessment_grounds_target_current_danger,
                            buf.targetBufferSizeMb,
                            currentOverheadMb,
                            currentSumMb,
                            warningLimitMb
                        )
                    )
                } else if (currentSumMb > safeLimitMb) {
                    append('\n')
                    append(
                        t.s(
                            R.string.assessment_grounds_target_current_warning,
                            buf.targetBufferSizeMb,
                            currentOverheadMb,
                            currentSumMb,
                            safeLimitMb,
                            warningLimitMb
                        )
                    )
                }
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = targetChange,
            memoryStatus = MemoryBudget.getUsageStatus(
                recTargetMb + overheadForTargetMb, safeLimitMb, warningLimitMb
            )
        )

        val allowLargeNeeded = recTargetMb > MemoryBudget.maxBufferMb(overheadForTargetMb)
        val allowLargeChange = settings.allowLargeTargetBuffer != allowLargeNeeded
        items += AssessmentItem(
            key = "allow_large",
            title = t.s(R.string.assessment_item_allow_large),
            currentValue = t.onOff(settings.allowLargeTargetBuffer),
            recommendedValue = t.onOff(allowLargeNeeded),
            grounds = if (allowLargeNeeded) {
                t.s(R.string.assessment_grounds_allow_large_on, recTargetMb)
            } else {
                t.s(R.string.assessment_grounds_allow_large_off)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = allowLargeChange
        )

        val capacityS = b.capacityS
        val bitrateMbps = f.bitrateMbps
        val maxChange = buf.maxBufferMs / 1000 != b.recMaxS
        items += AssessmentItem(
            key = "max_buffer",
            title = t.s(R.string.assessment_item_max_buffer),
            currentValue = t.s(R.string.assessment_value_seconds, buf.maxBufferMs / 1000),
            recommendedValue = t.s(R.string.assessment_value_seconds, b.recMaxS),
            // capacityS != null implies a positive bitrate (bufferBasis).
            grounds = if (capacityS != null && bitrateMbps != null) {
                buildString {
                    append(
                        t.s(
                            R.string.assessment_grounds_max_buffer,
                            recTargetMb,
                            capacityS,
                            "%.1f Mbps".format(bitrateMbps)
                        )
                    )
                    val currentTargetMb = buf.targetBufferSizeMb
                    if (currentTargetMb != recTargetMb) {
                        append('\n')
                        append(
                            t.s(
                                R.string.assessment_grounds_max_buffer_current,
                                currentTargetMb,
                                (currentTargetMb * 8.0 / bitrateMbps).toInt()
                            )
                        )
                    }
                }
            } else {
                t.s(R.string.assessment_grounds_max_buffer_nobitrate, MAX_BUFFER_CAP_S)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = maxChange
        )

        // F2: back-buffer rows. Both are RAM-tier keyed — on the ≤3 GB tiers
        // the recommendation is 0 / retain-off (the budget the preset pins);
        // higher tiers keep seek-back, and the rows become endorsements.
        // Back-buffer bytes are charged INCLUSIVELY against the allocator
        // budget (see BufferSettings docs — DefaultLoadControl compares
        // forward + back against the byte target), so they eat the forward
        // cushion inside the target rather than adding above it: total usage
        // stays target + overhead. The estimated MB only feeds the grounds.
        val lowRamTier = b.lowRamTier
        val backChange = lowRamTier && buf.backBufferDurationMs != 0
        val currentBackBufferMb = MemoryBudget.backBufferMb(
            buf.backBufferDurationMs,
            buf.retainBackBufferFromKeyframe,
            bitrateMbps
        )
        items += AssessmentItem(
            key = "back_buffer",
            title = t.s(R.string.assessment_item_back_buffer),
            currentValue = t.s(R.string.assessment_value_seconds, buf.backBufferDurationMs / 1000),
            recommendedValue = if (lowRamTier) {
                t.s(R.string.assessment_value_seconds, 0)
            } else {
                t.s(R.string.assessment_value_leave_as_is)
            },
            grounds = if (lowRamTier) {
                t.s(R.string.assessment_grounds_back_buffer_low, currentBackBufferMb)
            } else {
                t.s(R.string.assessment_grounds_back_buffer_high)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = backChange
        )
        val retainChange = lowRamTier && buf.retainBackBufferFromKeyframe
        items += AssessmentItem(
            key = "retain_keyframe",
            title = t.s(R.string.assessment_item_retain_keyframe),
            currentValue = t.onOff(buf.retainBackBufferFromKeyframe),
            recommendedValue = if (lowRamTier) t.off else t.s(R.string.assessment_value_leave_as_is),
            grounds = if (lowRamTier) {
                t.s(R.string.assessment_grounds_retain_low)
            } else {
                t.s(R.string.assessment_grounds_retain_high)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = retainChange
        )

        return Section(
            items,
            plan.copy(
                nuvioPerformanceModeEnabled = perfModeSupported.takeIf { perfChange },
                bufferEngineEnabled = true.takeIf { turnEngineOn },
                bufferBudgetManaged = false.takeIf { turnBudgetOff },
                allowLargeTargetBuffer = allowLargeNeeded.takeIf { allowLargeChange },
                targetBufferSizeMb = recTargetMb.takeIf { targetChange },
                // Never WRITE a max-buffer value the engine couldn't ground: with
                // no title bitrate the ceiling is the generic fallback, so the row
                // stays display-only (its grounds already say why).
                maxBufferMs = (b.recMaxS * 1000).takeIf { maxChange && capacityS != null },
                // Low-RAM tiers only. The runtime honors both settings
                // (NuvioExoPlayerPerformanceHelper.buildLoadControl +
                // BitrateAwareLoadControl construction).
                backBufferDurationMs = 0.takeIf { backChange },
                retainBackBufferFromKeyframe = false.takeIf { retainChange }
            )
        )
    }

    /**
     * Audio builder: force_ac3, denied_handling, the per-format passthrough
     * switches and pt_mat, with their writes.
     */
    internal fun audioRows(
        f: AssessmentFacts,
        settings: PlayerSettings,
        t: RowText,
        plan: AssessmentApplyPlan
    ): Section {
        val items = mutableListOf<AssessmentItem>()

        // Force AC-3 transcode vs the actual audio route. The audio-decode
        // scope-out stands (no silent AVR probe exists); the ROUTE TYPE is
        // silently queryable, and on an HDMI chain this setting only turns
        // lossless passthrough into lossy AC-3.
        val audioRoute = f.audioRoute
        val audioRouteType = audioRoute?.key
            ?.removePrefix("type:")?.substringBefore("|")
            ?.let { raw ->
                when (raw) {
                    "hdmi" -> "HDMI"
                    "hdmi_arc" -> "HDMI ARC"
                    "hdmi_earc" -> "HDMI eARC"
                    else -> raw
                }
            }
        // The route when it is any HDMI type (plain, ARC, eARC), else null.
        val hdmiRoute = audioRoute?.takeIf { AudioOutputRouteDetector.isHdmiRouteKey(it.key) }
        val forceAc3Off = hdmiRoute != null && settings.forceOpticalPassthrough
        items += if (hdmiRoute != null) {
            AssessmentItem(
                key = "force_ac3",
                title = t.s(R.string.assessment_item_ac3),
                currentValue = t.onOff(settings.forceOpticalPassthrough),
                recommendedValue = t.off,
                grounds = t.s(
                    R.string.assessment_grounds_ac3_hdmi,
                    audioRouteType ?: "HDMI",
                    hdmiRoute.label
                ),
                tier = AssessmentTier.CALCULATED,
                changeNeeded = forceAc3Off
            )
        } else {
            AssessmentItem(
                key = "force_ac3",
                title = t.s(R.string.assessment_item_ac3),
                currentValue = t.onOff(settings.forceOpticalPassthrough),
                recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                grounds = t.s(R.string.assessment_grounds_ac3_unknown),
                tier = AssessmentTier.VERIFY,
                changeNeeded = false
            )
        }

        // F5: denied-codec handling default per chain. Keyed on the max PCM channel
        // count the output negotiates - the one capability no playback failure can
        // teach, because a 2 ch-LPCM chain plays multichannel PCM "successfully"
        // (collapsed to stereo) and nothing ever errors for F3 to learn from.
        // TRANSCODE_AC3 is only recommended when AC-3 passthrough is itself enabled;
        // DeniedTranscodePlanner re-guards learned AC-3 rejections at build time, so
        // a wrong recommendation degrades to PCM decode rather than stranding.
        val maxPcmChannels = f.maxPcmChannels
        val direct = f.direct
        // HDMI ARC carries 2-channel LPCM only (multichannel LPCM needs eARC) -
        // an HDMI-spec fact, not an EDID reading - so on an ARC route with no
        // readable PCM profile (API < 31) the 2-channel answer is inferred.
        val arcInferredTwoCh = maxPcmChannels == null &&
            audioRoute != null && audioRoute.key.startsWith("type:hdmi_arc")
        val effectivePcmChannels = maxPcmChannels ?: (2.takeIf { arcInferredTwoCh })
        // AC-3 must be usable as the transcode target: switch on AND (when the probe
        // answered) claimed by the chain.
        val ac3Usable = settings.allowAc3Passthrough && direct?.ac3 != false
        val deniedHandlingTarget: DeniedCodecHandling? = when {
            hdmiRoute == null -> null
            effectivePcmChannels == null -> null
            effectivePcmChannels <= 2 && ac3Usable -> DeniedCodecHandling.TRANSCODE_AC3
            effectivePcmChannels > 2 -> DeniedCodecHandling.DECODE_PCM
            else -> null
        }
        val deniedWrite = deniedHandlingTarget?.takeIf { settings.deniedCodecHandling != it }
        fun deniedLabel(mode: DeniedCodecHandling): String =
            if (mode == DeniedCodecHandling.TRANSCODE_AC3) t.s(R.string.denied_mode_ac3)
            else t.s(R.string.denied_mode_pcm)
        items += if (deniedHandlingTarget != null) {
            AssessmentItem(
                key = "denied_handling",
                title = t.s(R.string.denied_handling_title),
                currentValue = deniedLabel(settings.deniedCodecHandling),
                recommendedValue = deniedLabel(deniedHandlingTarget),
                grounds = when {
                    deniedHandlingTarget == DeniedCodecHandling.TRANSCODE_AC3 && arcInferredTwoCh ->
                        t.s(R.string.assessment_grounds_denied_arc_2ch)
                    deniedHandlingTarget == DeniedCodecHandling.TRANSCODE_AC3 ->
                        t.s(R.string.assessment_grounds_denied_2ch)
                    else ->
                        t.s(R.string.assessment_grounds_denied_multich, maxPcmChannels ?: 0)
                },
                tier = AssessmentTier.CALCULATED,
                changeNeeded = deniedWrite != null
            )
        } else {
            AssessmentItem(
                key = "denied_handling",
                title = t.s(R.string.denied_handling_title),
                currentValue = deniedLabel(settings.deniedCodecHandling),
                recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                grounds = t.s(R.string.assessment_grounds_denied_unknown),
                tier = AssessmentTier.VERIFY,
                changeNeeded = false
            )
        }
        val chainPlan = plan.copy(
            forceOpticalPassthrough = false.takeIf { forceAc3Off },
            deniedCodecHandling = deniedWrite
        )

        // ── Per-format passthrough switches vs the platform's direct report ─
        // Writes are null (leave as is) or false (turn off); never true. The
        // branches mirror exoPassthroughApplies (the gate gatherFacts probes
        // under): engine first, then Decoder Priority, then the probe itself.
        val ptGate = when {
            !exoEngineSelected(settings) -> R.string.assessment_grounds_pt_not_exo
            settings.decoderPriority == 0 -> R.string.assessment_grounds_pt_device_only
            else -> null
        }
        if (ptGate != null || direct == null) {
            items += AssessmentItem(
                key = "pt_switches",
                title = t.s(R.string.assessment_item_pt_switches),
                currentValue = null,
                recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                grounds = t.s(ptGate ?: R.string.assessment_grounds_pt_unavailable),
                tier = AssessmentTier.VERIFY,
                changeNeeded = false
            )
            return Section(items, chainPlan)
        }
        val ac3 = ptRow(t, "pt_ac3", R.string.audio_passthrough_ac3, "AC-3",
            direct.ac3, settings.allowAc3Passthrough, recommendOff = true)
        val eac3 = ptRow(t, "pt_eac3", R.string.audio_passthrough_eac3, "E-AC-3",
            direct.eac3, settings.allowEac3Passthrough, recommendOff = true)
        val trueHd = ptRow(t, "pt_truehd", R.string.audio_passthrough_truehd, "TrueHD",
            direct.trueHd, settings.allowTrueHdPassthrough, recommendOff = true)
        val dts = ptRow(t, "pt_dts", R.string.audio_passthrough_dts, "DTS",
            direct.dts, settings.allowDtsPassthrough, recommendOff = true)
        // DTS-HD absent but DTS claimed: media3 relabels DTS-HD to DTS and the
        // receiver decodes the core - lossy 5.1 passthrough. Only worth giving
        // up when the chain provably takes multichannel LPCM (lossless decode
        // wins); on a 2-channel or unknown chain the core beats AC-3 or stereo.
        val dtsCoreFallback = !direct.dtsHd && direct.dts
        val dtsHdOffWins = !dtsCoreFallback || ((maxPcmChannels ?: 0) > 2)
        val dtsHd = ptRow(t, "pt_dtshd", R.string.audio_passthrough_dtshd, "DTS-HD",
            direct.dtsHd, settings.allowDtsHdPassthrough,
            recommendOff = dtsHdOffWins,
            offGroundsRes = if (dtsCoreFallback) R.string.assessment_grounds_pt_dtshd_multich
                            else R.string.assessment_grounds_pt_absent,
            keepGroundsRes = R.string.assessment_grounds_pt_dtshd_core)
        items += listOf(ac3.item, eac3.item, trueHd.item, dts.item, dtsHd.item)
        // MAT rides TrueHD: pin it off whenever TrueHD is, or will be, off.
        val trueHdEndsOff = trueHd.write == false || !settings.allowTrueHdPassthrough
        val matOff = trueHdEndsOff && settings.matPassthroughEnabled
        if (matOff) {
            items += AssessmentItem(
                key = "pt_mat", title = t.s(R.string.audio_mat_passthrough),
                currentValue = t.on, recommendedValue = t.off,
                grounds = t.s(R.string.assessment_grounds_pt_mat),
                tier = AssessmentTier.CALCULATED, changeNeeded = true
            )
        }
        return Section(
            items,
            chainPlan.copy(
                allowAc3Passthrough = ac3.write,
                allowEac3Passthrough = eac3.write,
                allowTrueHdPassthrough = trueHd.write,
                allowDtsPassthrough = dts.write,
                allowDtsHdPassthrough = dtsHd.write,
                matPassthroughEnabled = false.takeIf { matOff }
            )
        )
    }

    /** A per-format passthrough row and its write: false (turn off) or null. */
    private class PtRow(val item: AssessmentItem, val write: Boolean?)

    /**
     * Claimed by the chain: VERIFY, leave as is. Unclaimed and [recommendOff]:
     * CALCULATED, recommend off (write only when currently on). Unclaimed but
     * worth keeping: VERIFY with [keepGroundsRes].
     */
    private fun ptRow(
        t: RowText,
        key: String,
        titleRes: Int,
        label: String,
        claimed: Boolean,
        current: Boolean,
        recommendOff: Boolean,
        offGroundsRes: Int = R.string.assessment_grounds_pt_absent,
        keepGroundsRes: Int? = null
    ): PtRow = when {
        claimed -> PtRow(
            AssessmentItem(
                key = key, title = t.s(titleRes),
                currentValue = t.onOff(current),
                recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                grounds = t.s(R.string.assessment_grounds_pt_direct, label),
                tier = AssessmentTier.VERIFY, changeNeeded = false
            ),
            write = null
        )
        recommendOff -> PtRow(
            AssessmentItem(
                key = key, title = t.s(titleRes),
                currentValue = t.onOff(current),
                recommendedValue = t.off,
                grounds = t.s(offGroundsRes, label),
                tier = AssessmentTier.CALCULATED, changeNeeded = current
            ),
            write = false.takeIf { current }
        )
        else -> PtRow(
            AssessmentItem(
                key = key, title = t.s(titleRes),
                currentValue = t.onOff(current),
                recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                grounds = t.s(keepGroundsRes ?: R.string.assessment_grounds_pt_absent, label),
                tier = AssessmentTier.VERIFY, changeNeeded = false
            ),
            write = null
        )
    }

    /**
     * Display builder: afr and res_match. Each afr branch states its own
     * write; res_match is a preference row and is never written.
     */
    internal fun displayRows(
        f: AssessmentFacts,
        settings: PlayerSettings,
        t: RowText,
        plan: AssessmentApplyPlan
    ): Section {
        val display = f.device.display
        val mode = settings.frameRateMatchingMode
        // AFR + resolution: honest per display capability; VERIFY when the
        // display could not be inspected (no Activity, or API unsupported).
        if (!display.apiSupported) {
            return Section(
                listOf(
                    AssessmentItem(
                        key = "afr",
                        title = t.s(R.string.assessment_item_afr),
                        currentValue = afrModeLabel(t, mode),
                        recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                        grounds = t.s(R.string.assessment_grounds_display_unknown),
                        tier = AssessmentTier.VERIFY,
                        changeNeeded = false
                    ),
                    AssessmentItem(
                        key = "res_match",
                        title = t.s(R.string.assessment_item_res_match),
                        currentValue = t.onOff(settings.resolutionMatchingEnabled),
                        recommendedValue = t.s(R.string.assessment_value_leave_as_is),
                        grounds = t.s(R.string.assessment_grounds_display_unknown),
                        tier = AssessmentTier.VERIFY,
                        changeNeeded = false
                    )
                ),
                plan
            )
        }
        val afrWrite: FrameRateMatchingMode?
        val afr: AssessmentItem
        if (display.supportsFrameRateSwitching) {
            if (mode == FrameRateMatchingMode.OFF) {
                afrWrite = FrameRateMatchingMode.START_STOP
                afr = AssessmentItem(
                    key = "afr",
                    title = t.s(R.string.assessment_item_afr),
                    currentValue = t.off,
                    recommendedValue = t.s(R.string.assessment_value_afr_start_stop),
                    grounds = t.s(R.string.assessment_grounds_afr_on, display.ratesLabel),
                    tier = AssessmentTier.CALCULATED,
                    changeNeeded = true
                )
            } else {
                afrWrite = null
                afr = AssessmentItem(
                    key = "afr",
                    title = t.s(R.string.assessment_item_afr),
                    currentValue = afrModeLabel(t, mode),
                    recommendedValue = t.s(R.string.assessment_value_your_call),
                    grounds = t.s(R.string.assessment_grounds_afr_already),
                    tier = AssessmentTier.CALCULATED,
                    changeNeeded = false
                )
            }
        } else {
            afrWrite = FrameRateMatchingMode.OFF.takeIf { mode != it }
            afr = AssessmentItem(
                key = "afr",
                title = t.s(R.string.assessment_item_afr),
                currentValue = afrModeLabel(t, mode),
                recommendedValue = t.off,
                grounds = t.s(R.string.assessment_grounds_afr_single),
                tier = AssessmentTier.CALCULATED,
                changeNeeded = afrWrite != null
            )
        }
        // Resolution matching is a preference, not a derivable fact: it
        // decides WHICH scaler upscales sub-native content (the panel's or
        // this box's), and no API says which looks better. State the trade
        // and leave the choice; never write it.
        val resMatch = AssessmentItem(
            key = "res_match",
            title = t.s(R.string.assessment_item_res_match),
            currentValue = t.onOff(settings.resolutionMatchingEnabled),
            recommendedValue = if (display.supportsResolutionSwitching) {
                t.s(R.string.assessment_value_your_call)
            } else {
                t.s(R.string.assessment_value_leave_as_is)
            },
            grounds = if (display.supportsResolutionSwitching) {
                t.s(R.string.assessment_grounds_res_choice, display.resolutionsLabel)
            } else {
                t.s(R.string.assessment_grounds_res_inert)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = false
        )
        return Section(listOf(afr, resMatch), plan.copy(frameRateMatchingMode = afrWrite))
    }

    /**
     * DV + tunnelling builder. dv7 is CALCULATED when the display's HDR
     * types are readable, VERIFY otherwise (the policy still acts, but the
     * deciding fact is not queryable - say so instead of pretending), and
     * writes only when not VERIFY. dv5 and strip are permanent VERIFY
     * advisories and never machine-applied (nt6, see class doc); tunnelling
     * is not queryable at all.
     */
    internal fun dvRows(
        f: AssessmentFacts,
        settings: PlayerSettings,
        t: RowText,
        plan: AssessmentApplyPlan
    ): Section {
        val policy = f.device.policy
        val dvTier = if (policy.hdrCapsKnown) AssessmentTier.CALCULATED else AssessmentTier.VERIFY
        val dv7DecisionLabel = when (policy.decision) {
            DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7 -> t.s(R.string.assessment_dv7_native)
            DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81 -> t.s(R.string.assessment_dv7_convert)
            DolbyVisionBaseLayerPolicy.Decision.STRIP_TO_HDR10 -> t.s(R.string.assessment_dv7_strip)
            DolbyVisionBaseLayerPolicy.Decision.STRIP_BEST_EFFORT -> t.s(R.string.assessment_dv7_strip_besteffort)
            DolbyVisionBaseLayerPolicy.Decision.STRIP_AND_TONEMAP -> t.s(R.string.assessment_dv7_tonemap)
        }
        val dv7Change = settings.dv7HandlingMode != Dv7HandlingMode.AUTO
        val dv7 = AssessmentItem(
            key = "dv7_mode",
            title = t.s(R.string.assessment_item_dv7),
            currentValue = dv7ModeLabel(t, settings.dv7HandlingMode),
            recommendedValue = t.s(R.string.assessment_value_auto),
            grounds = if (policy.hdrCapsKnown) {
                t.s(R.string.assessment_grounds_dv7_auto, dv7DecisionLabel)
            } else {
                t.s(R.string.assessment_grounds_dv7_unknown)
            },
            tier = dvTier,
            changeNeeded = dv7Change
        )

        val dv5RecommendOn = !policy.displayDv && policy.bridgeReady && policy.codecSupportsDvheSt
        val dv5 = AssessmentItem(
            key = "dv5",
            title = t.s(R.string.assessment_item_dv5),
            currentValue = t.onOff(settings.dv5ToDv81Enabled),
            recommendedValue = t.onOff(dv5RecommendOn),
            grounds = when {
                policy.displayDv -> t.s(R.string.assessment_grounds_dv5_off_display)
                dv5RecommendOn -> t.s(R.string.assessment_grounds_dv5_on)
                else -> t.s(R.string.assessment_grounds_dv5_off_nopath)
            },
            tier = AssessmentTier.VERIFY,
            changeNeeded = settings.dv5ToDv81Enabled != dv5RecommendOn
        )

        val stripRecommendOn = policy.hdrCapsKnown && !policy.displayHdr10Plus
        val strip = AssessmentItem(
            key = "strip_hdr10plus",
            title = t.s(R.string.assessment_item_strip_hdr10plus),
            currentValue = t.onOff(settings.stripHdr10PlusSei),
            recommendedValue = t.onOff(stripRecommendOn),
            grounds = when {
                !policy.hdrCapsKnown -> t.s(R.string.assessment_grounds_dv7_unknown)
                stripRecommendOn -> t.s(R.string.assessment_grounds_strip_on)
                else -> t.s(R.string.assessment_grounds_strip_off)
            },
            tier = AssessmentTier.VERIFY,
            changeNeeded = settings.stripHdr10PlusSei != stripRecommendOn
        )

        // ── Tier 4: not queryable at all - verify yourself ──────────────────
        val tunneling = AssessmentItem(
            key = "tunneling",
            title = t.s(R.string.assessment_item_tunneling),
            currentValue = t.onOff(settings.tunnelingEnabled),
            recommendedValue = t.off,
            grounds = t.s(R.string.assessment_grounds_tunneling),
            tier = AssessmentTier.VERIFY,
            changeNeeded = settings.tunnelingEnabled
        )

        return Section(
            listOf(dv7, dv5, strip, tunneling),
            plan.copy(
                dv7HandlingMode = Dv7HandlingMode.AUTO.takeIf {
                    dv7Change && dvTier != AssessmentTier.VERIFY
                }
            )
        )
    }

    /** The http2 row's grounds, as pure data so the figure order is pin-able:
     *  both measured strings name the RECOMMENDED arm's number first. */
    internal sealed interface Http2Grounds {
        /** No variant ran: the multiplexing caveat. */
        data object Caveat : Http2Grounds

        data class Measured(
            val connections: Int,
            val chunkMb: Int,
            val recommendedMbps: Double,
            val otherMbps: Double
        ) : Http2Grounds
    }

    internal data class Http2RowDecision(
        val recommendOn: Boolean,
        val measured: Boolean,
        val grounds: Http2Grounds
    )

    /**
     * The F4 http2 row decision, extracted pure (review: the swapped grounds
     * figures and the OFF-when-h1-wins call are settings-write-adjacent and
     * must not drift silently). [variant] null means the A/B did not run or
     * the sweep ended in error — the row falls back to the caveat and the
     * HTTP/1.1 default (Spec 36/S6): only a measured h2 win recommends on.
     */
    internal fun http2RowDecision(variant: StreamSweepEngine.H2Variant?): Http2RowDecision {
        val recommendOn = variant?.h1Wins == false
        val grounds = when (variant) {
            null -> Http2Grounds.Caveat
            else -> Http2Grounds.Measured(
                connections = variant.connections,
                chunkMb = variant.chunkMb,
                recommendedMbps = if (recommendOn) variant.h2Mbps else variant.h1Mbps,
                otherMbps = if (recommendOn) variant.h1Mbps else variant.h2Mbps
            )
        }
        return Http2RowDecision(recommendOn, measured = variant != null, grounds = grounds)
    }

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank()) return emptyMap()
        val json = runCatching { JSONObject(headersJson) }.getOrNull() ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // Per-key tolerance: a single uncoercible value skips that header
            // instead of discarding every header (the old whole-map runCatching).
            val value = runCatching { json.getString(key) }.getOrNull() ?: continue
            map[key] = value
        }
        return map
    }

    /** "16 MB" / "512 KB" (the welcome card reuses it). */
    internal fun chunkLabel(t: RowText, chunkKb: Int): String =
        if (chunkKb >= 1024 && chunkKb % 1024 == 0) {
            t.s(R.string.assessment_unit_mb, chunkKb / 1024)
        } else {
            t.s(R.string.assessment_unit_kb, chunkKb)
        }

    private fun afrModeLabel(t: RowText, mode: FrameRateMatchingMode): String = when (mode) {
        FrameRateMatchingMode.OFF -> t.s(R.string.assessment_value_off)
        FrameRateMatchingMode.START -> t.s(R.string.assessment_value_afr_start)
        FrameRateMatchingMode.START_STOP -> t.s(R.string.assessment_value_afr_start_stop)
    }

    private fun dv7ModeLabel(t: RowText, mode: Dv7HandlingMode): String = when (mode) {
        Dv7HandlingMode.AUTO -> t.s(R.string.assessment_value_auto)
        else -> mode.name
    }

    private fun currentResolutionLabel(display: DisplayCapabilities.Snapshot): String? {
        val current = display.supportedModes.firstOrNull { it.modeId == display.currentModeId }
            ?: return null
        return "${current.physicalWidth}x${current.physicalHeight}"
    }

    private fun ratesAtCurrentResolution(display: DisplayCapabilities.Snapshot): String {
        // Fall back to the first mode (not "all resolutions") when the current
        // mode id is absent, matching DisplayCapabilities.deriveSupport so the
        // header and the AFR verdict describe the same resolution.
        val current = display.supportedModes.firstOrNull { it.modeId == display.currentModeId }
            ?: display.supportedModes.firstOrNull()
        return display.supportedModes
            .filter {
                current == null ||
                    (it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight)
            }
            // Dedupe at millihertz like deriveSupport: raw-float distinct() lets
            // 59.94f and 59.940002f survive as two rates and print twice.
            .map { Math.round(it.refreshRate * 1000f) }
            .distinct()
            .sorted()
            .joinToString("/") { milliHz ->
                val rate = milliHz / 1000.0
                if (rate == rate.toInt().toDouble()) {
                    rate.toInt().toString()
                } else {
                    // Locale.ROOT: keep a '.' decimal so trimEnd('.') works and
                    // comma-decimal locales don't emit "50," in the rate list.
                    "%.3f".format(java.util.Locale.ROOT, rate).trimEnd('0').trimEnd('.')
                }
            }
    }

    private fun resolutionsLabel(display: DisplayCapabilities.Snapshot): String =
        display.supportedModes
            .map { it.physicalWidth to it.physicalHeight }
            .distinct()
            .sortedByDescending { it.first.toLong() * it.second }
            .joinToString("/") { "${it.first}x${it.second}" }

    /** The mode [resolutionsLabel] lists first (largest area). */
    private fun largestMode(display: DisplayCapabilities.Snapshot) =
        display.supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
}
