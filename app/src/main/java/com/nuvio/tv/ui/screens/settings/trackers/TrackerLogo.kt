package com.nuvio.tv.ui.screens.settings.trackers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** A tracker's mark at the sizes the sandbox draws it (`.s104g`): hub row, sign-in eyebrow, account panel head, Library label. */
internal enum class TrackerLogoSize(val size: Dp, val radius: Dp) {
    ROW(26.dp, 7.dp),
    EYEBROW(28.dp, 7.dp),
    PANEL(40.dp, 10.dp),
    LABEL(16.dp, 4.dp),
    /** Home catalog order's MDBList rows (Spec 105 mockup v3 D2). */
    LIST(22.dp, 6.dp)
}

/**
 * The tracker's glyph ([logoRes], Specs 105/106 ship them), or its initial on a tile while a tracker has none.
 * Decorative: the name is always written beside it.
 */
@Composable
internal fun TrackerLogo(@DrawableRes logoRes: Int?, name: String, size: TrackerLogoSize, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size.radius)
    if (logoRes != null) {
        Image(
            painter = painterResource(logoRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size.size).clip(shape)
        )
    } else {
        Box(
            modifier = modifier.size(size.size).background(NuvioPrimitives.neutral750, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = NuvioTheme.textStyles.button.copy(
                    fontSize = (size.size.value * 0.5f).sp,
                    lineHeight = (size.size.value * 0.5f).sp
                ),
                color = NuvioPrimitives.white
            )
        }
    }
}
