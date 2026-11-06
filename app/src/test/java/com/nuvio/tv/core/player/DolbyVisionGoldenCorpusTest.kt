package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Spec 21 N2: golden byte outputs of today's Kotlin MKV per-sample pipeline
 * ([DolbyVisionMatroskaTransformer] with a deterministic fake [RpuConverter]) over a synthetic
 * corpus. The N3 native core must reproduce every `.out` byte for byte from the same `.in`.
 * Format, case configs and the fake converter contract: `src/test/resources/dv-golden/README.md`.
 *
 * A missing or mismatched golden writes the actual files to `app/build/dv-golden-actual/`;
 * review them before copying over the goldens (they pin the CURRENT Kotlin behaviour, quirks
 * included: equivalence, not unification).
 */
@UnstableApi
class DolbyVisionGoldenCorpusTest {

    @Test
    fun `kotlin pipeline output matches the golden for every case`() {
        val actualDir = File("build/dv-golden-actual")
        val mismatched = mutableListOf<String>()
        val manifest = StringBuilder(MANIFEST_HEADER)
        for (case in cases()) {
            val result = run(case)
            manifest.append("${case.name}\t${case.config.id}\t${case.profile ?: "none"}\t${case.lfl}\t${case.frames.size}\t${result.rpuDrops}\n")
            val inBytes = encodeFrames(case.frames)
            val outBytes = encodeFrames(result.outputs)
            val goldenIn = resource("${case.name}.in")
            val goldenOut = resource("${case.name}.out")
            if (goldenIn == null || !goldenIn.contentEquals(inBytes) ||
                goldenOut == null || !goldenOut.contentEquals(outBytes)
            ) {
                mismatched += case.name
                actualDir.mkdirs()
                File(actualDir, "${case.name}.in").writeBytes(inBytes)
                File(actualDir, "${case.name}.out").writeBytes(outBytes)
            }
        }
        val goldenManifest = resource("cases.tsv")?.decodeToString()
        if (goldenManifest != manifest.toString()) {
            mismatched += "cases.tsv"
            actualDir.mkdirs()
            File(actualDir, "cases.tsv").writeText(manifest.toString())
        }
        if (mismatched.isNotEmpty()) {
            fail("dv-golden mismatch (actual in app/build/dv-golden-actual/): $mismatched")
        }
    }

    /** The corpus covers what it claims (guards against a builder change hollowing a case). */
    @Test
    fun `corpus exercises convert, failure drops, abandon and pass-through`() {
        val byName = cases().associateBy { it.name }
        // 59 fails, ok, 59 fails, ok: the counter resets on success, so the last RPU converts.
        val reset = byName.getValue("p7_failure_counter_reset_lfl4")
        val resetRun = run(reset)
        assertEquals(118L, resetRun.rpuDrops)
        assertEquals("last RPU converted (fake appends the mode byte)", 2.toByte(), resetRun.outputs.last().last())
        assertEquals(1L, run(byName.getValue("p7_failed_rpu_then_abort_lfl4")).rpuDrops)
        // 60 fails abandon conversion: the 2 good RPUs after are dropped too.
        assertEquals(62L, run(byName.getValue("p7_abandon_lfl4")).rpuDrops)
        // DV5: static SEI on the 15th sample strips from there; on the 16th it is too late.
        val at15 = run(byName.getValue("p5_strip_sei_at_15th_lfl4"))
        val at16 = byName.getValue("p5_strip_sei_at_16th_lfl4")
        assertTrue(at15.outputs[14].size < byName.getValue("p5_strip_sei_at_15th_lfl4").frames[14].size)
        assertEquals(at16.frames.map { it.toList() }, run(at16).outputs.map { it.toList() })
        val passThrough = byName.getValue("p7_truncated_tail_convert_lfl4")
        assertEquals(passThrough.frames.map { it.toList() }, run(passThrough).outputs.map { it.toList() })
        val converted = byName.getValue("p7_mel_auto_lfl4")
        val out = run(converted).outputs
        for (i in out.indices) {
            if (out[i].contentEquals(converted.frames[i])) fail("frame $i of ${converted.name} not rewritten")
        }
    }

