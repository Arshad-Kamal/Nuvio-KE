package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

// Marquee loading skeletons (Home states, Detail loading): static `skeletonFill` bars drawn in
// one pass, positioned in dp from the start edge (mirrored in RTL), no shimmer.

/** A skeleton bar at ([x], [y]) from the top-start corner. */
internal fun DrawScope.marqueeSkeletonBar(fill: Color, x: Dp, y: Dp, width: Dp, height: Dp, radius: Dp) {
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - (x + width).toPx() else x.toPx()
    drawRoundRect(fill, Offset(left, y.toPx()), Size(width.toPx(), height.toPx()), CornerRadius(radius.toPx()))
}

/** A [bar] standing in for one text line: centred in the line box at [lineTop], [lineHeight] tall. */
internal fun DrawScope.marqueeSkeletonTextBar(
    fill: Color,
    x: Dp,
    lineTop: Dp,
    lineHeight: Dp,
    bar: DpSize,
    radius: Dp
) = marqueeSkeletonBar(fill, x, lineTop + (lineHeight - bar.height) / 2, bar.width, bar.height, radius)

/** The text-line bars' corner radius (person and studio loading). */
internal val MARQUEE_SKELETON_BAR_RADIUS = 4.dp

/** A skeleton [bar] centred in a [lineHeight] text line box, for skeletons laid out in a Column. */
@Composable
internal fun MarqueeSkeletonLine(lineHeight: Dp, bar: DpSize, radius: Dp = MARQUEE_SKELETON_BAR_RADIUS) {
    Box(modifier = Modifier.height(lineHeight), contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .size(bar)
                .background(NuvioTheme.marquee.skeletonFill, RoundedCornerShape(radius))
        )
    }
}
