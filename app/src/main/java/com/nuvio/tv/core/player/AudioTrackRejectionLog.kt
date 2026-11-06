/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.core.player

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink

/**
 * One observed refusal of a passthrough AudioTrack by the platform for a bitstream encoding
 * it had claimed to support - the ground-truth signal that isDirectPlaybackSupported and
 * AudioDeviceInfo.getEncodings() cannot supply proactively (they read the vendor policy and
 * EDID, not the HAL's actual open() result). Recorded when an AudioTrack init failure (5001)
 * hits a bitstream sink input.
 *
 * The only in-app reader is the F2b diagnostic (DiagnosticsCard appends the refused
 * encodings to the Audio Caps row). F3 cross-session learning does not read this store: it
 * persists "routeKey::GROUP" entries through PlayerSettingsDataStore.recordAudioRejection
 * when [audioRejectionLearning] allows it, gated once per session by
 * [AudioTrackRejectionLog.markGroupFirstThisSession].
 */
internal data class AudioTrackRejection(
    /** Short label, e.g. "DTS-HD" - the same vocabulary as the Audio Chain Claims row. */
    val encoding: String,
    /** Output-route key at the time of refusal, e.g. "type:hdmi_arc|...", or null. */
    val routeKey: String?,
    /** "Manufacturer Model", so evidence never leaks across a device restore. */
    val deviceFingerprint: String,
    val atMs: Long
)

/**
 * Session-scoped in-memory store of [AudioTrackRejection]s for the F2b diagnostic (a failure,
 * then opening the card, in the same session), plus the per-session F3 persistence gate.
 */
internal object AudioTrackRejectionLog {

    private val lock = Any()
    private val entries = mutableListOf<AudioTrackRejection>()
    private val groupsPersistedThisSession = mutableSetOf<String>()

    fun deviceFingerprint(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Bitstream sample MIME to the short label used in the Audio Chain Claims row. */
    fun labelForMime(mime: String?): String? = when (mime) {
        "audio/ac3" -> "AC3"           // MimeTypes.AUDIO_AC3
        "audio/eac3" -> "EAC3"         // MimeTypes.AUDIO_E_AC3
        "audio/eac3-joc" -> "EAC3-JOC" // MimeTypes.AUDIO_E_AC3_JOC
        "audio/true-hd" -> "TrueHD"    // MimeTypes.AUDIO_TRUEHD
        "audio/vnd.dts" -> "DTS"       // MimeTypes.AUDIO_DTS
        "audio/vnd.dts.hd" -> "DTS-HD" // MimeTypes.AUDIO_DTS_HD
        else -> null
    }

    /** Records one refusal, de-duplicated on (encoding, routeKey), keeping the latest time. */
    fun record(encoding: String, routeKey: String?, atMs: Long) {
        synchronized(lock) {
            entries.removeAll { it.encoding == encoding && it.routeKey == routeKey }
            entries.add(AudioTrackRejection(encoding, routeKey, deviceFingerprint(), atMs))
        }
    }

    /** All refusals recorded this session, in insertion order. */
    fun snapshot(): List<AudioTrackRejection> = synchronized(lock) { entries.toList() }

    /**
     * True the first time [routeGroupKey] ("routeKey::GROUP") is offered this session; used
     * to persist each (route, group) rejection at most once per session, so the datastore's
     * two-session confirmation guard counts distinct sessions rather than retries within one.
     */
    fun markGroupFirstThisSession(routeGroupKey: String): Boolean = synchronized(lock) {
        groupsPersistedThisSession.add(routeGroupKey)
    }

    /** Clears the log. Test-only today. */
    fun reset() {
        synchronized(lock) {
            entries.clear()
            groupsPersistedThisSession.clear()
        }
    }
}

/**
 * The format the refused AudioTrack was opened for, from the sink's
 * [AudioSink.InitializationException] in [error]'s cause chain. Not the renderer format: when
 * FFmpeg decodes, the renderer format is the compressed source while the sink was opened for PCM,
 * and that refusal says nothing about the receiver taking the bitstream (upstream
 * `failedAudioTrackInputFormat`, `126626500`).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun failedAudioTrackInputFormat(error: Throwable?): Format? =
    generateSequence(error) { it.cause }
        .take(8)
        .filterIsInstance<AudioSink.InitializationException>()
        .firstOrNull()
        ?.format

/** Whether a refused bitstream open may become F3 evidence, and if not, why (logged). */
internal enum class AudioRejectionLearning { LEARN, NO_ROUTE, ROUTE_CHANGED, ROUTE_CHANGING, OUTPUT_GONE }

/**
 * F3 outage guard (Register A2 P1). A refusal is only evidence that the receiver lacks the format
 * when it happened on the route the player was built for, with no audio device added or removed
 * around it, and the live output still claims passthrough for it. A route that changed or is
 * changing under the player, or an output that went away (AVR asleep, TV on another input, EDID
 * re-read), refuses every bitstream; learning those denied a working codec on that route for good.
 * Called at the refusal and again after a settle delay. Residual: an outage that raises no device
 * event and recovers inside the settle delay still counts (two such sessions on one route needed).
 */
internal fun audioRejectionLearning(
    routeAtBuild: String?,
    routeNow: String?,
    deviceChangedNearRefusal: Boolean,
    outputStillClaims: Boolean
): AudioRejectionLearning = when {
    routeNow == null -> AudioRejectionLearning.NO_ROUTE
    routeNow != routeAtBuild -> AudioRejectionLearning.ROUTE_CHANGED
    deviceChangedNearRefusal -> AudioRejectionLearning.ROUTE_CHANGING
    !outputStillClaims -> AudioRejectionLearning.OUTPUT_GONE
    else -> AudioRejectionLearning.LEARN
}

/**
 * Whether an AudioDeviceCallback event changed the device set. Registering the callback replays
 * every connected device through onAudioDevicesAdded; with [known] seeded from the device list at
 * registration, that replay (and any re-report) is not a change. A removal always is.
 */
internal fun isAudioDeviceSetChange(known: Set<Int>, eventDeviceIds: Set<Int>, added: Boolean): Boolean =
    !added || !known.containsAll(eventDeviceIds)
