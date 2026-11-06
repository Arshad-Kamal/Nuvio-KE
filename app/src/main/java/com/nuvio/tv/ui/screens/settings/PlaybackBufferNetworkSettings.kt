@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.core.player.MemoryUsageStatus
import com.nuvio.tv.core.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Buffer & network chip (ExoPlayer / Auto only): the This TV group first (Spec 33 D5,
 * [thisTvItems]), then (no mockup stage, kit rows per Q3) Native memory,
 * Custom buffers (its steppers and notes while on), Reset buffer values, Custom network (HTTP/2,
 * Parallel connections and their steppers while on), Reset network values, then the estimated
 * memory use. Blocks behind a master switch hide while it is off (as built); Target buffer size
 * and Allow larger dim under the managed budget.
 */
internal fun LazyListScope.bufferNetworkItems(
    settings: PlayerSettings,
    onSetNuvioPerformanceModeEnabled: (Boolean) -> Unit,
    onSetBufferEngineEnabled: (Boolean) -> Unit,
    onSetParallelNetworkEnabled: (Boolean) -> Unit,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetAllowLargeTargetBuffer: (Boolean) -> Unit,
    onSetBufferBudgetManaged: (Boolean) -> Unit,
    onResetBufferToDefaults: () -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeKb: (Int) -> Unit,
    onSetEnableHttp2: (Boolean) -> Unit,
    onResetNetworkToDefaults: () -> Unit,
    thisTv: ThisTvState,
    thisTvActions: ThisTvActions,
    entryFocus: FocusRequester?
) {
    thisTvItems(settings = settings, thisTv = thisTv, actions = thisTvActions, entryFocus = entryFocus)
    // The native allocator needs Android 8.0.
    val performanceModeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    item(key = "bn_performance_mode") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_net_nuvio_performance_mode),
            subtitle = stringResource(
                if (performanceModeSupported) R.string.playback_net_nuvio_performance_mode_sub
                else R.string.playback_net_nuvio_performance_mode_not_supported
            ),
            checked = performanceModeSupported && settings.nuvioPerformanceModeEnabled,
            onCheckedChange = onSetNuvioPerformanceModeEnabled,
            enabled = performanceModeSupported
        )
    }
    if (performanceModeSupported && settings.nuvioPerformanceModeEnabled) {
        item(key = "bn_device_memory") {
            val context = LocalContext.current
            SettingsNote(
                text = stringResource(
                    R.string.playback_net_device_memory_info,
                    NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel(context),
                    NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
                )
            )
        }
    }
    item(key = "bn_custom_buffers") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_buffer_custom),
            subtitle = stringResource(R.string.playback_buffer_custom_sub),
            checked = settings.bufferEngineEnabled,
            onCheckedChange = onSetBufferEngineEnabled
        )
    }
    if (settings.bufferEngineEnabled) {
        customBufferItems(
            settings = settings,
            onSetBufferMinBufferMs = onSetBufferMinBufferMs,
            onSetBufferMaxBufferMs = onSetBufferMaxBufferMs,
            onSetBufferForPlaybackMs = onSetBufferForPlaybackMs,
            onSetBufferForPlaybackAfterRebufferMs = onSetBufferForPlaybackAfterRebufferMs,
            onSetBufferTargetSizeMb = onSetBufferTargetSizeMb,
            onSetBufferBackBufferDurationMs = onSetBufferBackBufferDurationMs,
            onSetAllowLargeTargetBuffer = onSetAllowLargeTargetBuffer,
            onSetBufferBudgetManaged = onSetBufferBudgetManaged
        )
    }
    if (settings.bufferEngineEnabled || settings.nuvioPerformanceModeEnabled) {
        item(key = "bn_reset_buffer") {
            SettingsValueRow(
                title = stringResource(R.string.playback_reset_buffer_values),
                value = null,
                onClick = onResetBufferToDefaults,
                trailingIcon = null
            )
        }
    }
    item(key = "bn_custom_network") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_net_custom),
            subtitle = stringResource(R.string.playback_net_custom_sub),
            checked = settings.parallelNetworkEnabled,
            onCheckedChange = onSetParallelNetworkEnabled
        )
    }
    if (settings.parallelNetworkEnabled) {
        networkItems(
            settings = settings,
            onSetEnableHttp2 = onSetEnableHttp2,
            onSetUseParallelConnections = onSetUseParallelConnections,
            onSetParallelConnectionCount = onSetParallelConnectionCount,
            onSetParallelChunkSizeKb = onSetParallelChunkSizeKb
        )
        item(key = "bn_reset_network") {
            SettingsValueRow(
                title = stringResource(R.string.playback_reset_network_values),
                value = null,
                onClick = onResetNetworkToDefaults,
                trailingIcon = null
            )
        }
    }
    if (settings.bufferEngineEnabled || settings.parallelNetworkEnabled || settings.nuvioPerformanceModeEnabled) {
        item(key = "bn_memory_estimate") {
            MemoryEstimateNote(settings)
        }
    }
}

