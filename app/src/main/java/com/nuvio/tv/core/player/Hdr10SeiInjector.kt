package com.nuvio.tv.core.player

/**
 * Task 1 (RPU-informed strip-path output): authors and injects HDR10 static
 * metadata SEI (MDCV + CLLI) into a base-layer HEVC stream from which the Dolby
 * Vision RPU has been stripped, so a non-DV HDR10 sink tone-maps against the
 * source's real mastering metadata instead of a decoder default.
 *
 * The read half is proven ([DoviBridge.getRpuStaticMetadata]). This is the
 * write half: build the two SEI NALs once per stream from the RPU's static
 * metadata, and insert them ahead of the first VCL NAL of each stripped
 * length-delimited (MKV) sample.
 *
 * Byte layouts follow Rec. ITU-T H.265 D.2.28 (mastering_display_colour_volume,
 * payloadType 137) and D.2.35 (content_light_level_info, payloadType 144).
 *
 * Sourcing choices, made for confidence rather than completeness:
 *  - Mastering-display luminance is derived from the RPU's source_*_pq via the
 *    SMPTE ST 2084 EOTF (proven on device: a 1000-nit master reads ~1001 nits),
 *    NOT from the L6 luminance fields whose libdovi units are unverified.
 *  - MaxCLL/MaxFALL come straight from the L6 block (plain cd/m^2); CLLI is
 *    omitted entirely when both are 0 (== "unknown").
 *  - Display primaries are assumed BT.2020 + D65 (correct for essentially all
 *    UHD HDR masters); the RPU does not carry them.
 *
 * The byte layouts are validated without a device by a build-then-parse round
 * trip in the JVM unit test Hdr10SeiInjectorTest. No libdovi source is reproduced;
 * RPU-parsing credit: quietvoid/libdovi (MIT).
 */
internal object Hdr10SeiInjector {

    private const val NAL_HEADER_BYTE0 = 0x4E   // forbidden 0, nal_unit_type 39 (prefix SEI), layer 0
    private const val NAL_HEADER_BYTE1 = 0x01   // nuh_temporal_id_plus1 = 1
    private const val SEI_TYPE_MDCV = 137
    private const val SEI_TYPE_CLLI = 144
    private const val VCL_NAL_MAX_TYPE = 31     // HEVC VCL NAL unit types are 0..31

    // BT.2020 primaries and D65 white point in MDCV units of 0.00002 (coordinate
    // * 50000), given in the spec's G, B, R primary order.
    private const val PRIM_G_X = 8500    // 0.170
    private const val PRIM_G_Y = 39850   // 0.797
    private const val PRIM_B_X = 6550    // 0.131
    private const val PRIM_B_Y = 2300    // 0.046
    private const val PRIM_R_X = 35400   // 0.708
    private const val PRIM_R_Y = 14600   // 0.292
    private const val WHITE_X = 15635    // 0.3127 (D65)
    private const val WHITE_Y = 16450    // 0.3290 (D65)

    // ---------------------------------------------------------------------------
    // SEI authoring
    // ---------------------------------------------------------------------------

    /** SMPTE ST 2084 (PQ) EOTF applied to a 12-bit RPU code value; returns nits. */
    fun pqCodeToNits(pq12: Int): Double {
        val e = pq12.coerceIn(0, 4095).toDouble() / 4095.0
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val ep = Math.pow(e, 1.0 / m2)
        val num = (ep - c1).coerceAtLeast(0.0)
        val den = c2 - c3 * ep
        val l = if (den <= 0.0) 0.0 else Math.pow(num / den, 1.0 / m1)
        return l * 10000.0   // normalized display luminance -> nits (PQ peak is 10000 cd/m^2)
    }

