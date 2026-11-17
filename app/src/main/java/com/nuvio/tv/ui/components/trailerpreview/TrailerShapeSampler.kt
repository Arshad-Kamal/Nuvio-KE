package com.nuvio.tv.ui.components.trailerpreview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView

/**
 * One preview's bar sampling (Spec 123 C4), driven from the overlay's progress tick on the main thread: copies the
 * player's latest frame small into one reused bitmap, detects its bars and feeds the [TrailerShapeDecider]; once
 * decided it reports the shape to the machine (trace, memory) and to [onShape].
 *
 * The copy is a [PixelCopy] from a Surface on the player's SurfaceTexture, requested on a background thread (Spec 128
 * D5, F1): PixelCopy, like [TextureView.getBitmap], waits for the RenderThread, which on the Shield's Search screen
 * held the main thread 4-23 ms per sample. Only getPixels + the scan run on the main thread; that is what the
 * decider's budget counts. One copy is in flight at a time, so the bitmap is never read while it is written.
 */
internal class TrailerShapeSampler(
    private val machine: TrailerPreviewMachine,
    private val videoUrl: String,
    startPositionMs: Long,
    // The frame's own display aspect; 0 until reported (no sample before it).
    private val frameAspect: () -> Float,
    private val onShape: (Float) -> Unit
) {
    /** The player's TextureView while it exists (TrailerPlayer's onVideoSurface). */
    var surface: TextureView? = null

    private val decider = TrailerShapeDecider(startPositionMs)
    private val main = Handler(Looper.getMainLooper())
    // Never recycled: a copy may still be writing it when the preview leaves; the GC frees it (192 x 216 ARGB).
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(TRAILER_SAMPLE_WIDTH * TRAILER_SAMPLE_HEIGHT)
    // The previous sample's pixels: a pixel-identical repeat (a frozen texture) is invalid.
    private var previous: IntArray? = null
    private var inFlight = false
    private var released = false
    // The latest tick's deferral: a copy that comes back while the panel grows is dropped, not scanned.
    private var deferredNow = false
    // Made at the first copy.
    private var copier: Copier? = null

    /** The progress tick at playback [positionMs]; [deferred] while the panel grows (a sample would cost a frame). */
    fun onPosition(positionMs: Long, deferred: Boolean) {
        deferredNow = deferred
        val frame = frameAspect()
        if (released || inFlight || deferred || frame <= 0f || !decider.isDue(positionMs)) return
        val copy = bitmap ?: Bitmap.createBitmap(TRAILER_SAMPLE_WIDTH, TRAILER_SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
            .also { bitmap = it }
        // No surface yet: a missed copy, read as a dark (invalid) sample.
        val texture = surface?.takeIf { it.isAvailable }?.surfaceTexture
            ?: return onCopied(positionMs, frame, copied = false, copyMs = 0f)
        inFlight = true
        (copier ?: Copier().also { copier = it }).copy(texture, copy) { copied, copyMs -> main.post { onCopied(positionMs, frame, copied, copyMs) } }
    }

    /** Stops sampling and ends the copy thread; called once decided and when the preview's frame leaves. */
    fun release() {
        if (released) return
        released = true
        bitmap = null
        previous = null
        copier?.close()
        copier = null
    }

    /**
     * On the main thread: one sample's copy came back ([copied] false = missed: a dark, invalid sample). One that comes
     * back while the panel grows is dropped uncounted; the decider still has it due, so a later tick samples again.
     */
    private fun onCopied(positionMs: Long, frame: Float, copied: Boolean, copyMs: Float) {
        inFlight = false
        val copy = bitmap ?: return // released
        if (deferredNow) return
        val start = System.nanoTime()
        if (copied) {
            copy.getPixels(pixels, 0, TRAILER_SAMPLE_WIDTH, 0, 0, TRAILER_SAMPLE_WIDTH, TRAILER_SAMPLE_HEIGHT)
        } else {
            pixels.fill(Color.BLACK)
        }
        val aspect = trailerSampleAspect(pixels, previous, TRAILER_SAMPLE_WIDTH, TRAILER_SAMPLE_HEIGHT, frame)
        val last = previous ?: IntArray(pixels.size)
        previous = pixels
        pixels = last
        val mainMs = (System.nanoTime() - start) / NANOS_PER_MS
        machine.onShapeSample(copyMs, mainMs, aspect)
        decider.onSample(positionMs, aspect, mainMs, frame)?.let { shape ->
            machine.onShapeDecided(videoUrl, shape)
            onShape(shape.aspect)
            release()
        }
    }

    /**
     * The copy thread. Its fields are touched only on that thread. PixelCopy reports through [handler] after request
     * returns, on every API level (before API 34 the request itself blocks here until the readback ends), so [close]
     * waits for a copy in flight to report before it releases the Surface and ends the thread.
     */
    private class Copier {
        private val thread = HandlerThread("TrailerShapeCopy").apply { start() }
        private val handler = Handler(thread.looper)
        private var source: Surface? = null
        private var sourceTexture: SurfaceTexture? = null
        private var pending = false
        private var closing = false

        /** Copies [texture]'s latest frame into [into], scaled; [done] gets success and the copy's wall time. */
        fun copy(texture: SurfaceTexture, into: Bitmap, done: (Boolean, Float) -> Unit) {
            handler.post { copyNow(texture, into, done, System.nanoTime()) }
        }

        fun close() {
            handler.post {
                closing = true
                if (!pending) shutDown()
            }
        }

        private fun copyNow(texture: SurfaceTexture, into: Bitmap, done: (Boolean, Float) -> Unit, start: Long) {
            // A released SurfaceTexture throws (the Surface constructor or PixelCopy) instead of returning an error.
            val from = try {
                sourceFor(texture).takeIf { it.isValid }
            } catch (e: RuntimeException) {
                Log.w(TAG, "copy source unusable: ${e.javaClass.simpleName}")
                null
            } ?: return finish(done, start, copied = false)
            pending = true
            try {
                PixelCopy.request(from, into, { result ->
                    if (result != PixelCopy.SUCCESS) Log.w(TAG, "copy failed: result $result")
                    finish(done, start, copied = result == PixelCopy.SUCCESS)
                }, handler)
            } catch (e: RuntimeException) {
                Log.w(TAG, "copy refused: ${e.javaClass.simpleName}")
                finish(done, start, copied = false)
            }
        }

        private fun finish(done: (Boolean, Float) -> Unit, start: Long, copied: Boolean) {
            pending = false
            done(copied, (System.nanoTime() - start) / NANOS_PER_MS)
            if (closing) shutDown()
        }

        private fun sourceFor(texture: SurfaceTexture): Surface {
            source?.takeIf { sourceTexture === texture }?.let { return it }
            source?.release()
            source = null
            sourceTexture = texture
            return Surface(texture).also { source = it }
        }

        private fun shutDown() {
            source?.release()
            source = null
            sourceTexture = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000f
        const val TAG = "TrailerShapeSampler"
    }
}