/** The Custom buffers block: durations, managed budget, target size and its notes, Allow larger. */
private fun LazyListScope.customBufferItems(
    settings: PlayerSettings,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetAllowLargeTargetBuffer: (Boolean) -> Unit,
    onSetBufferBudgetManaged: (Boolean) -> Unit
) {
    val buffer = settings.bufferSettings
    // The native allocator keeps far more media off-heap, so its durations reach 20 minutes.
    val maxDuration = if (settings.nuvioPerformanceModeEnabled) 1200 else 120
    val durationStep = if (settings.nuvioPerformanceModeEnabled) 10 else 5
    val minSeconds = buffer.minBufferMs / 1000
    val maxSeconds = buffer.maxBufferMs / 1000
    item(key = "bn_buffer_section") {
        SettingsSectionLabel(text = stringResource(R.string.playback_buffer_header))
    }
    item(key = "bn_buffer_warning") {
        SettingsNote(text = stringResource(R.string.playback_buffer_warning))
    }
    secondsStepperItem(
        key = "bn_min_buffer",
        title = R.string.playback_buffer_min,
        subtitle = R.string.playback_buffer_min_sub,
        seconds = minSeconds,
        min = 5,
        max = maxDuration,
        step = durationStep,
        onSetMs = onSetBufferMinBufferMs
    )
    item(key = "bn_max_buffer") {
        SettingsStepperRow(
            title = stringResource(R.string.playback_buffer_max),
            subtitle = stringResource(R.string.playback_buffer_max_sub),
            value = maxSeconds,
            min = 5,
            max = maxDuration,
            step = durationStep,
            valueText = if (maxSeconds == minSeconds) {
                stringResource(R.string.playback_buffer_value_same_as_min, maxSeconds)
            } else {
                "${maxSeconds}s"
            },
            // Never below the minimum duration.
            onValueChange = { onSetBufferMaxBufferMs(maxOf(it, minSeconds) * 1000) }
        )
    }
    secondsStepperItem(
        key = "bn_initial_buffer",
        title = R.string.playback_buffer_initial,
        subtitle = R.string.playback_buffer_initial_sub,
        seconds = buffer.bufferForPlaybackMs / 1000,
        min = BufferSettings.MIN_BUFFER_FOR_PLAYBACK_MS / 1000,
        max = BufferSettings.MAX_BUFFER_FOR_PLAYBACK_MS / 1000,
        step = 1,
        onSetMs = onSetBufferForPlaybackMs
    )
    secondsStepperItem(
        key = "bn_rebuffer",
        title = R.string.playback_buffer_after_rebuffer,
        subtitle = R.string.playback_buffer_after_rebuffer_sub,
        seconds = buffer.bufferForPlaybackAfterRebufferMs / 1000,
        min = BufferSettings.MIN_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 1000,
        max = BufferSettings.MAX_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 1000,
        step = 1,
        onSetMs = onSetBufferForPlaybackAfterRebufferMs
    )
    secondsStepperItem(
        key = "bn_back_buffer",
        title = R.string.playback_buffer_back,
        subtitle = R.string.playback_buffer_back_sub,
        seconds = buffer.backBufferDurationMs / 1000,
        min = 0,
        max = 120,
        step = 5,
        onSetMs = onSetBufferBackBufferDurationMs
    )
    // The back-buffer byte reserve Media3 holds on top of the target buffer:
    // targetBuffer * backBufferMs / maxBufferMs.
    if (buffer.backBufferDurationMs > 0 && buffer.maxBufferMs > 0) {
        item(key = "bn_back_buffer_reserve") {
            val reserveMb = (storedTargetMb(settings).toLong() * buffer.backBufferDurationMs / buffer.maxBufferMs).toInt()
            SettingsNote(text = stringResource(R.string.playback_buffer_back_reserve, reserveMb))
        }
    }
    item(key = "bn_managed") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_buffer_managed),
            // Names the figure the runtime buffers to when managed (managedLimitMb).
            subtitle = stringResource(R.string.playback_buffer_managed_sub, managedLimitMb(settings)),
            checked = settings.bufferBudgetManaged,
            onCheckedChange = onSetBufferBudgetManaged
        )
    }
    targetBufferItems(settings, onSetBufferTargetSizeMb)
    item(key = "bn_allow_large") {
        val reason = playbackRowDimReason(PlaybackRow.TARGET_BUFFER, settings)
        SettingsSwitchRow(
            title = stringResource(R.string.playback_buffer_allow_large),
            subtitle = dimmedSubtitle(reason, stringResource(R.string.playback_buffer_allow_large_sub)),
            checked = settings.allowLargeTargetBuffer && reason == null,
            onCheckedChange = onSetAllowLargeTargetBuffer,
            enabled = reason == null
        )
    }
}

