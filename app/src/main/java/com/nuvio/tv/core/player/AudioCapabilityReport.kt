/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.core.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * What the platform says the audio chain can take, captured once per sink build.
 *
 * The per-format passthrough overrides exist because this report is sometimes wrong - a
 * chain advertises DTS in its EDID that the receiver cannot actually decode. When a user
 * says "no audio on DTS-HD", the first thing worth knowing is what the platform claimed,
 * and that is invisible today. This puts it on the diagnostics page so it can be
 * photographed and quoted, with no logcat required.
 *
 * Read-only: probes capabilities and reads a settings value. It never opens an AudioTrack
 * (see the MAT workstream - startup IEC61937 activity is a wedge risk on some HALs) and
 * never writes a setting.
 *
 * [AudioTrack.isDirectPlaybackSupported] is the same question media3's AudioCapabilities
 * asks, so this reports what the sink will decide, not an independent opinion. The
 * encoded-surround *mode* is included because it is what makes the platform's answer
 * interpretable: in AUTO the enabled set is derived from EDID and is exactly as
 * trustworthy as the EDID; in MANUAL the user has curated it deliberately.
 */
object AudioCapabilityReport {

    /** Label to AudioFormat encoding, in the order they appear in the report. */
    private val PROBES: List<Pair<String, Int>> = buildList {
        add("AC3" to AudioFormat.ENCODING_AC3)
        add("EAC3" to AudioFormat.ENCODING_E_AC3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add("EAC3-JOC" to AudioFormat.ENCODING_E_AC3_JOC)
        }
        add("TrueHD" to AudioFormat.ENCODING_DOLBY_TRUEHD)
        add("DTS" to AudioFormat.ENCODING_DTS)
        add("DTS-HD" to AudioFormat.ENCODING_DTS_HD)
    }

    /**
     * [text] = the Audio Chain Claims lines; [listedNotOpened] = encodings the HDMI device
     * lists that this box cannot open directly (space-separated), null when there are none.
     */
    data class Snapshot(val text: String, val listedNotOpened: String?)

    @Volatile
    private var latestReport: Snapshot? = null

    /** The most recent report, or null if none has been captured yet. */
    val latest: Snapshot?
        get() = latestReport

