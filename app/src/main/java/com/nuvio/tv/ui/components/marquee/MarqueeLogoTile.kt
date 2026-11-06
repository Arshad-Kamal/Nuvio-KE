package com.nuvio.tv.ui.components.marquee

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The content of a logo tile: a studio / network logo fitted `detail.logoTilePadding` inside a [width]x[height] tile, else
 * [name] in black (no logo, or it failed). Not focusable; the tile's fill and shape belong to the
 * caller (Detail's Studios row: a focusable white card). [tintDarkMonochrome] (the studio page
 * header, Spec 29 Q7): once the logo loads, a dark monochrome one is drawn white for a page without
 * the tile; [onTiled] reports whether the tile is wanted (false only for that white logo).
 */
@Composable
internal fun MarqueeLogoTile(
    logo: String?,
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    tintDarkMonochrome: Boolean = false,
    onTiled: (Boolean) -> Unit = {}
) {
    val padding = NuvioTheme.marquee.detail.logoTilePadding
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoWidthPx = with(density) { (width - padding * 2).roundToPx() }
    val logoHeightPx = with(density) { (height - padding * 2).roundToPx() }
    var logoFailed by remember(logo) { mutableStateOf(false) }
    var tint by remember(logo) { mutableStateOf<ColorFilter?>(null) }
    val logoModel = remember(context, logo, logoWidthPx, logoHeightPx, tintDarkMonochrome) {
        logo?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = logoWidthPx, height = logoHeightPx)
                // The tint check reads the decoded pixels.
                .apply { if (tintDarkMonochrome) allowHardware(false) }
                .build()
        }
    }
    Box(
        modifier = modifier
            .size(width, height)
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        if (logoModel != null && !logoFailed) {
            AsyncImage(
                model = logoModel,
                contentDescription = name,
                onSuccess = { success ->
                    val dark = tintDarkMonochrome &&
                        (success.result.image as? BitmapImage)?.bitmap?.let(::isLogoDarkAndMonochrome) == true
                    tint = if (dark) ColorFilter.tint(NuvioPrimitives.white, BlendMode.SrcIn) else null
                    onTiled(!dark)
                },
                onError = {
                    logoFailed = true
                    onTiled(true)
                },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = tint
            )
        } else {
            Text(
                text = name,
                style = NuvioTheme.textStyles.cardLabel,
                color = NuvioPrimitives.black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Samples pixels from a [Bitmap] and returns true only if the non-transparent
 * pixels are both predominantly dark AND low-saturation (grayscale / monochrome).
 * This avoids treating colorful logos (red, blue, etc.) as "black".
 */
internal fun isLogoDarkAndMonochrome(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val step = maxOf(1, minOf(width, height) / 20) // sample ~20×20 grid
    var totalLuminance = 0.0
    var totalSaturation = 0.0
    var count = 0
    val hsv = FloatArray(3)
    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha > 50) { // skip transparent / nearly-transparent pixels
                val r = ((pixel ushr 16) and 0xFF) / 255.0
                val g = ((pixel ushr 8) and 0xFF) / 255.0
                val b = (pixel and 0xFF) / 255.0
                totalLuminance += 0.2126 * r + 0.7152 * g + 0.0722 * b
                android.graphics.Color.colorToHSV(pixel, hsv)
                totalSaturation += hsv[1]
                count++
            }
            y += step
        }
        x += step
    }
    if (count == 0) return false
    val avgLuminance = totalLuminance / count
    val avgSaturation = totalSaturation / count
    // Dark (luminance < 0.3) AND grayscale (saturation < 0.2)
    return avgLuminance < 0.3 && avgSaturation < 0.2
}