    /**
     * Spec 21 N2/D4 step 1: JVM timing of the Kotlin per-sample walk on 4K-sized frames
     * (~400 KB average, 1.5 MB I-frame). Opt-in (`DV_TIMING=1`), results on stdout.
     */
    @Test
    fun `timing of the kotlin walk on 4K-sized frames`() {
        assumeTrue(System.getenv("DV_TIMING") == "1")
        val iFrame = timingFrame(sliceBytes = 300_000, elBytes = 300_000)   // ~1.5 MB
        val pFrame = timingFrame(sliceBytes = 80_000, elBytes = 80_000)     // ~400 KB
        val lines = mutableListOf<String>()
        for (config in listOf(Config.CONVERT_MANUAL, Config.STRIP)) {
            for ((label, frame) in listOf("avg400KB" to pFrame, "I1.5MB" to iFrame)) {
                val transformer = config.newTransformer()
                val iterations = if (frame.size > 1_000_000) 200 else 600
                repeat(iterations / 2) { transformer.transformHevcSample(frame, frame.size, 4, configBytes(7)) }
                val samples = LongArray(iterations)
                for (i in 0 until iterations) {
                    val t0 = System.nanoTime()
                    transformer.transformHevcSample(frame, frame.size, 4, configBytes(7))
                    samples[i] = System.nanoTime() - t0
                }
                samples.sort()
                lines += "DV_TIMING kotlin ${config.id} $label bytes=${frame.size} " +
                    "medianUs=${samples[iterations / 2] / 1000} p90Us=${samples[iterations * 9 / 10] / 1000}"
            }
        }
        lines.forEach(::println)
    }

    // ── Runner (mirrors the vendored extractor's call sequence) ──

    private class Result(val outputs: List<ByteArray>, val rpuDrops: Long)

    private fun run(case: Case): Result {
        DolbyVisionConversionStats.reset()
        val transformer = case.config.newTransformer()
        val configBytes = case.profile?.let(::configBytes)
        // MatroskaExtractor: requiresDolbyVisionTransform = shouldTransform(codecs, config) at format
        // time (codecs passed as null here; the config record decides the profile). The extractor
        // also keeps the original payload on a RuntimeException; here one fails the test instead,
        // so a crash can never be recorded as a pass-through golden.
        val transform = transformer.shouldTransform(null, configBytes)
        val outputs = case.frames.map { frame ->
            if (!transform) return@map frame
            transformer.transformHevcSample(frame, frame.size, case.lfl, configBytes)
                ?.copyOf(transformer.lastTransformedSampleLength()) ?: frame
        }
        return Result(outputs, DolbyVisionConversionStats.getRpuDropCount())
    }

    private fun resource(name: String): ByteArray? =
        javaClass.getResourceAsStream("/dv-golden/$name")?.use { it.readBytes() }

    // ── Case configs (README "Configs") ──

    internal enum class Config(val id: String, val newTransformer: () -> DolbyVisionMatroskaTransformer) {
        CONVERT_AUTO("convert_auto", { transformer(DolbyVisionConversionConfig(active = true)) }),
        CONVERT_MANUAL("convert_manual", {
            transformer(DolbyVisionConversionConfig(active = true, manualDv81 = true))
        }),
        CONVERT_MANUAL_HDR10P("convert_manual+hdr10p", {
            transformer(DolbyVisionConversionConfig(active = true, manualDv81 = true), stripHdr10Plus = true)
        }),
        CONVERT_DV5("convert_dv5", {
            transformer(DolbyVisionConversionConfig(active = true, dv5Enabled = true, manualDv81 = true))
        }),
        STRIP("strip", { transformer(DolbyVisionConversionConfig(active = false), stripRpuOnly = true) }),
        STRIP_HDR10P("strip+hdr10p", {
            transformer(DolbyVisionConversionConfig(active = false), stripRpuOnly = true, stripHdr10Plus = true)
        }),
        HDR10P("hdr10p", { transformer(DolbyVisionConversionConfig(active = false), stripHdr10Plus = true) }),
    }