    /** MDCV (mastering_display_colour_volume) prefix-SEI NAL, 24-byte payload. */
    fun buildMdcvSeiNal(sourceMinPq: Int, sourceMaxPq: Int): ByteArray {
        // SEI luminance is in units of 0.0001 cd/m^2.
        val maxLum = Math.round(pqCodeToNits(sourceMaxPq) * 10000.0).coerceIn(0L, 0xFFFFFFFFL)
        val minLum = Math.round(pqCodeToNits(sourceMinPq) * 10000.0).coerceIn(0L, 0xFFFFFFFFL)
        val p = ByteArray(24)
        var i = 0
        i = putU16(p, i, PRIM_G_X); i = putU16(p, i, PRIM_G_Y)
        i = putU16(p, i, PRIM_B_X); i = putU16(p, i, PRIM_B_Y)
        i = putU16(p, i, PRIM_R_X); i = putU16(p, i, PRIM_R_Y)
        i = putU16(p, i, WHITE_X); i = putU16(p, i, WHITE_Y)
        i = putU32(p, i, maxLum); putU32(p, i, minLum)
        return wrapSeiNal(SEI_TYPE_MDCV, p)
    }

    /** CLLI (content_light_level_info) prefix-SEI NAL, or null when both are 0. */
    fun buildClliSeiNal(maxCll: Int, maxFall: Int): ByteArray? {
        if (maxCll <= 0 && maxFall <= 0) return null
        val p = ByteArray(4)
        putU16(p, 0, maxCll.coerceIn(0, 0xFFFF))
        putU16(p, 2, maxFall.coerceIn(0, 0xFFFF))
        return wrapSeiNal(SEI_TYPE_CLLI, p)
    }

    /**
     * The SEI NALs to inject for a stream, from its RPU static metadata. MDCV is
     * always present; CLLI is included only when MaxCLL/MaxFALL are known.
     * Returns an empty list when no metadata is available.
     */
    fun buildSeiNals(meta: DoviBridge.RpuStaticMetadata?): List<ByteArray> {
        if (meta == null) return emptyList()
        val out = ArrayList<ByteArray>(2)
        out += buildMdcvSeiNal(meta.sourceMinPq, meta.sourceMaxPq)
        buildClliSeiNal(meta.maxCll ?: 0, meta.maxFall ?: 0)?.let { out += it }
        return out
    }

    // Assemble one prefix-SEI NAL: [2-byte header][emulation-escaped RBSP], where
    // RBSP = payloadType byte + payloadSize byte + payload + rbsp_trailing_bits.
    // Both type and size are < 255 here, so each is a single byte (no 0xFF run).
    private fun wrapSeiNal(payloadType: Int, payload: ByteArray): ByteArray {
        val rbsp = ByteArray(2 + payload.size + 1)
        rbsp[0] = payloadType.toByte()
        rbsp[1] = payload.size.toByte()
        System.arraycopy(payload, 0, rbsp, 2, payload.size)
        rbsp[rbsp.size - 1] = 0x80.toByte()   // rbsp_trailing_bits: stop bit + alignment
        val escaped = escapeRbsp(rbsp)
        val nal = ByteArray(2 + escaped.size)
        nal[0] = NAL_HEADER_BYTE0.toByte()
        nal[1] = NAL_HEADER_BYTE1.toByte()
        System.arraycopy(escaped, 0, nal, 2, escaped.size)
        return nal
    }

