package com.nuvio.tv.core.assessment

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.DeviceFacts
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine.RowText
import com.nuvio.tv.core.player.AudioCapabilityReport.DirectSupport
import com.nuvio.tv.core.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings

/**
 * The first-launch welcome (Spec 33 D8, sandbox u33-welcome-setup v3 stage a-summary): what the
 * first start of this install does, and the summary card's text. Pure, so tests pass any
 * device; DeviceSetupCoordinator.firstLaunch does the reads and writes.
 */
internal enum class WelcomeSetup {
    /** This box is already recorded (same fingerprint): no card. */
    NONE,

    /** Run the setup formula, then show the card. */
    APPLY,

    /** An install tuned before the setup existed (decision Q3): record KEPT, apply nothing, show the card. */
    KEEP
}

/**
 * Once per device fingerprint (decision Q4): a record for this box means done. No record and a
 * [tuned] install keeps its values; anything else (fresh install, or a record from another box
 * or RAM tier) runs the setup.
 */
internal fun welcomeSetupFor(record: DeviceSetupRecord?, fingerprint: String, tuned: Boolean): WelcomeSetup =
    when {
        record?.fingerprint == fingerprint -> WelcomeSetup.NONE
        record == null && tuned -> WelcomeSetup.KEEP
        else -> WelcomeSetup.APPLY
    }

/**
 * Decision Q3's "already tuned" signal, by value (D1 m5): an undo snapshot exists (an earlier
 * assessment or Shield preset apply), or a buffer / network value differs from its default.
 * Key presence says nothing: old builds' migrations wrote explicit defaults on every install.
 */
internal fun isAlreadyTuned(settings: PlayerSettings, hasUndoSnapshot: Boolean): Boolean {
    val d = DEFAULT_SETTINGS
    return hasUndoSnapshot ||
        settings.bufferSettings != d.bufferSettings ||
        settings.nuvioPerformanceModeEnabled != d.nuvioPerformanceModeEnabled ||
        settings.bufferEngineEnabled != d.bufferEngineEnabled ||
        settings.parallelNetworkEnabled != d.parallelNetworkEnabled ||
        settings.bufferBudgetManaged != d.bufferBudgetManaged ||
        settings.allowLargeTargetBuffer != d.allowLargeTargetBuffer ||
        settings.useParallelConnections != d.useParallelConnections ||
        settings.parallelConnectionCount != d.parallelConnectionCount ||
        settings.parallelChunkSizeKb != d.parallelChunkSizeKb ||
        settings.enableHttp2 != d.enableHttp2
}

private val DEFAULT_SETTINGS = PlayerSettings()

/** One card row: [applied] = a tick (set now), false = the circle arrows (decided at play time). */
internal data class WelcomeRow(val label: String, val value: String, val applied: Boolean = true)

/** The card: [deviceName], the [deviceLine] under it, the rows; [showNetworkNote] = the network-test note. */
internal data class WelcomeSummary(
    val deviceName: String,
    val deviceLine: String,
    val rows: List<WelcomeRow>,
    val showNetworkNote: Boolean
)

/**
 * The card's text from the device facts, [settings] as they stand after the setup (or as kept),
 * the Android [androidRelease] and the [audio] passthrough probe (null = unknown, left out).
 * Rows the formula leaves alone are not listed (decision Q3): frame-rate matching only when the
 * display can switch rates, resolution matching only on a Shield (its overlay sets it). A kept
 * install (KEEP) may run values the player ignores, so rows show only what plays: buffer memory
 * with the buffer master on and the budget unmanaged, the trio with the master on, buffer and
 * network rows on the Exo engine only, and the network-test promise only where the per-film
 * rule runs ([inAutomaticShape], D6).
 */
@OptIn(UnstableApi::class)
internal fun welcomeSummary(
    device: DeviceFacts,
    settings: PlayerSettings,
    androidRelease: String,
    audio: DirectSupport?,
    t: RowText
): WelcomeSummary {
    val buf = settings.bufferSettings
    val exo = DeviceAssessmentEngine.exoEngineSelected(settings)
    val ruleRuns = exo && inAutomaticShape(settings)
    val rows = buildList {
        if (exo && settings.bufferEngineEnabled && !settings.bufferBudgetManaged) add(
            WelcomeRow(
                t.s(R.string.welcome_row_buffer_memory),
                t.s(
                    R.string.welcome_value_buffer_memory,
                    buf.targetBufferSizeMb,
                    ShieldPreset.secondsOfReference4k(buf.targetBufferSizeMb)
                )
            )
        )
        if (exo && settings.bufferEngineEnabled) add(
            WelcomeRow(
                t.s(R.string.welcome_row_buffering),
                t.s(
                    R.string.welcome_value_buffering,
                    secondsLabel(buf.bufferForPlaybackMs),
                    secondsLabel(buf.bufferForPlaybackAfterRebufferMs),
                    secondsLabel(buf.minBufferMs)
                )
            )
        )
        if (device.limits().afrSupported == true) {
            add(WelcomeRow(t.s(R.string.welcome_row_frame_rate), frameRateLabel(settings.frameRateMatchingMode, t)))
        }
        if (device.quirks.isShield) {
            add(WelcomeRow(t.s(R.string.welcome_row_resolution), t.onOff(settings.resolutionMatchingEnabled)))
        }
        if (exo) add(WelcomeRow(t.s(R.string.welcome_row_network), networkLabel(settings, ruleRuns, t)))
        add(
            WelcomeRow(
                t.s(R.string.welcome_row_dv_audio),
                t.s(R.string.welcome_value_dv_audio),
                applied = false
            )
        )
    }
    return WelcomeSummary(
        deviceName = deviceName(device.quirks.manufacturer, device.quirks.model),
        deviceLine = deviceLine(device, androidRelease, audio, t),
        rows = rows,
        showNetworkNote = ruleRuns
    )
}