    internal class Case(
        val name: String,
        val config: Config,
        val profile: Int?,
        val lfl: Int,
        val frames: List<ByteArray>,
    )

    // ── Corpus ──

    private fun cases(): List<Case> {
        // One PRNG seed per case name, so adding or reordering a case leaves the others' bytes alone.
        fun case(name: String, config: Config, profile: Int?, lfl: Int, frames: (CorpusBuilder) -> List<ByteArray>) =
            Case(name, config, profile, lfl, frames(CorpusBuilder(name.hashCode())))
        return listOf(
            case("p7_mel_auto_lfl4", Config.CONVERT_AUTO, 7, 4) {
                listOf(it.melFrame(4, key = true), it.melFrame(4), it.melFrame(4))
            },
            case("p7_mel_manual_lfl4", Config.CONVERT_MANUAL, 7, 4) {
                listOf(it.melFrame(4, key = true), it.melFrame(4))
            },
            case("p7_fel_unspec63_manual_lfl4", Config.CONVERT_MANUAL, 7, 4) {
                listOf(it.felUnspec63Frame(4, key = true), it.felUnspec63Frame(4))
            },
            case("p7_mel_hdr10plus_manual_h10p_lfl4", Config.CONVERT_MANUAL_HDR10P, 7, 4) {
                listOf(it.hdr10PlusFrame(4, dv = true, key = true), it.hdr10PlusFrame(4, dv = true))
            },
            case("p7_mel_hdr10plus_manual_h10p_lfl3", Config.CONVERT_MANUAL_HDR10P, 7, 3) {
                listOf(it.hdr10PlusFrame(3, dv = true, key = true))
            },
            case("p7_mel_hdr10plus_strip_h10p_lfl4", Config.STRIP_HDR10P, 7, 4) {
                listOf(it.hdr10PlusFrame(4, dv = true, key = true), it.hdr10PlusFrame(4, dv = true))
            },
            case("p7_mel_hdr10plus_strip_h10p_lfl2", Config.STRIP_HDR10P, 7, 2) {
                listOf(it.hdr10PlusFrame(2, dv = true, key = true))
            },
            case("p7_mel_manual_lfl1", Config.CONVERT_MANUAL, 7, 1) {
                listOf(it.melFrame(1, key = true, small = true), it.melFrame(1, small = true))
            },
            // lfl 1: the converted RPU (255 + 1 bytes) no longer fits the length field -> pass-through.
            case("p7_rpu_overflow_lfl1", Config.CONVERT_MANUAL, 7, 1) { listOf(it.rpuOverflowFrame()) },
            case("p7_mel_manual_lfl2", Config.CONVERT_MANUAL, 7, 2) {
                listOf(it.melFrame(2, key = true), it.melFrame(2))
            },
            case("p7_mel_manual_lfl3", Config.CONVERT_MANUAL, 7, 3) {
                listOf(it.melFrame(3, key = true), it.melFrame(3))
            },
            // 59 fails, ok, 59 fails, ok: a success resets the consecutive count, so the last RPU
            // converts (no reset, or a threshold under 60, would have abandoned by then).
            case("p7_failure_counter_reset_lfl4", Config.CONVERT_MANUAL, 7, 4) { b ->
                List(59) { b.melFrame(4, failRpu = true, small = true) } + b.melFrame(4, small = true) +
                    List(59) { b.melFrame(4, failRpu = true, small = true) } + b.melFrame(4, small = true)
            },
            // 60 consecutive failures abandon conversion; the 2 good RPUs after are dropped too.
            case("p7_abandon_lfl4", Config.CONVERT_MANUAL, 7, 4) { b ->
                List(60) { b.melFrame(4, failRpu = true, small = true) } + List(2) { b.melFrame(4, small = true) }
            },
            case("p7_zero_length_nal_convert_lfl4", Config.CONVERT_MANUAL, 7, 4) { listOf(it.zeroLengthNalFrame()) },
            case("p7_zero_length_nal_strip_lfl4", Config.STRIP, 7, 4) { listOf(it.zeroLengthNalFrame()) },
            case("p7_truncated_tail_convert_lfl4", Config.CONVERT_MANUAL, 7, 4) {
                listOf(it.truncatedTailFrame(), it.partialLengthFieldFrame())
            },
            case("p7_truncated_tail_strip_h10p_lfl4", Config.STRIP_HDR10P, 7, 4) {
                listOf(it.truncatedTailFrame(), it.partialLengthFieldFrame())
            },
            case("p8_strip_lfl4", Config.STRIP, 8, 4) { listOf(it.p8Frame(key = true), it.p8Frame()) },
            case("p8_convert_inactive_lfl4", Config.CONVERT_AUTO, 8, 4) { listOf(it.p8Frame(key = true)) },
            // DV5 strip waits for base-layer HDR10 static SEI (MDCV here): frame 0 lacks it (kept),
            // frame 1 has it, and the decision then holds for frame 2.
            case("p5_strip_sei_on_second_lfl4", Config.STRIP, 5, 4) {
                listOf(it.p5Frame(key = true), it.p5Frame(mdcv = true), it.p5Frame())
            },
            // Static SEI (CLLI only) on the 15th sample: still inside the window, stripped from there.
            case("p5_strip_sei_at_15th_lfl4", Config.STRIP, 5, 4) { b ->
                List(14) { b.p5Frame(small = true) } + b.p5Frame(clli = true, small = true) + b.p5Frame(small = true)
            },
            // Static SEI (MDCV only) on the 16th sample: the 15-sample window has closed, never stripped.
            case("p5_strip_sei_at_16th_lfl4", Config.STRIP, 5, 4) { b ->
                List(15) { b.p5Frame(small = true) } + b.p5Frame(mdcv = true, small = true)
            },
            case("p5_convert_dv5_lfl4", Config.CONVERT_DV5, 5, 4) {
                listOf(it.p5Frame(mdcv = true, clli = true, key = true))
            },
            case("hevc_hdr10plus_only_lfl4", Config.HDR10P, null, 4) {
                listOf(it.hdr10PlusFrame(4, dv = false, key = true), it.hdr10PlusFrame(4, dv = false))
            },
            // The HDR10+ walk drops a tail shorter than a length field when it strips something.
            case("hevc_hdr10plus_short_tail_lfl4", Config.HDR10P, null, 4) {
                listOf(it.hdr10PlusFrame(4, dv = false) + byteArrayOf(0x00, 0x07))
            },
            // The convert walk keeps (converts) an RPU at nuh_layer_id 1; other layer>0 NALs drop.
            case("p7_layer1_rpu_manual_lfl4", Config.CONVERT_MANUAL, 7, 4) { listOf(it.layer1RpuFrame()) },
            // A failed RPU counts its drop even though the walk then aborts on the short tail.
            case("p7_failed_rpu_then_abort_lfl4", Config.CONVERT_MANUAL, 7, 4) {
                listOf(it.melFrame(4, failRpu = true, small = true) + byteArrayOf(0x00, 0x07))
            },
            // 1-byte NAL: the strip walk reads its layer id from the next byte (the following
            // length field, 0x64 -> layer 12 -> dropped); the convert walk takes layer 0 (kept).
            case("p7_one_byte_nal_strip_lfl1", Config.STRIP, 7, 1) { listOf(it.oneByteNalFrame()) },
            case("p7_one_byte_nal_convert_lfl1", Config.CONVERT_MANUAL, 7, 1) { listOf(it.oneByteNalFrame()) },
            // Seeded random NAL mixes (Spec 21 N3 review): malformed, top-bit and past-end lengths,
            // short tails, 0/1/2-byte NALs, mixed SEI, several RPUs per sample, every walk and lfl.
            case("random_convert_manual_h10p_lfl2", Config.CONVERT_MANUAL_HDR10P, 7, 2) { b -> List(40) { b.randomFrame(2) } },
            case("random_convert_auto_lfl1", Config.CONVERT_AUTO, 7, 1) { b -> List(40) { b.randomFrame(1) } },
            case("random_strip_h10p_lfl4", Config.STRIP_HDR10P, 7, 4) { b -> List(40) { b.randomFrame(4) } },
            case("random_strip_p5_lfl4", Config.STRIP, 5, 4) { b -> List(20) { b.randomFrame(4) } },
            case("random_hdr10p_lfl3", Config.HDR10P, null, 3) { b -> List(30) { b.randomFrame(3) } },
            // 7 RPUs per sample, all failing first: the abandon threshold (60) is crossed mid-sample.
            case("random_abandon_multi_rpu_lfl3", Config.CONVERT_MANUAL, 7, 3) { b ->
                List(10) { b.randomFrame(3, rpus = 7, failPct = 100) } + List(4) { b.randomFrame(3, rpus = 7, failPct = 30) }
            },
        )
    }