/** A seconds stepper over a stored millisecond value. */
private fun LazyListScope.secondsStepperItem(
    key: String,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    seconds: Int,
    min: Int,
    max: Int,
    step: Int,
    onSetMs: (Int) -> Unit
) {
    item(key = key) {
        SettingsStepperRow(
            title = stringResource(title),
            subtitle = stringResource(subtitle),
            value = seconds,
            min = min,
            max = max,
            step = step,
            valueText = "${seconds}s",
            onValueChange = { onSetMs(it * 1000) }
        )
    }
}

/**
 * The cap the runtime buffers to under the managed budget: the RAM-tier native safe limit with
 * the native allocator (off-heap), else the heap budget (BitrateAwareLoadControl's budgetBytes).
 */
@Composable
private fun managedLimitMb(settings: PlayerSettings): Int =
    if (settings.nuvioPerformanceModeEnabled) {
        NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(LocalContext.current)
    } else {
        MemoryBudget.budgetMb
    }

/** The stored target buffer size as the runtime applies it. */
private fun storedTargetMb(settings: PlayerSettings): Int =
    MemoryBudget.effectiveBufferMb(settings.bufferSettings.targetBufferSizeMb)

/** The parallel chunk size in whole MB, rounded up (the memory maths' unit). */
private fun chunkSizeMb(settings: PlayerSettings): Int = Math.ceil(settings.parallelChunkSizeKb / 1024.0).toInt()

/** Past the safe limit, the heap path warns up to a quarter above the budget before danger. */
private fun heapWarningLimitMb(): Int = (MemoryBudget.budgetMb * 1.25f).toInt()

/**
 * Target buffer size and its notes. Under the managed budget the row dims and shows the figure
 * the runtime enforces (coerced into the stepper's range, which the note quotes too, so text
 * and control agree); otherwise a value past the device's safe limit gets a warning.
 */
