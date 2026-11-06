package com.nuvio.tv.core.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Debounce window so flapping add/remove events coalesce into one route decision. */
private const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 700L

internal enum class BluetoothRoutePlaybackAction {
    NONE,
    UPDATE_SINK_IN_PLACE,
    UPDATE_MPV_IN_PLACE,
}

/**
 * Bluetooth connect/disconnect must never rebuild the player: that restarts video from a
 * seek point and shows the loading overlay. Update PCM/passthrough (Exo) or stereo/delay
 * (MPV) on the live engine instead.
 */
internal fun decideBluetoothRoutePlaybackAction(
    wasBluetooth: Boolean,
    isBluetooth: Boolean,
    usingMpv: Boolean,
    oldRouteKey: String? = null,
    newRouteKey: String? = null
): BluetoothRoutePlaybackAction {
    val bluetoothStateChanged = wasBluetooth != isBluetooth
    val bluetoothDeviceChanged = isBluetooth &&
        !oldRouteKey.isNullOrBlank() &&
        !newRouteKey.isNullOrBlank() &&
        oldRouteKey != newRouteKey
    if (!bluetoothStateChanged && !bluetoothDeviceChanged) {
        return BluetoothRoutePlaybackAction.NONE
    }
    return if (usingMpv) {
        BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE
    } else {
        BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE
    }
}

internal object MpvBluetoothAudioPolicy {
    const val STEREO_CHANNELS = "stereo"
    const val AUTO_CHANNELS = "auto"

    fun audioChannels(isBluetooth: Boolean): String {
        return if (isBluetooth) STEREO_CHANNELS else AUTO_CHANNELS
    }

    fun shouldClearAudioSpdif(isBluetooth: Boolean): Boolean = isBluetooth
}

internal fun audioDelayMsToSeconds(delayMs: Int): Double {
    return delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS) / 1000.0
}

internal suspend fun PlayerRuntimeController.applyStoredAudioDelayForCurrentRouteIfEnabled() {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: return
    currentAudioOutputRoute = route

    val storedDelayMs = audioDelayRouteDataStore.loadDelayMs(route.key) ?: 0
    applyAudioDelay(storedDelayMs, persistForCurrentRoute = false)
    Log.d(
        PlayerRuntimeController.TAG,
        "Applied audio delay ${storedDelayMs}ms for route=${route.key}"
    )
}

internal fun PlayerRuntimeController.persistAudioDelayForCurrentRoute(delayMs: Int) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: currentAudioOutputRoute ?: return
    currentAudioOutputRoute = route
    scope.launch {
        audioDelayRouteDataStore.saveDelayMs(route.key, delayMs)
    }
}

internal fun PlayerRuntimeController.registerAudioDelayRouteCallback() {
    if (audioOutputRouteCallback != null) return

    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("removed", removedDevices)
        }
    }

    runCatching {
        // Seed before registering: registration replays every connected device as "added".
        knownAudioDeviceIds = audioManager.getDevices(AudioManager.GET_DEVICES_ALL).mapTo(mutableSetOf()) { it.id }
        audioManager.registerAudioDeviceCallback(callback, null)
        audioOutputRouteCallback = callback
        Log.d(PlayerRuntimeController.TAG, "Registered audio output route callback")
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to register audio route callback", it)
    }
}

internal fun PlayerRuntimeController.unregisterAudioDelayRouteCallback() {
    val callback = audioOutputRouteCallback ?: return
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        audioManager.unregisterAudioDeviceCallback(callback)
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to unregister audio route callback", it)
    }
    audioOutputRouteCallback = null
}

