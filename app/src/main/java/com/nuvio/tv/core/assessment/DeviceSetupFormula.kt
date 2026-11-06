package com.nuvio.tv.core.assessment

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DeviceFacts
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings

/**
 * The first-launch config for a device with no stream test yet (Spec 33 D2): device facts
 * plus the current settings in, an absolute apply plan + buffer trio out. Pipeline:
 * [formula] -> [ShieldPreset.overlay] on a Shield -> [fitPlaybackConfig] (no film) -> plan.
 *
 * Every buffer / network / display field is written (absolute), because the applier turns
 * performance mode on first and that setter presets its own buffer and parallel values; a
 * "leave as is" field would keep the preset's value, not the user's. DV, audio and
 * resolution matching stay untouched (mockup a-summary lists only what the formula sets);
 * the Shield overlay adds resolution matching + auto-switch from [ShieldPreset.plan].
 */
@UnstableApi
internal object DeviceSetupFormula {

    data class Setup(val plan: AssessmentApplyPlan, val profile: IntentProfileOption)

    /**
     * No-stream defaults: performance mode where supported, the largest target that fits the
     * safe limit beside the current parallel pipe, the BALANCED trio, max buffer at the
     * engine's cap, back buffer off on the low-RAM tiers (else current), network and HTTP/2
     * as they are (the stream test decides those), AFR on when the display can switch rates.
     */
    fun formula(device: DeviceFacts, settings: PlayerSettings): PlaybackConfig {
        val buf = settings.bufferSettings
        val lowRam = DeviceAssessmentEngine.isUnder4GbRung(device.safeLimitMb)
        val base = PlaybackConfig(
            performanceMode = device.perfModeSupported,
            targetBufferMb = 0,
            minBufferMs = DeviceAssessmentEngine.BAL_MIN_MS,
            maxBufferMs = DeviceAssessmentEngine.MAX_BUFFER_CAP_S * 1000,
            bufferForPlaybackMs = DeviceAssessmentEngine.BAL_INITIAL_MS,
            bufferForPlaybackAfterRebufferMs = DeviceAssessmentEngine.BAL_REBUFFER_MS,
            backBufferMs = if (lowRam) 0 else buf.backBufferDurationMs,
            retainBackBufferFromKeyframe = !lowRam && buf.retainBackBufferFromKeyframe,
            parallel = settings.useParallelConnections,
            connections = settings.parallelConnectionCount,
            // Tier cap first, so the target is sized beside the chunk the gate keeps.
            chunkKb = MemoryBudget.tierCappedChunkKb(settings.parallelChunkSizeKb, device.tierMaxChunkMb),
            http2 = settings.enableHttp2,
            frameRateMatching = if (
                device.limits().afrSupported == true &&
                settings.frameRateMatchingMode == FrameRateMatchingMode.OFF
            ) FrameRateMatchingMode.START_STOP else settings.frameRateMatchingMode
        )
        return base.copy(
            targetBufferMb = MemoryBudget.maxSafeTargetMb(device.safeLimitMb, base.parallelOverheadMb)
                .coerceAtMost(PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB)
        )
    }

    /** Formula, Shield overlay, fit gate, then the apply plan and its trio. */
    fun setup(device: DeviceFacts, settings: PlayerSettings): Setup {
        val limits = device.limits()
        val shield = device.quirks.isShield
        val formula = formula(device, settings)
        val config = fitPlaybackConfig(
            if (shield) ShieldPreset.overlay(formula, limits) else formula,
            limits,
            filmMbps = null
        )
        val plan = toPlan(config).let {
            if (shield) {
                it.copy(
                    resolutionMatchingEnabled = ShieldPreset.plan.resolutionMatchingEnabled,
                    autoSwitchInternalPlayerOnError = ShieldPreset.plan.autoSwitchInternalPlayerOnError
                )
            } else it
        }
        val profile = IntentProfileOption(
            kind = if (shield) ShieldPreset.profile.kind else ProfileKind.BALANCED,
            title = "",
            subtitle = "",
            initialBufferMs = config.bufferForPlaybackMs,
            rebufferMs = config.bufferForPlaybackAfterRebufferMs,
            minBufferMs = config.minBufferMs
        )
        return Setup(plan, profile)
    }

    /**
     * Masters on, managed budget off (managed makes the runtime ignore the stored target),
     * large targets allowed (the fit gate, not the slider range, bounds the target).
     */
    private fun toPlan(c: PlaybackConfig) = AssessmentApplyPlan(
        nuvioPerformanceModeEnabled = c.performanceMode,
        bufferEngineEnabled = true,
        parallelNetworkEnabled = true,
        bufferBudgetManaged = false,
        allowLargeTargetBuffer = true,
        targetBufferSizeMb = c.targetBufferMb,
        maxBufferMs = c.maxBufferMs,
        useParallelConnections = c.parallel,
        parallelConnectionCount = c.connections,
        parallelChunkSizeKb = c.chunkKb,
        enableHttp2 = c.http2,
        frameRateMatchingMode = c.frameRateMatching,
        backBufferDurationMs = c.backBufferMs,
        retainBackBufferFromKeyframe = c.retainBackBufferFromKeyframe
    )
}
