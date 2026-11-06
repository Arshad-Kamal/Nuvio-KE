package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Spec 21 N4: the transformer's Kotlin glue around the native MKV pipeline, driven by a scripted
 * [MkvNativePipeline] (the JNI call itself cannot run on the JVM; its core is covered by
 * scripts/test-dv-native.sh): output selection, failure-state round trip, stats and conversion
 * counters, and the LinkageError fallback.
 */
@UnstableApi
class DolbyVisionMkvNativeGlueTest {

    private class Call(val ops: Int, val mode: Int, val threshold: Int, val stateIn: IntArray)

    private val calls = mutableListOf<Call>()

    /** Each call records its inputs, then runs the next scripted step. */
    private fun pipeline(vararg steps: (DoviBridge.OutBuffer, IntArray) -> Int) =
        MkvNativePipeline { _, _, _, out, ops, mode, threshold, state ->
            calls += Call(ops, mode, threshold, state.copyOf())
            steps[calls.size - 1](out, state)
        }

    private fun transformer(pipeline: MkvNativePipeline?) = DolbyVisionMatroskaTransformer(
        config = DolbyVisionConversionConfig(active = true, manualDv81 = true),
        rpuConverter = FakeRpuConverter,
        nativePipeline = pipeline,
    )

    private val dvcC = DolbyVisionGoldenCorpusTest.configBytes(7)

    // lfl 4: base slice, layer-1 EL slice, RPU (converts under the fake).
    private val frame = ByteArrayOutputStream().apply {
        for (nal in listOf(
            byteArrayOf(0x02, 0x01, 0x11, 0x22, 0x33),
            byteArrayOf(0x02, 0x09, 0x44, 0x55),
            byteArrayOf(0x7C, 0x01, 0x19, 0x66),
        )) {
            write(byteArrayOf(0, 0, 0, nal.size.toByte()))
            write(nal)
        }
    }.toByteArray()

    private fun DolbyVisionMatroskaTransformer.run(sample: ByteArray = frame): ByteArray =
        transformHevcSample(sample, sample.size, 4, dvcC)!!.copyOf(lastTransformedSampleLength())

    @Before
    fun reset() {
        DolbyVisionConversionStats.reset()
        DoviBridge.resetRuntimeCounters()
    }

    @Test
    fun `ops, mode and threshold follow the policy`() {
        transformer(pipeline({ _, _ -> 0 })).run()
        assertEquals(DoviBridge.MKV_OP_CONVERT, calls.single().ops)
        assertEquals(2, calls.single().mode)
        assertEquals(60, calls.single().threshold)
    }

    @Test
    fun `unchanged keeps the input and its length`() {
        val t = transformer(pipeline({ _, _ -> 0 }))
        val result = t.transformHevcSample(frame, frame.size, 4, dvcC)
        assertSame(frame, result)
        assertEquals(frame.size, t.lastTransformedSampleLength())
    }

    @Test
    fun `changed output is the out buffer at the native length, 0 bytes included`() {
        val t = transformer(pipeline(
            { out, state ->
                out.ensureCapacity(3)
                byteArrayOf(7, 8, 9).copyInto(out.bytes)
                state[DoviBridge.MKV_STATE_FLAGS] = DoviBridge.MKV_FLAG_CHANGED
                3
            },
            { _, state -> state[DoviBridge.MKV_STATE_FLAGS] = DoviBridge.MKV_FLAG_CHANGED; 0 },
        ))
        assertArrayEquals(byteArrayOf(7, 8, 9), t.run())
        assertEquals(0, t.run().size)
    }

    @Test
    fun `failure state round-trips and folds into drop stats and conversion counters`() {
        val t = transformer(pipeline(
            { _, s ->
                s[DoviBridge.MKV_STATE_CONSECUTIVE_FAILURES] = 60
                s[DoviBridge.MKV_STATE_ABANDONED] = 1
                s[DoviBridge.MKV_STATE_DROPS] = 60
                s[DoviBridge.MKV_STATE_CONVERT_CALLS] = 61
                s[DoviBridge.MKV_STATE_CONVERTED] = 1
                0
            },
            { _, s ->
                s[DoviBridge.MKV_STATE_CONSECUTIVE_FAILURES] = 62
                s[DoviBridge.MKV_STATE_ABANDONED] = 1
                s[DoviBridge.MKV_STATE_DROPS] = 2
                0
            },
            { _, _ -> 0 },
        ))
        t.run(); t.run(); t.run()
        assertEquals(listOf(0, 0), calls[0].stateIn.take(2))
        assertEquals(listOf(60, 1), calls[1].stateIn.take(2))
        assertEquals(listOf(62, 1), calls[2].stateIn.take(2))
        // Outputs are zeroed before every call, so a step that leaves them alone adds nothing.
        assertEquals(listOf(0, 0, 0, 0), calls[2].stateIn.drop(2))
        assertEquals(62L, DolbyVisionConversionStats.getRpuDropCount())
        assertEquals(61L, DoviBridge.getConversionCallCount())
        assertEquals(1L, DoviBridge.getConversionSuccessCount())
    }

    @Test
    fun `a missing native entry point falls back to the Kotlin path for the rest of the stream`() {
        val kotlinOnly = transformer(null)
        val expected = listOf(kotlinOnly.run().toList(), kotlinOnly.run().toList())
        val t = transformer(pipeline({ _, _ -> throw UnsatisfiedLinkError("no entry") }))
        assertEquals(expected, listOf(t.run().toList(), t.run().toList()))
        assertEquals(1, calls.size)
    }
}
