package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.theme.AmbientAccent
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AmbientAccent"

// Palette scales its input to ~112x112 px anyway; a small decode keeps the extra request cheap.
private const val ACCENT_DECODE_WIDTH_PX = 160
private const val ACCENT_DECODE_HEIGHT_PX = 90
// Entries are single colours; sized for the hero accents plus every fitted card's fill on Home.
private const val ACCENT_CACHE_SIZE = 512

/** Marks "this art has no swatch" in the cache (LruCache refuses null values). */
private object NoAccent

/** One accent per artwork URL for the process lifetime of the entry (Marquee §Shield rules). */
private val accentCache = LruCache<String, Any>(ACCENT_CACHE_SIZE)

/**
 * The ambient accent of [imageUrl] (Marquee spec "Decided at the Home slice" 1-2), computed off
 * the main thread from a small software decode and cached per URL. Null for a null URL and when
 * the art has no usable swatch: callers fall back to palette `Secondary`. While a new URL is
 * computing, the previous URL's accent stays (no flash through the fallback colour).
 */
@Composable
fun rememberAmbientAccent(imageUrl: String?): State<Color?> {
    val context = LocalContext.current
    val cached = imageUrl?.let { accentCache.get(it) }
    return produceState(initialValue = cached as? Color, imageUrl) {
        // produceState keeps its state across key changes, so a cache hit must publish itself.
        if (imageUrl.isNullOrBlank() || cached != null) {
            value = cached as? Color
            return@produceState
        }
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(ACCENT_DECODE_WIDTH_PX, ACCENT_DECODE_HEIGHT_PX)
            // Palette reads pixels; a HARDWARE bitmap refuses that.
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: run {
            value = null // Unloadable art: fall back rather than keep the previous title's accent.
            return@produceState
        }
        val accent = withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap()
            // U1c decision (review 2026-10-06-043504): this loader is AmbientAccent's only
            // caller and always decodes software bitmaps, so a HARDWARE bitmap here is a caller
            // bug; debug builds log it, release silently falls back to the palette colour.
            if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE
            ) {
                Log.w(TAG, "HARDWARE bitmap reached AmbientAccent for $imageUrl")
            }
            AmbientAccent.fromBitmap(bitmap)
        }
        accentCache.put(imageUrl, accent ?: NoAccent)
        value = accent
    }
}

/**
 * The ambient accent of [imageUrl] with the palette `Secondary` fallback (decision 2), animated
 * over `fast`. Read it only through the returned provider, in leaves (draw lambdas, the focused
 * card): the caller then never recomposes per tween frame.
 */
@Composable
fun rememberAmbientAccentProvider(imageUrl: String?): () -> Color {
    val ambientAccent by rememberAmbientAccent(imageUrl)
    val accentState = animateColorAsState(
        targetValue = ambientAccent ?: NuvioTheme.colors.Secondary,
        animationSpec = tween(NuvioTheme.motion.durations.fast),
        label = "ambientAccent"
    )
    return remember(accentState) { { accentState.value } }
}