    // Emulation prevention: after any run of two 0x00 bytes, a following byte of
    // 0x00..0x03 gets a 0x03 inserted before it.
    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 4)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 0x03) {
                out.add(0x03.toByte())
                zeros = 0
            }
            out.add(b)
            zeros = if (v == 0x00) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------
    // Injection
    // ---------------------------------------------------------------------------

    /**
     * Insert [seiNals] as length-delimited NALs immediately before the first VCL
     * NAL of a length-delimited sample. If [seiNals] is empty the input is
     * returned unchanged.
     */
    fun injectLengthDelimited(
        sample: ByteArray,
        sampleLen: Int,
        nalLengthFieldLength: Int,
        seiNals: List<ByteArray>
    ): ByteArray {
        if (seiNals.isEmpty() || nalLengthFieldLength !in 1..4) return sample.copyOf(sampleLen)
        val insertAt = firstVclOffsetLengthDelimited(sample, sampleLen, nalLengthFieldLength)
        val extra = seiNals.sumOf { nalLengthFieldLength + it.size }
        val out = ByteArray(sampleLen + extra)
        System.arraycopy(sample, 0, out, 0, insertAt)
        var o = insertAt
        for (nal in seiNals) {
            o = putLengthField(out, o, nal.size, nalLengthFieldLength)
            System.arraycopy(nal, 0, out, o, nal.size); o += nal.size
        }
        System.arraycopy(sample, insertAt, out, o, sampleLen - insertAt)
        return out
    }

    // Offset at which to insert (start of the first VCL NAL's length prefix), or
    // sampleLen (append) if no VCL NAL is found.
    private fun firstVclOffsetLengthDelimited(sample: ByteArray, sampleLen: Int, nlf: Int): Int {
        var pos = 0
        while (pos + nlf <= sampleLen) {
            val nalSize = readLengthField(sample, pos, nlf)
            val nalStart = pos + nlf
            if (nalSize <= 0 || nalSize > sampleLen - nalStart) break
            val nalType = (sample[nalStart].toInt() ushr 1) and 0x3F
            if (nalType <= VCL_NAL_MAX_TYPE) return pos
            pos = nalStart + nalSize
        }
        return sampleLen
    }

    // ---------------------------------------------------------------------------
    // Detection (inject-when-absent guard; also the MKV DV5 strip-safety signal)
    // ---------------------------------------------------------------------------

    /**
     * True if the length-delimited sample carries an MDCV or CLLI prefix-SEI NAL: the
     * injection skips such samples, and DolbyVisionMatroskaTransformer treats it as a DV5 base
     * layer that is viewable on its own (safe to strip the RPU).
     */
    fun hasHdr10StaticSei(sample: ByteArray, sampleLen: Int, nalLengthFieldLength: Int): Boolean {
        if (nalLengthFieldLength !in 1..4) return false
        var pos = 0
        while (pos + nalLengthFieldLength <= sampleLen) {
            val nalSize = readLengthField(sample, pos, nalLengthFieldLength)
            val nalStart = pos + nalLengthFieldLength
            if (nalSize <= 0 || nalSize > sampleLen - nalStart) break
            if (isHdr10StaticSeiNal(sample, nalStart, nalSize)) return true
            pos = nalStart + nalSize
        }
        return false
    }

    private fun isHdr10StaticSeiNal(sample: ByteArray, nalOffset: Int, nalSize: Int): Boolean {
        if (nalSize < 3) return false
        val nalType = (sample[nalOffset].toInt() ushr 1) and 0x3F
        if (nalType != 39) return false
        val payloadType = sample[nalOffset + 2].toInt() and 0xFF   // single byte at 137/144
        return payloadType == SEI_TYPE_MDCV || payloadType == SEI_TYPE_CLLI
    }

    // ---------------------------------------------------------------------------
    // Low-level byte helpers (self-contained to keep this file independently
    // testable; deliberately not sharing HevcDvRpuStripper's private helpers).
    // ---------------------------------------------------------------------------

    private fun putU16(a: ByteArray, off: Int, v: Int): Int {
        a[off] = ((v ushr 8) and 0xFF).toByte()
        a[off + 1] = (v and 0xFF).toByte()
        return off + 2
    }

    private fun putU32(a: ByteArray, off: Int, v: Long): Int {
        a[off] = ((v ushr 24) and 0xFF).toByte()
        a[off + 1] = ((v ushr 16) and 0xFF).toByte()
        a[off + 2] = ((v ushr 8) and 0xFF).toByte()
        a[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }

    private fun readLengthField(a: ByteArray, off: Int, len: Int): Int {
        var v = 0
        for (k in 0 until len) v = (v shl 8) or (a[off + k].toInt() and 0xFF)
        return v
    }

    private fun putLengthField(a: ByteArray, off: Int, v: Int, len: Int): Int {
        for (k in 0 until len) a[off + k] = ((v ushr (8 * (len - 1 - k))) and 0xFF).toByte()
        return off + len
    }
}