    /**
     * Probes the platform and stores the result. Call from the sink build, where the same
     * MEDIA/MOVIE attributes the sink uses are in force.
     *
     * Any failure is swallowed into a short marker rather than propagated: a diagnostic
     * must never be able to break playback.
     */
    fun capture(context: Context) {
        latestReport = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Snapshot("unavailable (API ${Build.VERSION.SDK_INT} < 29)", null)
            } else {
                val attributes = mediaMovieAudioAttributes()
                val supported = mutableListOf<String>()
                val absent = mutableListOf<String>()
                for ((label, ok) in probeDirectLabels(attributes)) {
                    (if (ok) supported else absent).add(label)
                }
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val negotiated = negotiatedEncodings(audioManager)
                Snapshot(
                    text = format(supported, absent, negotiated, surroundModeName(audioManager), hdmiPlugReport(context)),
                    listedNotOpened = listedNotOpened(negotiated, absent)
                )
            }
        } catch (t: Throwable) {
            Snapshot("unavailable (${t.javaClass.simpleName})", null)
        }
    }

    /** Clears the report. Used by tests. */
    internal fun reset() {
        latestReport = null
    }

    /**
     * What the platform claims the chain can take directly, one flag per format group
     * the per-format passthrough switches can act on. Same probe as [capture]; exposed
     * structurally so the Device Assessment can key its per-format rows on it rather
     * than parsing the display string.
     *
     * Null below API 29 (no isDirectPlaybackSupported) or when the probe throws. A
     * false here is the same answer media3's AudioCapabilities will give the sink, so
     * "absent" can never be wrong in the direction that loses working passthrough.
     */
    data class DirectSupport(
        val ac3: Boolean,
        val eac3: Boolean,
        val trueHd: Boolean,
        val dts: Boolean,
        val dtsHd: Boolean
    )

    fun probeDirectSupport(): DirectSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val m = probeDirectLabels(mediaMovieAudioAttributes()).toMap()
            DirectSupport(
                ac3 = m["AC3"] == true,
                eac3 = m["EAC3"] == true,
                trueHd = m["TrueHD"] == true,
                dts = m["DTS"] == true,
                dtsHd = m["DTS-HD"] == true
            )
        }.getOrNull()
    }

    /** One isDirectPlaybackSupported call per [PROBES] entry, 48 kHz / 5.1, in order. */
    private fun probeDirectLabels(attributes: AudioAttributes): List<Pair<String, Boolean>> =
        PROBES.map { (label, encoding) ->
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            // why: the exact call media3's AudioCapabilities makes on API 29-32 (Shield: 30); the
            // API-33 replacement (AudioManager.getDirectPlaybackSupport) would need an SDK branch.
            @Suppress("DEPRECATION")
            val ok = runCatching {
                AudioTrack.isDirectPlaybackSupported(format, attributes)
            }.getOrDefault(false)
            label to ok
        }

    /**
     * Pure formatter, split out so the wording is unit-testable without a device. "Box" lines
     * come from isDirectPlaybackSupported, "HDMI" lines from the TV or receiver the box is
     * plugged into (upstream eb69c856a wording); the plug report line only when there is one.
     */
    internal fun format(
        supported: List<String>,
        absent: List<String>,
        negotiated: List<String>,
        surroundMode: String,
        plugReport: String?
    ): String {
        val direct = if (supported.isEmpty()) "none" else supported.joinToString(" ")
        val missing = if (absent.isEmpty()) "none" else absent.joinToString(" ")
        // An empty list is common on TVs (getEncodings not exposed): unknown, not "accepts nothing".
        val listed = if (negotiated.isEmpty()) "unknown" else negotiated.joinToString(" ")
        return buildString {
            append("Box opens: $direct\nBox cannot open: $missing\nHDMI device lists: $listed")
            if (plugReport != null) append("\nHDMI plug report: $plugReport")
            append("\nAndroid surround: $surroundMode")
        }
    }

    /** Encodings the HDMI device lists that the box cannot open, in list order; null when none. */
    internal fun listedNotOpened(negotiated: List<String>, absent: List<String>): String? =
        negotiated.filter { it in absent }.joinToString(" ").ifEmpty { null }

    /**
     * "plugged in · 8 ch · PCM16 AC3 …" from the sticky ACTION_HDMI_AUDIO_PLUG extras; null
     * unless the report says plugged in. The channel count is EXTRA_MAX_CHANNEL_COUNT, documented
     * as the maximum the HDMI device supports, i.e. not PCM-specific (unlike
     * [readMaxPcmChannelCount]'s per-encoding read, which needs API 31 and so reads nothing on
     * the Shield's API 30).
     */
    internal fun plugReportLabel(state: Int, maxChannels: Int, encodings: IntArray?): String? {
        if (state != 1) return null
        val labels = encodings?.asList()?.mapNotNull(::encodingLabel)?.distinct().orEmpty()
        return buildList {
            add("plugged in")
            if (maxChannels > 0) add("$maxChannels ch")
            if (labels.isNotEmpty()) add(labels.joinToString(" "))
        }.joinToString(" · ")
    }

    /** Same sticky-intent read Media3's AudioCapabilities does (null receiver = no registration). */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun hdmiPlugReport(context: Context): String? {
        val report = runCatching {
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        }.getOrNull() ?: return null
        return plugReportLabel(
            state = report.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1),
            maxChannels = report.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, -1),
            encodings = report.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
        )
    }

    /** HDMI/ARC/eARC output device types whose negotiated encodings we report. */
    private val HDMI_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC
    )

    /**
     * The encodings the connected HDMI/ARC/eARC output actually negotiated, read from
     * [AudioDeviceInfo.getEncodings]. A different oracle from isDirectPlaybackSupported
     * above: that reads the vendor audio-policy profiles (static, and on some TVs still
     * advertising formats the licence-stripped HAL will not open); this reflects what the
     * HDMI link reported after EDID negotiation, i.e. the TV or receiver the box is plugged
     * into. When the two disagree, the direct probe decides what the box will actually open
     * (the card's "Listed by HDMI, not opened by this box" row). PCM16 is included
     * deliberately: its presence is what makes the line legible at a glance.
     *
     * Best-effort, de-duplicated labels. getEncodings() returns an empty array when the
     * platform does not expose it, which is common on TVs; [format] says "unknown" then.
     */
    private fun negotiatedEncodings(audioManager: AudioManager?): List<String> {
        if (audioManager == null) return emptyList()
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HDMI_OUTPUT_TYPES }
                .flatMap { it.encodings.asList() }
                .distinct()
                .mapNotNull(::encodingLabel)
        }.getOrNull().orEmpty()
    }

    /**
     * F2c: highest PCM output channel count the connected HDMI/ARC/eARC chain
     * negotiates, read PER-ENCODING from [AudioDeviceInfo.getAudioProfiles]
     * (API 31+) and filtered to PCM formats only. This is the one capability
     * no failure can ever teach: a 2 ch-LPCM chain plays multichannel PCM
     * "successfully" - silently collapsed to stereo - so nothing errors and
     * F3 never learns. Only a read like this can tell the assessment that
     * Transcode-to-AC-3 is the better denied-codec handling for the chain.
     *
     * Previously this used [AudioDeviceInfo.getChannelCounts], which returns
     * the port's channel-count UNION across EVERY encoding. On a chain that
     * takes 2-ch LPCM but 5.1 compressed (plain ARC, or a stereo TV passing
     * surround through) that union reports 6 and wrongly steers the default to
     * DECODE_PCM - the exact failure this read exists to prevent. On eARC the
     * PCM profile itself reaches 7.1, so union == PCM and the answer is
     * unchanged; the divergence only bites where PCM < compressed. Verified
     * against the AM9 Pro's dumpsys profiles (PCM reaches 0x18FC/8ch; AC3
     * caps at 0xFC/6ch) - the per-encoding sets genuinely differ.
     *
     * getAudioProfiles()/getChannelMasks() are API 31+; below S this returns
     * null and the assessment's denied-codec row degrades to VERIFY rather
     * than guessing. Best-effort like the negotiated-encodings read above.
     */
    @SuppressLint("NewApi", "InlinedApi")
    fun readMaxPcmChannelCount(context: Context): Int? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        // getAudioProfiles() and the AudioProfile channel-mask getters are S+.
        // Below that there is no PCM-specific read (getChannelCounts unions
        // across encodings), so report unknown and let the row fall to VERIFY.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val pcmEncodings = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HDMI_OUTPUT_TYPES }
                .flatMap { it.audioProfiles }
                .filter { it.format in pcmEncodings }
                // Both positional and index masks resolve to a channel count via
                // popcount; take the largest across every PCM profile's masks.
                .flatMap { profile ->
                    (profile.channelMasks.asList() + profile.channelIndexMasks.asList())
                        .map { mask -> Integer.bitCount(mask) }
                }
                .filter { it > 0 }
                .maxOrNull()
        }.getOrNull()
    }

    /** AudioFormat.ENCODING_* to the same short labels used in the direct/absent lists. */
    private fun encodingLabel(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_E_AC3_JOC -> "EAC3-JOC"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        else -> null
    }

    /**
     * The Android TV "surround sound" mode. NEVER/ALWAYS/AUTO/MANUAL are the platform's
     * own names; MANUAL means the user has ticked formats by hand, which is the only case
     * where the enabled set carries information the EDID does not.
     */
    private fun surroundModeName(audioManager: AudioManager?): String {
        if (audioManager == null) return "unknown"
        // getEncodedSurroundMode() is API 31+ (Android 12). The previous API 29 gate let
        // the call through on Android 10/11, where it throws and collapsed to "unknown" -
        // indistinguishable from a genuinely unreadable value. Attempt only where the
        // getter exists, wrap it so an OEM off-by-one still degrades cleanly, and say
        // "n/a" when the platform cannot report the mode rather than "unknown".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "n/a (<API 31)"
        val mode = runCatching { audioManager.encodedSurroundMode }.getOrNull()
            ?: return "n/a"
        return when (mode) {
            AudioManager.ENCODED_SURROUND_OUTPUT_NEVER -> "NEVER"
            AudioManager.ENCODED_SURROUND_OUTPUT_ALWAYS -> "ALWAYS"
            AudioManager.ENCODED_SURROUND_OUTPUT_AUTO -> "AUTO"
            AudioManager.ENCODED_SURROUND_OUTPUT_MANUAL -> "MANUAL"
            else -> "unknown"
        }
    }
}