    private fun timingFrame(sliceBytes: Int, elBytes: Int): ByteArray {
        val c = CorpusBuilder(1)
        return c.lengthDelimited(4, buildList {
            add(c.nal(TYPE_VPS, 0, 24)); add(c.nal(TYPE_SPS, 0, 64)); add(c.nal(TYPE_PPS, 0, 16))
            repeat(4) { add(c.nal(TYPE_IDR, 0, sliceBytes)) }
            add(c.nal(TYPE_TRAIL, 1, elBytes))
            add(c.rpu(400))
        })
    }

    internal companion object {
        const val MANIFEST_HEADER = "name\tconfig\tprofile\tlfl\tframes\trpu_drops\n"
        const val TYPE_TRAIL = 1
        const val TYPE_IDR = 19
        const val TYPE_VPS = 32
        const val TYPE_SPS = 33
        const val TYPE_PPS = 34
        const val TYPE_AUD = 35
        const val TYPE_PREFIX_SEI = 39
        const val TYPE_SUFFIX_SEI = 40
        const val TYPE_RPU = 62
        const val TYPE_UNSPEC63 = 63
        const val RPU_OK_MARKER = 0x19
        const val RPU_FAIL_MARKER = 0xFF

        fun transformer(
            config: DolbyVisionConversionConfig,
            stripRpuOnly: Boolean = false,
            stripHdr10Plus: Boolean = false,
        ) = DolbyVisionMatroskaTransformer(
            config = config,
            stripRpuOnly = stripRpuOnly,
            stripHdr10PlusSei = stripHdr10Plus,
            rpuConverter = FakeRpuConverter,
        )

        /** dvcC record: [2] = profile << 1, [3] = level 6 bits. */
        fun configBytes(profile: Int) = ByteArray(24).also {
            it[0] = 0x01
            it[2] = (profile shl 1).toByte()
            it[3] = 0x30
        }

        /** Frames file: per frame a u32 big-endian length, then the bytes. */
        fun encodeFrames(frames: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            for (f in frames) {
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((f.size ushr shift) and 0xFF)
                out.write(f)
            }
            return out.toByteArray()
        }
    }
}

