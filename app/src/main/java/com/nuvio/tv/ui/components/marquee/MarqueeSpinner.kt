package com.nuvio.tv.ui.components.marquee

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * [size] spinner (default `marquee.spinnerSize`: Detail source line, Stream chips; the loading
 * hand-off passes its own): a [trackColor] ring under a [arcColor] quarter arc; the angle is read
 * in the draw phase.
 */
@Composable
internal fun MarqueeSpinner(
    modifier: Modifier = Modifier,
    size: Dp = NuvioTheme.marquee.spinnerSize,
    trackColor: Color = NuvioPrimitives.white.copy(alpha = 0.18f),
    arcColor: Color = NuvioPrimitives.white.copy(alpha = 0.72f)
) {
    val marquee = NuvioTheme.marquee
    val transition = rememberInfiniteTransition(label = "marqueeSpinner")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "marqueeSpinnerAngle"
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = marquee.spinnerStroke.toPx())
        drawArc(color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
        drawArc(color = arcColor, startAngle = angle.value, sweepAngle = 90f, useCenter = false, style = stroke)
    }
}