/** "NVIDIA SHIELD Android TV": the manufacturer first unless the model already starts with it. */
internal fun deviceName(manufacturer: String?, model: String?): String {
    val maker = manufacturer.orEmpty().trim().replaceFirstChar { it.uppercaseChar() }
    val name = model.orEmpty().trim()
    return when {
        name.isEmpty() -> maker
        maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name"
    }
}

/** "2 GB memory · Android 11 · 4K HDR TV with Dolby Vision · receiver with TrueHD and DTS-HD"; unknown parts left out. */
@OptIn(UnstableApi::class)
private fun deviceLine(device: DeviceFacts, androidRelease: String, audio: DirectSupport?, t: RowText): String =
    listOfNotNull(
        device.ramLabel.takeIf { it.isNotBlank() && it != NuvioExoPlayerPerformanceHelper.UNKNOWN_RAM_LABEL }
            ?.let { t.s(R.string.welcome_device_memory, it) },
        androidRelease.takeIf { it.isNotBlank() }?.let { t.s(R.string.welcome_device_android, it) },
        tvLabel(device, t),
        audio?.let { audioLabel(it, t) }
    ).joinToString(" \u00b7 ")

/** "4K HDR TV with Dolby Vision" from the largest display mode and the HDR capabilities. */
@OptIn(UnstableApi::class)
private fun tvLabel(device: DeviceFacts, t: RowText): String? {
    val width = device.display.largestWidth
    val policy = device.policy
    val hdr = policy.displayHdr10 || policy.displayHlg || policy.displayHdr10Plus || policy.displayDv
    val words = listOfNotNull(
        when {
            width == null -> null
            width >= 3840 -> "4K"
            width >= 1920 -> "1080p"
            else -> null
        },
        if (hdr) "HDR" else null
    )
    if (words.isEmpty()) return null
    val kind = words.joinToString(" ")
    return if (policy.displayDv) t.s(R.string.welcome_device_tv_dv, kind) else t.s(R.string.welcome_device_tv, kind)
}

/** Lossless formats name a receiver; otherwise the compressed formats the chain takes. */
private fun audioLabel(audio: DirectSupport, t: RowText): String? {
    val lossless = listOfNotNull("TrueHD".takeIf { audio.trueHd }, "DTS-HD".takeIf { audio.dtsHd })
    if (lossless.isNotEmpty()) return t.s(R.string.welcome_device_receiver, joinAnd(lossless, t))
    val lossy = listOfNotNull(
        when {
            audio.eac3 -> "Dolby Digital Plus"
            audio.ac3 -> "Dolby Digital"
            else -> null
        },
        "DTS".takeIf { audio.dts }
    )
    return if (lossy.isEmpty()) null else t.s(R.string.welcome_device_passthrough, joinAnd(lossy, t))
}

private fun joinAnd(parts: List<String>, t: RowText): String =
    if (parts.size == 1) parts[0] else t.s(R.string.welcome_join_and, parts[0], parts[1])

private fun frameRateLabel(mode: FrameRateMatchingMode, t: RowText): String = when (mode) {
    FrameRateMatchingMode.OFF -> t.off
    FrameRateMatchingMode.START -> t.s(R.string.welcome_value_frame_rate_start)
    FrameRateMatchingMode.START_STOP -> t.s(R.string.welcome_value_frame_rate_start_stop)
}

/**
 * "4 × 16 MB, HTTP/2 on · set for each film after the network test" ([ruleRuns]; else the pipe
 * alone); one connection when parallel is off.
 */
@OptIn(UnstableApi::class)
private fun networkLabel(settings: PlayerSettings, ruleRuns: Boolean, t: RowText): String {
    val connections = connectionsInUse(settings)
    val http2 = t.s(if (settings.enableHttp2) R.string.welcome_http2_on else R.string.welcome_http2_off)
    val pipe = if (connections > 1) {
        t.s(
            R.string.welcome_value_network_parallel,
            connections,
            DeviceAssessmentEngine.chunkLabel(t, settings.parallelChunkSizeKb),
            http2
        )
    } else {
        t.s(R.string.welcome_value_network_single, http2)
    }
    return if (ruleRuns) t.s(R.string.welcome_value_network, pipe) else pipe
}

/** 5000 -> "5", 1500 -> "1.5". */
internal fun secondsLabel(ms: Int): String =
    if (ms % 1000 == 0) (ms / 1000).toString() else "%.1f".format(java.util.Locale.ROOT, ms / 1000.0)