/**
 * The fake converter contract (README "Fake converter"); the native host harness implements the
 * same function. Fails (0) when the NAL is shorter than 3 bytes or byte 2 is 0xFF; otherwise
 * writes the 2-byte header, the payload XOR 0x5A, then one byte = mode (len + 1 bytes).
 */
internal object FakeRpuConverter : RpuConverter {
    override fun convert(sample: ByteArray, offset: Int, len: Int, out: DoviBridge.OutBuffer, mode: Int): Int {
        if (len < 3 || (sample[offset + 2].toInt() and 0xFF) == 0xFF) return 0
        out.ensureCapacity(len + 1)
        val bytes = out.bytes
        bytes[0] = sample[offset]
        bytes[1] = sample[offset + 1]
        for (i in 2 until len) bytes[i] = (sample[offset + i].toInt() xor 0x5A).toByte()
        bytes[len] = mode.toByte()
        return len + 1
    }
}

/** Deterministic synthetic HEVC/DV NALs (xorshift32 payloads, fixed seed per builder). */
@UnstableApi
private class CorpusBuilder(seed: Int) {
    private var state = if (seed == 0) 0x2545F491 else seed

    private fun nextByte(): Int {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        return state and 0xFF
    }

    fun nal(type: Int, layerId: Int, totalSize: Int): ByteArray {
        val out = ByteArray(totalSize)
        out[0] = (((type and 0x3F) shl 1) or ((layerId shr 5) and 0x01)).toByte()
        out[1] = (((layerId and 0x1F) shl 3) or 0x01).toByte()
        for (i in 2 until totalSize) out[i] = nextByte().toByte()
        return out
    }

