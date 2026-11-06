package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A title over artwork: the [logo] (fit, start-aligned, in a [logoHeight] x ≤[logoMaxWidth] box
 * and decoded at that box) when it loads, else [title] in [textStyle] on up to two lines.
 */
@Composable
internal fun MarqueeLogoTitle(
    title: String,
    logo: String?,
    logoHeight: Dp,
    logoMaxWidth: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    var logoFailed by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() && !logoFailed }
    if (logoUrl != null) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val logoModel = remember(context, logoUrl, density, logoHeight, logoMaxWidth) {
            with(density) {
                ImageRequest.Builder(context)
                    .data(logoUrl)
                    .crossfade(true)
                    .size(logoMaxWidth.roundToPx(), logoHeight.roundToPx())
                    .build()
            }
        }
        AsyncImage(
            model = logoModel,
            contentDescription = title,
            onError = { logoFailed = true },
            modifier = modifier
                .height(logoHeight)
                .widthIn(max = logoMaxWidth)
                .fillMaxWidth(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart
        )
    } else {
        Text(
            text = title,
            style = textStyle,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
}
