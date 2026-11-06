package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

/**
 * Where a new Exo player starts. Starting at 0 and seeking once the player is ready throws away what it buffered at
 * 0, so a position a rebuild carried over goes in as the start position instead (ysosrs f37b5c231; upstream's
 * `explicit` argument is dropped: no caller here passes one).
 */
internal object PlayerStartPosition {

    /** A position a rebuild carried over, else the saved resume point. */
    fun choose(carriedOver: Long?, savedResume: () -> Long): Long =
        carriedOver?.takeIf { it > 0L } ?: savedResume()

    /**
     * Spec 57 D4: whether a source switch or failover reads the saved resume. Not when the live position seeded
     * the switch, and not while a "Start from beginning" session has saved no progress yet (the saved row is the
     * old resume point the viewer turned down).
     */
    fun readsSavedResumeOnSwitch(seededFromLivePosition: Boolean, startFromBeginningPending: Boolean): Boolean =
        !seededFromLivePosition && !startFromBeginningPending

    /** Spec 57 D2: a start past 0 on a track that [needsWarmDecoder] first decodes from 0, then seeks. */
    fun shouldPrimeFromZero(trackNeedsWarmDecoder: Boolean, startPositionMs: Long): Boolean =
        trackNeedsWarmDecoder && startPositionMs > 0L

    /**
     * Spec 57 D2: an MP4 HEVC or Dolby Vision track whose hvcC carries no parameter-set arrays has VPS/SPS/PPS
     * in-band, possibly at the stream start only: a cold decoder entered at a later keyframe never begins a
     * sequence (Shield OMX.Nvidia.DOVI), while a decoder warmed from 0 survives seeks. Tunneled playback is left
     * out: its first frame is a synthetic READY, so a prime would have no completion signal.
     *
     * Parameter sets are read from [initializationData] by content: Media3 1.8.0 writes each hvcC/avcC entry as
     * Annex-B NAL units (start code first, HevcConfig/AvcConfig) and appends the dvcC/dvvC record body for Dolby
     * Vision (BoxParser), so a DV track without parameter sets still carries one entry (24 bytes, version 1.0).
     */
    fun needsWarmDecoder(
        containerMimeType: String?,
        sampleMimeType: String?,
        initializationData: List<ByteArray>,
        tunneled: Boolean,
    ): Boolean =
        !tunneled &&
            containerMimeType == MimeTypes.VIDEO_MP4 &&
            (sampleMimeType == MimeTypes.VIDEO_H265 || sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) &&
            initializationData.none(::startsWithNalStartCode)

    private fun startsWithNalStartCode(data: ByteArray): Boolean =
        data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() &&
            data[3] == 1.toByte()
}