    fun rpu(totalSize: Int, fail: Boolean = false, layerId: Int = 0): ByteArray =
        nal(DolbyVisionGoldenCorpusTest.TYPE_RPU, layerId, totalSize)
        .also { it[2] = (if (fail) DolbyVisionGoldenCorpusTest.RPU_FAIL_MARKER else DolbyVisionGoldenCorpusTest.RPU_OK_MARKER).toByte() }

    private fun nextInt(bound: Int): Int =
        ((nextByte() shl 16) or (nextByte() shl 8) or nextByte()) % bound

    private fun chance(pct: Int) = nextInt(100) < pct

    /**
     * Random NAL mix for [lfl] plus [rpus] RPUs at random positions ([failPct]% fail), then
     * optional damage: one bogus length field (top bit set, past the end, or 0), a short tail,
     * or a truncated end.
     */
    fun randomFrame(lfl: Int, rpus: Int = 1, failPct: Int = 20): ByteArray {
        val max = if (lfl == 1) 255 else 600
        val nals = mutableListOf<ByteArray>()
        repeat(1 + nextInt(7)) {
            nals += when (nextInt(10)) {
                0, 1 -> nal(if (chance(30)) DolbyVisionGoldenCorpusTest.TYPE_IDR else DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 3 + nextInt(max - 3))
                2 -> nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1 + nextInt(2), 3 + nextInt(max / 2))
                3 -> nal(DolbyVisionGoldenCorpusTest.TYPE_UNSPEC63, 0, 3 + nextInt(100))
                4, 5 -> sei(
                    if (chance(50)) DolbyVisionGoldenCorpusTest.TYPE_PREFIX_SEI else DolbyVisionGoldenCorpusTest.TYPE_SUFFIX_SEI,
                    List(1 + nextInt(3)) {
                        when {
                            chance(55) -> 4 to hdr10PlusPayload(5 + nextInt(40))
                            chance(30) -> (if (chance(50)) 137 else 144) to ByteArray(4 + nextInt(20)) { (it * 7 + 1).toByte() }
                            else -> nextInt(200) to ByteArray(nextInt(30)) { if (chance(40)) 0 else nextByte().toByte() }
                        }
                    },
                )
                6 -> byteArrayOf((DolbyVisionGoldenCorpusTest.TYPE_TRAIL shl 1).toByte())
                7 -> nal(DolbyVisionGoldenCorpusTest.TYPE_AUD, 0, 2)
                8 -> if (chance(25)) ByteArray(0) else nal(DolbyVisionGoldenCorpusTest.TYPE_SPS, nextInt(2), 3 + nextInt(60))
                else -> rpu(3 + nextInt(if (lfl == 1) 253 else 300), fail = chance(failPct))
            }
        }
        repeat(rpus) {
            nals.add(nextInt(nals.size + 1), rpu(3 + nextInt(if (lfl == 1) 253 else 200), fail = chance(failPct), layerId = if (chance(10)) 1 else 0))
        }
        val bogus = if (chance(12)) nextInt(nals.size) else -1
        val out = ByteArrayOutputStream()
        nals.forEachIndexed { i, n ->
            val len = when {
                i != bogus -> n.size
                lfl == 4 && chance(40) -> n.size or (1 shl 31)
                chance(70) -> n.size + 1 + nextInt(50)
                else -> 0
            }
            for (k in lfl - 1 downTo 0) out.write((len ushr (k * 8)) and 0xFF)
            out.write(n)
        }
        if (lfl > 1 && chance(10)) repeat(1 + nextInt(lfl - 1)) { out.write(nextByte()) }
        val bytes = out.toByteArray()
        return if (chance(6) && bytes.size > 40) bytes.copyOf(bytes.size - 1 - nextInt(30)) else bytes
    }

    fun lengthDelimited(lfl: Int, nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            for (i in lfl - 1 downTo 0) out.write((n.size ushr (i * 8)) and 0xFF)
            out.write(n)
        }
        return out.toByteArray()
    }

    private fun parameterSets() = listOf(
        nal(DolbyVisionGoldenCorpusTest.TYPE_VPS, 0, 24),
        nal(DolbyVisionGoldenCorpusTest.TYPE_SPS, 0, 48),
        nal(DolbyVisionGoldenCorpusTest.TYPE_PPS, 0, 12),
    )

    /** DV7 MEL-style: base slice + layer-1 EL slice + RPU. */
    fun melFrame(lfl: Int, key: Boolean = false, failRpu: Boolean = false, small: Boolean = false): ByteArray {
        val slice = if (small || lfl == 1) 120 else 900
        return lengthDelimited(lfl, buildList {
            add(nal(DolbyVisionGoldenCorpusTest.TYPE_AUD, 0, 3))
            if (key) addAll(parameterSets())
            add(nal(if (key) DolbyVisionGoldenCorpusTest.TYPE_IDR else DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, slice))
            add(nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, slice / 3))
            add(rpu(if (small || lfl == 1) 40 else 180, fail = failRpu))
        })
    }

    /** DV7 FEL single-track remux style: EL in unspec63 wrappers at layer 0, plus a layer-2 NAL. */
    fun felUnspec63Frame(lfl: Int, key: Boolean = false): ByteArray = lengthDelimited(lfl, buildList {
        if (key) addAll(parameterSets())
        add(nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 700))
        add(nal(DolbyVisionGoldenCorpusTest.TYPE_UNSPEC63, 0, 300))
        add(nal(DolbyVisionGoldenCorpusTest.TYPE_UNSPEC63, 0, 64))
        add(nal(DolbyVisionGoldenCorpusTest.TYPE_SPS, 2, 20))
        add(rpu(220))
    })

    /** HDR10+ SEI only, mixed SEI (HDR10+ + unregistered, emulation-prevented), suffix SEI. */
    fun hdr10PlusFrame(lfl: Int, dv: Boolean, key: Boolean = false): ByteArray = lengthDelimited(lfl, buildList {
        if (key) addAll(parameterSets())
        add(sei(DolbyVisionGoldenCorpusTest.TYPE_PREFIX_SEI, listOf(4 to hdr10PlusPayload(40))))
        add(sei(DolbyVisionGoldenCorpusTest.TYPE_PREFIX_SEI, listOf(
            5 to ByteArray(20) { if (it in 4..6) (if (it == 6) 1 else 0).toByte() else (0x40 + it).toByte() },
            4 to hdr10PlusPayload(24),
        )))
        add(nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 600))
        if (dv) {
            add(nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 200))
            add(rpu(160))
        }
        add(sei(DolbyVisionGoldenCorpusTest.TYPE_SUFFIX_SEI, listOf(4 to hdr10PlusPayload(32))))
    })

    fun layer1RpuFrame(): ByteArray = lengthDelimited(4, listOf(
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 300),
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 90),
        rpu(70, layerId = 1),
        rpu(60),
    ))

    /** lfl 1: base slice, a 1-byte NAL (header byte only), a 100-byte NAL, EL, RPU. */
    fun oneByteNalFrame(): ByteArray = lengthDelimited(1, listOf(
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 80),
        byteArrayOf((DolbyVisionGoldenCorpusTest.TYPE_TRAIL shl 1).toByte()),
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 100),
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 30),
        rpu(40),
    ))

    fun rpuOverflowFrame(): ByteArray = lengthDelimited(1, listOf(
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 200),
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 60),
        rpu(255),
    ))

    /** A zero-length NAL between base slice and RPU. */
    fun zeroLengthNalFrame(): ByteArray = lengthDelimited(4, listOf(
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 400),
        ByteArray(0),
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 100),
        rpu(120),
    ))

    /** Last NAL's length field exceeds the remaining bytes. */
    fun truncatedTailFrame(): ByteArray {
        val whole = lengthDelimited(4, listOf(
            nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 300),
            nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 80),
            rpu(100),
        ))
        return whole.copyOf(whole.size - 30)
    }

    /** Two trailing bytes after the last NAL, shorter than a length field. */
    fun partialLengthFieldFrame(): ByteArray = lengthDelimited(4, listOf(
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 300),
        nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 1, 80),
        rpu(100),
    )) + byteArrayOf(0x00, 0x07)

    fun p8Frame(key: Boolean = false): ByteArray = lengthDelimited(4, buildList {
        if (key) addAll(parameterSets())
        add(nal(if (key) DolbyVisionGoldenCorpusTest.TYPE_IDR else DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, 800))
        add(rpu(200))
    })

    /** DV5: base + RPU, optionally with base-layer MDCV and/or CLLI SEI. */
    fun p5Frame(
        mdcv: Boolean = false,
        clli: Boolean = false,
        key: Boolean = false,
        small: Boolean = false,
    ): ByteArray = lengthDelimited(4, buildList {
        if (key) addAll(parameterSets())
        if (mdcv) add(sei(DolbyVisionGoldenCorpusTest.TYPE_PREFIX_SEI, listOf(137 to ByteArray(24) { (it * 7 + 1).toByte() })))
        if (clli) add(sei(DolbyVisionGoldenCorpusTest.TYPE_PREFIX_SEI, listOf(144 to byteArrayOf(0x03, 0xE8.toByte(), 0x01, 0x90.toByte()))))
        add(nal(DolbyVisionGoldenCorpusTest.TYPE_TRAIL, 0, if (small) 100 else 700))
        add(rpu(if (small) 40 else 150))
    })

    private fun hdr10PlusPayload(size: Int): ByteArray {
        val sig = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01)
        // Two zero runs in the body force emulation prevention when escaped.
        return ByteArray(size) { i ->
            when {
                i < sig.size -> sig[i]
                i in 10..12 -> (if (i == 12) 2 else 0).toByte()
                else -> (0x10 + i).toByte()
            }
        }
    }

    /** SEI NAL from (payloadType, payload) messages: ff-coded type/size, stop bit, then escaped. */
    private fun sei(nalType: Int, messages: List<Pair<Int, ByteArray>>): ByteArray {
        val rbsp = ByteArrayOutputStream()
        for ((type, payload) in messages) {
            var t = type
            while (t >= 0xFF) { rbsp.write(0xFF); t -= 0xFF }
            rbsp.write(t)
            var s = payload.size
            while (s >= 0xFF) { rbsp.write(0xFF); s -= 0xFF }
            rbsp.write(s)
            rbsp.write(payload)
        }
        rbsp.write(0x80)
        val header = nal(nalType, 0, 2)
        val out = ByteArrayOutputStream()
        out.write(header)
        var zeros = 0
        for (b in rbsp.toByteArray()) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}
