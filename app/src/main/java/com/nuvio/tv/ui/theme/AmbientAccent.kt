package com.nuvio.tv.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Marquee ambient accent: one colour per title, taken from its artwork, for eyebrows, progress
 * fills and the focus glow (docs/design/marquee-design-system-2026-10-06.md, "Decided at the
 * Home slice" 1-2). A null result (the art has no usable swatch, or the bitmap is HARDWARE)
 * means callers use `NuvioTheme.colors.Secondary`. Focus ring and glass never use this colour.
 */
object AmbientAccent {
    // HSL targets that keep the accent readable as 11sp text on the black scrim.
    internal const val LIGHTNESS = 0.64f
    internal const val MIN_SATURATION = 0.60f

    /**
     * Runs Palette over [bitmap]. CPU-bound: call it off the main thread. The bitmap must be a
     * software bitmap (decode with Coil `allowHardware(false)`): Palette reads pixels, which a
     * HARDWARE bitmap refuses, so a HARDWARE bitmap returns null instead of crashing.
     */
    fun fromBitmap(bitmap: Bitmap): Color? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            return null
        }
        val palette = Palette.from(bitmap).generate()
        return fromSwatches(
            vibrant = palette.vibrantSwatch?.rgb,
            darkVibrant = palette.darkVibrantSwatch?.rgb,
            lightVibrant = palette.lightVibrantSwatch?.rgb
        )
    }

    /** First present swatch of Vibrant -> DarkVibrant -> LightVibrant, normalised; null when none. */
    internal fun fromSwatches(vibrant: Int?, darkVibrant: Int?, lightVibrant: Int?): Color? {
        val rgb = vibrant ?: darkVibrant ?: lightVibrant ?: return null
        return normalise(rgb)
    }

    /** Keeps the swatch's hue, lifts saturation to at least [MIN_SATURATION], fixes lightness. */
    internal fun normalise(rgb: Int): Color {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val lightness = (maxC + minC) / 2f
        val saturation = if (delta == 0f) 0f else delta / (1f - abs(2f * lightness - 1f))
        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta).mod(6f))
            maxC == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Color.hsl(
            hue = hue,
            saturation = max(saturation, MIN_SATURATION).coerceAtMost(1f),
            lightness = LIGHTNESS
        )
    }
}
