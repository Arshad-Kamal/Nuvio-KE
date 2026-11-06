package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The loading indicator (Spec 49 D16, sandbox spec-49-spinner-and-imdb-label v2): the house ring
 * [com.nuvio.tv.ui.components.marquee.MarqueeSpinner] draws elsewhere, a [color] quarter arc over
 * a 25% track turning every 0.9 s, drawn inside its box: a size the caller sets on [modifier]
 * wins, else 48 dp. The ring's outer edge spans two thirds of the box, the stroke a sixteenth
 * (32 dp and 3 dp at 48). Replaced a LottieFiles spinner whose licence could not ship in the
 * public build; that one always drew at 48 dp, whatever box it was given.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = NuvioTheme.colors.TextSecondary
) {
    val transition = rememberInfiniteTransition(label = "loadingIndicator")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "loadingIndicatorAngle"
    )
    val track = color.copy(alpha = color.alpha * 0.25f)
    Canvas(modifier = modifier.size(NuvioTheme.spacing.xxxl)) {
        val strokeWidth = size.minDimension / 16f
        // A stroke straddles its path: inset the path by half a stroke so the outer edge is 2/3.
        val diameter = size.minDimension * 2f / 3f - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokeWidth)
        drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        drawArc(color = color, startAngle = angle.value, sweepAngle = 90f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }
}