private fun PlayerRuntimeController.onAudioOutputRouteMaybeChanged(
    reason: String,
    devices: Array<out AudioDeviceInfo>
) {
    // Before the player check: F3 needs the change time even mid-rebuild (no player yet).
    val eventIds = devices.mapTo(mutableSetOf()) { it.id }
    val added = reason == "added"
    if (isAudioDeviceSetChange(knownAudioDeviceIds, eventIds, added)) {
        lastAudioDeviceChangeAtMs = android.os.SystemClock.elapsedRealtime()
        // T14: a real add (never the registration replay) may be the receiver coming back.
        if (added) passthroughReturnCheckPending = true
    }
    knownAudioDeviceIds = if (added) knownAudioDeviceIds + eventIds else knownAudioDeviceIds - eventIds

    if (_exoPlayer == null && !isUsingMpvEngine()) return

    Log.d(
        PlayerRuntimeController.TAG,
        "Audio device $reason (count=${devices.size}); scheduling route re-probe"
    )

    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = scope.launch {
        delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)

        val oldRoute = currentAudioOutputRoute
        val newRoute = AudioOutputRouteDetector.detect(context)
        if (newRoute != null) {
            currentAudioOutputRoute = newRoute
        }

        if (rememberAudioDelayPerDeviceEnabled) {
            applyStoredAudioDelayForCurrentRouteIfEnabled()
        }

        val wasBluetooth = oldRoute?.isBluetooth == true
        val isBluetooth = (newRoute ?: currentAudioOutputRoute)?.isBluetooth == true
        when (
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = wasBluetooth,
                isBluetooth = isBluetooth,
                usingMpv = isUsingMpvEngine(),
                oldRouteKey = oldRoute?.key,
                newRouteKey = (newRoute ?: currentAudioOutputRoute)?.key
            )
        ) {
            BluetoothRoutePlaybackAction.NONE -> {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Audio route after device $reason: bluetooth=$isBluetooth (was=$wasBluetooth); player stays running"
                )
                // A flag, not this event's reason: a later event inside the debounce
                // window cancels this job, and the add must still be checked.
                if (passthroughReturnCheckPending) {
                    passthroughReturnCheckPending = false
                    if (!isBluetooth) schedulePassthroughReturnCheck()
                }
            }
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE -> {
                if (_exoPlayer == null) return@launch
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating PCM policy in place (player stays running)"
                )
                applyBluetoothAudioRouteInPlace(isBluetooth)
            }
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE -> {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating MPV stereo/delay in place (player stays running)"
                )
                applyMpvBluetoothAudioRouteInPlace(isBluetooth)
            }
        }
    }
}

/** Upstream `PassthroughOutputReturn.SECOND_CHECK_DELAY_MS`: a receiver reports formats a moment after the device appears. */
private const val PASSTHROUGH_RETURN_SECOND_CHECK_MS = 3_000L
private const val MAX_PASSTHROUGH_RETURN_REBUILDS_PER_STREAM = 3

/**
 * T14 (upstream-sync Register A2 P6): rebuild so the playing track goes out as bitstream again
 * when the player was built while the receiver was away. TV sinks pin their capabilities at
 * build, so without this a title started during an AVR wake decodes to PCM to the end.
 */
internal fun shouldRebuildForReturnedPassthrough(
    isBitstreamTrack: Boolean,
    pinnedPasses: Boolean?,
    livePasses: Boolean,
    sinkBitstreaming: Boolean,
    policyDenies: Boolean,
    pcmByChoice: Boolean,
    rebuildsSoFar: Int
): Boolean = isBitstreamTrack &&
    pinnedPasses == false &&
    livePasses &&
    !sinkBitstreaming &&
    !policyDenies &&
    !pcmByChoice &&
    rebuildsSoFar < MAX_PASSTHROUGH_RETURN_REBUILDS_PER_STREAM

/**
 * Checks now and once more after the receiver had time to report its formats. Called on a real
 * device add, on each build's first frame, and on return from background (review 200420 #1).
 */
