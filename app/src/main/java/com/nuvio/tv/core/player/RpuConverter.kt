package com.nuvio.tv.core.player

/**
 * Spec 21 N2: the per-RPU conversion seam of the MKV NAL pipeline. Production converts through
 * libdovi ([Native]); tests and the native host harness inject a deterministic fake (contract in
 * `app/src/test/resources/dv-golden/README.md`), so the pipeline's byte output is checkable
 * without the native library.
 */
internal fun interface RpuConverter {
    /**
     * Converts the RPU NAL `sample[offset, offset + len)` (2-byte NAL header included) into
     * [out] for libdovi conversion [mode]. Returns the bytes written to `out.bytes` (> 0) on
     * success; any value <= 0 is a failure (the caller drops the RPU).
     */
    fun convert(sample: ByteArray, offset: Int, len: Int, out: DoviBridge.OutBuffer, mode: Int): Int

    companion object {
        val Native = RpuConverter(DoviBridge::convertDv7RpuToDv81NonAllocating)
    }
}
