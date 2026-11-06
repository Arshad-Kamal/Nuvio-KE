package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.core.player.MemoryUsageStatus

/**
 * Confidence tiers for Device Assessment rows. The tier states HOW a
 * recommendation was derived, so the UI can be honest about certainty:
 *
 *  MEASURED   - tested on this device against the real last-played stream
 *               (the sweep). Highest confidence.
 *  CALCULATED - derived from queryable device facts (RAM tier, display
 *               modes, HDR capability reads, decoder lists) plus the
 *               documented behaviour of the setting. No measurement ran.
 *  VERIFY     - a recommendation exists but the deciding fact is not
 *               queryable on this device (unknown HDR caps, uninspectable
 *               display, tunnelling quirks). Eyes-on verification advised.
 */
enum class AssessmentTier { MEASURED, CALCULATED, VERIFY }

enum class ProfileKind { FAST_START, BALANCED, STALL_RESISTANT }

data class AssessmentItem(
    /** Stable identifier, e.g. "parallel", "target_buffer". */
    val key: String,
    val title: String,
    /** Localised display of the current setting; null when not meaningful. */
    val currentValue: String?,
    val recommendedValue: String,
    /** One-line, honest "why" - names the fact or measurement behind it. */
    val grounds: String,
    val tier: AssessmentTier,
    /** True when the current setting differs from the recommendation. */
    val changeNeeded: Boolean,
    /** Safe/warning/danger colouring for memory-bearing rows, else null. */
    val memoryStatus: MemoryUsageStatus? = null
)

data class IntentProfileOption(
    val kind: ProfileKind,
    val title: String,
    val subtitle: String,
    val initialBufferMs: Int,
    val rebufferMs: Int,
    val minBufferMs: Int,
    /** Set when the byte-derived ceiling capped this profile's min buffer. */
    val minCappedFromMs: Int? = null
)

data class AssessmentHeaderFacts(
    val deviceRamLabel: String,
    val safeLimitMb: Int,
    val warningLimitMb: Int,
    /** "3840x2160 · 24/50/60 Hz" style summary, null when uninspectable. */
    val displaySummary: String?,
    /** Filename (preferred) or host of the last-played stream, null when none. */
    val streamLabel: String?,
    val streamBitrateMbps: Double?,
    val streamDvProfile: String?,
    val streamHdrType: String?
)

data class AssessmentResult(
    val timestampMs: Long,
    val header: AssessmentHeaderFacts,
    val sweepRan: Boolean,
    val sweepVerdictText: String?,
    val items: List<AssessmentItem>,
    val profiles: List<IntentProfileOption>,
    /** Stability-derived suggestion among the profiles; null = no signal. */
    val suggestedProfile: ProfileKind?,
    val stabilityCoV: Double?,
    val stabilityPassCount: Int,
    /** Machine-actionable side of the items: null field = never touched. */
    val applyPlan: AssessmentApplyPlan,
    val errorText: String?
)

/**
 * Only MEASURED/CALCULATED rows with changeNeeded populate fields; VERIFY
 * rows and no-change rows stay null by construction. The Internal Engine
 * row is deliberately advisory-only and never enters the plan.
 *
 * Second producer: ShieldPreset (core/assessment/ShieldPreset.kt) hand-fills
 * a plan for the reference device, including the three fields at the end.
 * The engine sets the back-buffer pair too (0 / off on the low-RAM tiers);
 * autoSwitchInternalPlayerOnError is preset-only (no device-derivable
 * recommendation — docs/project/device-assessment-analysis-2026-08-28.md F2).
 */
data class AssessmentApplyPlan(
    val nuvioPerformanceModeEnabled: Boolean? = null,
    val bufferEngineEnabled: Boolean? = null,
    val parallelNetworkEnabled: Boolean? = null,
    val bufferBudgetManaged: Boolean? = null,
    val allowLargeTargetBuffer: Boolean? = null,
    val targetBufferSizeMb: Int? = null,
    val maxBufferMs: Int? = null,
    val useParallelConnections: Boolean? = null,
    val parallelConnectionCount: Int? = null,
    val parallelChunkSizeKb: Int? = null,
    val enableHttp2: Boolean? = null,
    val frameRateMatchingMode: FrameRateMatchingMode? = null,
    val resolutionMatchingEnabled: Boolean? = null,
    val dv7HandlingMode: Dv7HandlingMode? = null,
    // No dv5ToDv81Enabled / stripHdr10PlusSei: advisory-only VERIFY rows (nt6).
    val forceOpticalPassthrough: Boolean? = null,
    val deniedCodecHandling: DeniedCodecHandling? = null,
    // Per-format passthrough switches, keyed on the platform's direct-playback
    // report. Only ever set to false (a chain-absent format), never to true.
    val allowAc3Passthrough: Boolean? = null,
    val allowEac3Passthrough: Boolean? = null,
    val allowTrueHdPassthrough: Boolean? = null,
    val allowDtsPassthrough: Boolean? = null,
    val allowDtsHdPassthrough: Boolean? = null,
    /** Pinned off whenever TrueHD passthrough ends up off (MAT rides TrueHD). */
    val matPassthroughEnabled: Boolean? = null,
    // Back-buffer pair: counted in the engine's memory accounting and
    // recommended 0/off on the ≤3 GB tiers (F2, landed); auto-switch is
    // still preset-only (player-resilience preference, no device-derivable
    // recommendation yet). Null = untouched.
    val backBufferDurationMs: Int? = null,
    val retainBackBufferFromKeyframe: Boolean? = null,
    val autoSwitchInternalPlayerOnError: Boolean? = null
) {
    val touchedCount: Int
        get() = listOfNotNull(
            nuvioPerformanceModeEnabled, bufferEngineEnabled, parallelNetworkEnabled,
            bufferBudgetManaged, allowLargeTargetBuffer, targetBufferSizeMb,
            maxBufferMs, useParallelConnections, parallelConnectionCount,
            parallelChunkSizeKb, enableHttp2,
            frameRateMatchingMode, resolutionMatchingEnabled, dv7HandlingMode,
            forceOpticalPassthrough,
            deniedCodecHandling, allowAc3Passthrough, allowEac3Passthrough,
            allowTrueHdPassthrough, allowDtsPassthrough, allowDtsHdPassthrough,
            matPassthroughEnabled, backBufferDurationMs, retainBackBufferFromKeyframe,
            autoSwitchInternalPlayerOnError
        ).size
}
