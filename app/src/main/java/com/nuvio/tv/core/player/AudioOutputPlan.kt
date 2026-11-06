package com.nuvio.tv.core.player

import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Audio output configuration for the current route: one home for the Bluetooth rules the
 * player build and the in-place route switch share. A Bluetooth media sink (A2DP / LE Audio)
 * is PCM-only and stereo, so force-optical never wins over it (an AC3/DTS AudioTrack to A2DP
 * fails hard) and it always downmixes to a clean 2.0.
 */
internal data class AudioOutputPlan(
    val forceOpticalPassthrough: Boolean,
    val downmixEnabled: Boolean,
    val outputChannels: AudioOutputChannels,
    val downmixNormalizationEnabled: Boolean
) {
    companion object {
        fun of(settings: PlayerSettings, isBluetooth: Boolean): AudioOutputPlan = AudioOutputPlan(
            forceOpticalPassthrough = !isBluetooth &&
                settings.forceOpticalPassthrough &&
                settings.decoderPriority != 0,
            downmixEnabled = settings.effectiveDownmixEnabled || isBluetooth,
            outputChannels = if (isBluetooth) AudioOutputChannels.CHANNELS_2_0 else settings.audioOutputChannels,
            downmixNormalizationEnabled = !settings.maintainOriginalAudioOnDownmix
        )
    }
}

/** Applies [plan] to the FFmpeg renderer: force-optical, then the downmix layout (or none). */
internal fun FfmpegAudioRenderer.applyAudioOutputPlan(plan: AudioOutputPlan) {
    setForceOpticalPassthrough(plan.forceOpticalPassthrough)
    if (plan.downmixEnabled) {
        setAudioOutputChannels(plan.outputChannels.ffmpegLayoutName, plan.outputChannels.channelCount)
        setDownmixNormalizationEnabled(plan.downmixNormalizationEnabled)
    } else {
        setAudioOutputChannels(null, 0)
        setDownmixNormalizationEnabled(false)
    }
}