private fun LazyListScope.targetBufferItems(settings: PlayerSettings, onSetBufferTargetSizeMb: (Int) -> Unit) {
    item(key = "bn_target_size") {
        val context = LocalContext.current
        val performanceMode = settings.nuvioPerformanceModeEnabled
        val parallelOverheadMb = if (settings.parallelNetworkEnabled && settings.useParallelConnections) {
            MemoryBudget.parallelOverheadMb(settings.parallelConnectionCount, chunkSizeMb(settings))
        } else {
            0
        }
        val safeMaxMb = if (performanceMode) {
            NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
        } else {
            MemoryBudget.maxBufferMb(parallelOverheadMb)
        }
        val warningMaxMb = if (performanceMode) {
            NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
        } else {
            MemoryBudget.maxSafeTargetMb(heapWarningLimitMb(), parallelOverheadMb)
                .coerceAtMost(MemoryBudget.MAX_BUFFER_MB)
        }
        val maxSizeMb = if (settings.allowLargeTargetBuffer) PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB else warningMaxMb
        val minSizeMb = ((MemoryBudget.defaultBufferSizeMb / 2) / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
            .coerceIn(MemoryBudget.MIN_BUFFER_MB, maxSizeMb)
        val storedMb = storedTargetMb(settings)
        val reason = playbackRowDimReason(PlaybackRow.TARGET_BUFFER, settings)
        val managedDisplayMb = managedLimitMb(settings).coerceIn(minSizeMb, maxSizeMb)
        val sizeMb = if (reason != null) managedDisplayMb else storedMb.coerceIn(minSizeMb, maxSizeMb)
        SettingsStepperRow(
            title = stringResource(R.string.playback_buffer_target),
            subtitle = dimmedSubtitle(reason, stringResource(R.string.playback_buffer_target_sub)),
            value = sizeMb,
            min = minSizeMb,
            max = maxSizeMb,
            step = MemoryBudget.BUFFER_STEP_MB,
            valueText = stringResource(R.string.assessment_unit_mb, sizeMb),
            onValueChange = onSetBufferTargetSizeMb,
            enabled = reason == null
        )
        when {
            reason != null -> SettingsNote(
                text = stringResource(R.string.playback_buffer_target_managed_hint, managedDisplayMb, storedMb)
            )
            sizeMb > warningMaxMb -> SettingsNote(
                text = stringResource(R.string.playback_buffer_target_danger_warning, warningMaxMb),
                color = NuvioPrimitives.errorOnImage
            )
            sizeMb > safeMaxMb -> SettingsNote(
                text = stringResource(R.string.playback_buffer_target_warning, safeMaxMb),
                color = NuvioPrimitives.warning
            )
        }
    }
}

/** The Custom network block: HTTP/2, Parallel connections, then its connection count and chunk size. */
private fun LazyListScope.networkItems(
    settings: PlayerSettings,
    onSetEnableHttp2: (Boolean) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeKb: (Int) -> Unit
) {
    item(key = "bn_http2") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_net_http2),
            subtitle = stringResource(R.string.playback_net_http2_sub),
            checked = settings.enableHttp2,
            onCheckedChange = onSetEnableHttp2
        )
    }
    item(key = "bn_parallel") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_net_parallel),
            subtitle = stringResource(R.string.playback_net_parallel_sub),
            checked = settings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections
        )
    }
    if (!settings.useParallelConnections) return
    item(key = "bn_connection_count") {
        SettingsStepperRow(
            title = stringResource(R.string.playback_net_connection_count),
            subtitle = stringResource(R.string.playback_net_connection_count_sub),
            value = settings.parallelConnectionCount,
            min = MemoryBudget.MIN_CONNECTIONS,
            max = MemoryBudget.MAX_CONNECTIONS,
            step = 1,
            valueText = settings.parallelConnectionCount.toString(),
            onValueChange = onSetParallelConnectionCount
        )
    }
    item(key = "bn_chunk_size") {
        val maxChunkMb = if (settings.nuvioPerformanceModeEnabled) {
            MemoryBudget.tierMaxChunkMb
        } else {
            MemoryBudget.maxChunkMb(storedTargetMb(settings), settings.parallelConnectionCount)
        }
        val sizesKb = PlayerSettings.PARALLEL_CHUNK_SIZES_KB.filter { it <= maxChunkMb * 1024 }
        val currentKb = settings.parallelChunkSizeKb
        if (sizesKb.isEmpty()) return@item
        // A stored size off today's list (above the memory cap, or a retired size) shows as
        // stored and steps to the nearest listed size (stepValue).
        SettingsStepperRow(
            title = stringResource(R.string.playback_net_chunk_size),
            subtitle = stringResource(R.string.playback_net_chunk_size_sub),
            values = sizesKb,
            selected = currentKb,
            valueText = chunkSizeLabel(currentKb),
            onValueChange = onSetParallelChunkSizeKb
        )
    }
}

@Composable
private fun chunkSizeLabel(kb: Int): String =
    if (kb < 1024) stringResource(R.string.assessment_unit_kb, kb) else stringResource(R.string.assessment_unit_mb, kb / 1024)

/**
 * The estimated memory use of the current buffer and network choices against the device's limit
 * (green / amber / red), the parallel overhead on top of the buffer: with Native memory, its safe
 * limit unless Custom buffers sets an unmanaged target; Custom buffers alone, the heap budget when
 * managed, else the target; neither, Media3's default size.
 */
@Composable
private fun MemoryEstimateNote(settings: PlayerSettings) {
    val context = LocalContext.current
    val performanceMode = settings.nuvioPerformanceModeEnabled
    val nativeSafeMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
    val managedMb = managedLimitMb(settings)
    val bufferMb = when {
        performanceMode || settings.bufferEngineEnabled ->
            if (settings.bufferEngineEnabled && !settings.bufferBudgetManaged) storedTargetMb(settings) else managedMb
        else -> MemoryBudget.defaultBufferSizeMb
    }
    val totalMb = MemoryBudget.displayTotalUsageMb(
        bufferMb,
        settings.parallelConnectionCount,
        chunkSizeMb(settings),
        settings.useParallelConnections && settings.parallelNetworkEnabled,
        safeNativeLimitMb = nativeSafeMb,
        deepPathActive = performanceMode
    )
    val warningLimitMb = if (performanceMode) {
        NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
    } else {
        heapWarningLimitMb()
    }
    val color: Color = when (MemoryBudget.getUsageStatus(totalMb, managedMb, warningLimitMb)) {
        MemoryUsageStatus.DANGER -> NuvioPrimitives.errorOnImage
        MemoryUsageStatus.WARNING -> NuvioPrimitives.warning
        MemoryUsageStatus.SAFE -> NuvioTheme.marquee.readyText
    }
    SettingsNote(text = stringResource(R.string.playback_estimated_memory_usage, totalMb, warningLimitMb), color = color)
}
