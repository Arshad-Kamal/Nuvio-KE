package com.nuvio.tv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * Human-readable codec and channel-layout labels for the player's track info. The codec
 * table was ported from Just (Video) Player's CustomDefaultTrackNameProvider (the provider
 * class itself was never instantiated here; only these lookups were used).
 */
@OptIn(UnstableApi::class)
internal object TrackFormatNames {
    /**
     * The label for a track format, falling back to [codecs]: subtitles parsed during extraction
     * report `application/x-media3-cues` and keep the original MIME in `codecs` (Register A2 P9),
     * and some video formats carry only the codec string.
     */
    fun formatName(sampleMimeType: String?, codecs: String?): String? =
        formatNameFromMime(sampleMimeType) ?: formatNameFromMime(codecs)

    fun formatNameFromMime(mimeType: String?): String? {
        if (mimeType == null) return null

        return when (mimeType) {
            // Audio codecs
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3-JOC"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_WAV -> "WAV"
            MimeTypes.AUDIO_RAW -> "PCM"
            MimeTypes.AUDIO_AMR -> "AMR"
            MimeTypes.AUDIO_AMR_NB -> "AMR-NB"
            MimeTypes.AUDIO_AMR_WB -> "AMR-WB"
            MimeTypes.AUDIO_IAMF -> "IAMF"
            MimeTypes.AUDIO_MPEGH_MHA1 -> "MPEG-H"
            MimeTypes.AUDIO_MPEGH_MHM1 -> "MPEG-H"
            // Video codecs
            MimeTypes.VIDEO_H264 -> "AVC"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP8 -> "VP8"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            // Subtitle codecs
            "application/pgs" -> "PGS"
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.TEXT_SSA -> "SSA"
            MimeTypes.TEXT_VTT -> "VTT"
            MimeTypes.APPLICATION_TTML -> "TTML"
            MimeTypes.APPLICATION_TX3G -> "TX3G"
            MimeTypes.APPLICATION_DVBSUBS -> "DVB"
            else -> null
        }
    }

    /**
     * Get a human-readable channel layout description.
     */
    fun getChannelLayoutName(channelCount: Int): String? {
        return when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (channelCount > 0) "${channelCount}ch" else null
        }
    }
}
