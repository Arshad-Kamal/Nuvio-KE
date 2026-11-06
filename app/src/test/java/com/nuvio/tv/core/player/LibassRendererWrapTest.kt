package com.nuvio.tv.core.player

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.render.AssRenderer
import io.github.peerless2012.ass.media.type.AssRenderType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 36/S3 (B2-24): upstream test port. */
class LibassRendererWrapTest {

    private class ShiftingRenderer(base: Renderer, private val shiftUs: Long) : ForwardingRenderer(base) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            super.render(positionUs + shiftUs, elapsedRealtimeUs)
        }
    }

    private fun createRenderers(factory: RenderersFactory): Array<Renderer> =
        factory.createRenderers(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

    @Test
    fun `the libass renderer is wrapped and the other renderers are left alone`() {
        val other = mockk<Renderer>(relaxed = true)
        val base = RenderersFactory { _, _, _, _, _ -> arrayOf(other) }
        val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)

        val renderers = createRenderers(
            base.withAssSupport(handler).wrapAssRenderer { ShiftingRenderer(it, 0L) }
        )

        assertEquals(2, renderers.size)
        assertSame(other, renderers[0])
        assertTrue(renderers[1] is ShiftingRenderer)
    }

    @Test
    fun `libass gets the shifted position from the wrapper`() {
        val base = RenderersFactory { _, _, _, _, _ -> emptyArray() }
        val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
        val renderers = createRenderers(
            // Production wrapper: a +2 s subtitle delay shows subtitles 2 s later (libass sees 8 s at 10 s).
            base.withAssSupport(handler).wrapAssRenderer { SubtitleOffsetRenderer(it, { 2_000_000L }, { 0L }) }
        )

        renderers.single().render(1_000_000_000_000L + 10_000_000L, 0L)

        assertEquals(8_000_000L, handler.videoTime)
    }

    @Test
    fun `without a wrapper libass keeps the player position`() {
        val base = RenderersFactory { _, _, _, _, _ -> emptyArray() }
        val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
        val renderers = createRenderers(base.withAssSupport(handler).wrapAssRenderer { it })

        assertTrue(renderers.single() is AssRenderer)
        renderers.single().render(1_000_000_000_000L + 10_000_000L, 0L)

        assertEquals(10_000_000L, handler.videoTime)
    }
}