internal fun PlayerRuntimeController.schedulePassthroughReturnCheck() {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    passthroughReturnCheckJob?.cancel()
    passthroughReturnCheckJob = scope.launch {
        if (rebuildForReturnedPassthroughIfNeeded("device added")) return@launch
        delay(PASSTHROUGH_RETURN_SECOND_CHECK_MS)
        rebuildForReturnedPassthroughIfNeeded("device added, second check")
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun PlayerRuntimeController.rebuildForReturnedPassthroughIfNeeded(reason: String): Boolean {
    if (isReleasingPlayer || isInBackground || isUsingMpvEngine()) return false
    val player = _exoPlayer ?: return false
    val sink = playbackSpeedAwareAudioSink ?: return false
    val format = player.audioFormat ?: return false
    if (AudioOutputRouteDetector.isBluetoothMediaOutput(context)) return false
    val url = currentStreamUrl
    val rebuildsSoFar = if (passthroughReturnRebuildUrl == url) passthroughReturnRebuilds else 0
    val pinned = sink.pinnedCapabilities
    val rebuild = shouldRebuildForReturnedPassthrough(
        isBitstreamTrack = AudioTrackRejectionLog.labelForMime(format.sampleMimeType) != null,
        pinnedPasses = pinned?.let { caps ->
            runCatching { caps.isPassthroughPlaybackSupported(format, movieAudioAttributes) }.getOrDefault(true)
        },
        livePasses = liveOutputClaimsPassthrough(format),
        sinkBitstreaming = AudioTrackRejectionLog.labelForMime(sink.lastConfiguredInputFormat?.sampleMimeType) != null,
        policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(format.sampleMimeType) == true,
        pcmByChoice = hasTriedAudioPcmFallback ||
            isSafeAudioModeActiveForCurrentPlayback ||
            isAudioDisabledForCurrentPlayback ||
            _uiState.value.playbackSpeed != 1f ||
            url in preferFfmpegAudioStreamUrls ||
            // Global PREFER (decoder priority or the VC-1 software fallback): FFmpeg wins the
            // renderer tie and decodes whatever the sink takes (review 200420 #2).
            cachedDecoderPriority == androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER ||
            url in vc1SoftwarePreferredStreamUrls ||
            currentPlayerSettings.matPassthroughEnabled,
        rebuildsSoFar = rebuildsSoFar
    )
    if (!rebuild) return false
    passthroughReturnRebuildUrl = url
    passthroughReturnRebuilds = rebuildsSoFar + 1
    val savedPosition = exoCarryPositionMs(player).takeIf { it > 0L } ?: 0L
    val paused = userPausedManually
    Log.i(
        PlayerRuntimeController.TAG,
        "PASSTHROUGH_RETURN rebuild reason=$reason mime=${format.sampleMimeType} " +
            "position=${savedPosition}ms rebuild=${rebuildsSoFar + 1}/$MAX_PASSTHROUGH_RETURN_REBUILDS_PER_STREAM"
    )
    showRecoveryOverlay()
    errorRetryJob?.cancel()
    rebuildCurrentStream("passthrough return", savedPosition, paused)
    return true
}

internal fun PlayerRuntimeController.applyBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    val sink = playbackSpeedAwareAudioSink
    if (sink != null && sink.isBluetoothForcePcm() == isBluetooth) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Bluetooth PCM policy already $isBluetooth; " +
                if (wasPlaying) "player keeps running" else "player stays paused"
        )
        if (!wasPlaying || userPausedManually) {
            _exoPlayer?.let { player ->
                player.playWhenReady = false
                player.pause()
            }
        }
        return
    }

    sink?.setBluetoothForcePcm(isBluetooth)

    // The denied-transcode set stays as built: recomputing it on a switch to Bluetooth could
    // enable AC-3 transcode toward a PCM-only sink, and back on wired force mode already
    // transcodes every eligible format.
    ffmpegAudioRenderer?.applyAudioOutputPlan(AudioOutputPlan.of(currentPlayerSettings, isBluetooth))

    sink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        if (!wasPlaying || userPausedManually) {
            player.playWhenReady = false
            player.pause()
        }
    }
}

internal fun PlayerRuntimeController.applyMpvBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val view = mpvView ?: return
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    view.applyBluetoothAudioRoute(isBluetooth, reloadOutput = true)
    // ao-reload can drop live properties; re-pin the current per-route delay.
    view.setAudioDelayMs(_uiState.value.audioDelayMs)
    view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
    if (!wasPlaying || userPausedManually) {
        view.setPaused(true)
    }
}
