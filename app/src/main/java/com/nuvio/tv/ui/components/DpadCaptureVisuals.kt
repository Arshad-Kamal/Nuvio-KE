package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/** Outcome state of a D-pad capture surface (unlock overlay + setup flow). */
internal enum class DpadCaptureOutcome { Idle, Success, Fail }

/**
 * The fail shake shared by the ring and the slots (Spec 43 d-wrong / s-mismatch): the slot row and
 * the ring read it in their graphicsLayer, so both move together. ±13 decaying over ~400 ms.
 */
@Composable
internal fun rememberDpadFailShake(outcome: DpadCaptureOutcome): Animatable<Float, *> {
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(outcome) {
        if (outcome == DpadCaptureOutcome.Fail) {
            listOf(-13f, 11f, -10f, 9f, -8f, 7f, -5f, 4f, -2f, 0f).forEach { target ->
                shakeX.animateTo(target, tween(40))
            }
        } else {
            shakeX.snapTo(0f)
        }
    }
    return shakeX
}

/**
 * The remote ring (Spec 43, sandbox `u43-profiles-redesign` v4, replaces the padlock): the remote's
 * D-pad drawn as a ring with four chevrons and a centre key reading [centreLabel]. Every press
 * ([pressTick]) lights the WHOLE ring the same way (white edge, halo, a glow in [accent]); nothing
 * drawn depends on the direction. Fail: red at once; success: green with a green glow, faded in.
 *
 * Motion stays on the draw path (W5.3 discipline): pulse and success fade are read only in the draw
 * lambda. The pulse rises eased and falls eased (Spec 43f F2: a snap to full + linear fall read as
 * jank); brushes, the chevron path and the fixed strokes are built once per size in [drawWithCache]
 * (only the edge stroke, whose width animates, is made per frame).
 */
@Composable
internal fun DpadRing(
    pressTick: Int,
    outcome: DpadCaptureOutcome,
    centreLabel: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.profiles
    val idleChevron = NuvioTheme.marquee.onImage2
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pressTick) {
        if (pressTick <= 0) return@LaunchedEffect
        // From the current value, so a quick second press re-brightens without a jump.
        pulse.animateTo(1f, tween(tokens.dpadPulseAttackMs, easing = LinearOutSlowInEasing))
        pulse.animateTo(0f, tween(tokens.dpadPulseDecayMs, easing = FastOutSlowInEasing))
    }
    val successFade = remember { Animatable(0f) }
    LaunchedEffect(outcome) {
        if (outcome == DpadCaptureOutcome.Success) {
            successFade.animateTo(1f, tween(tokens.successFadeMs, easing = LinearOutSlowInEasing))
        } else {
            successFade.snapTo(0f)
        }
    }
    val fail = outcome == DpadCaptureOutcome.Fail
    Box(modifier = modifier.size(tokens.dpadRingSize), contentAlignment = Alignment.Center) {
        Spacer(
            modifier = Modifier
                .size(tokens.dpadRingSize)
                .drawWithCache {
                    val center = size.center
                    val radius = size.minDimension / 2
                    val glowRadius = radius * 1.35f
                    val accentGlow = Brush.radialGradient(listOf(accent, Color.Transparent), center, glowRadius)
                    val successGlow = Brush.radialGradient(
                        listOf(tokens.successOutline, Color.Transparent), center, glowRadius
                    )
                    val haloWidth = tokens.dpadRingHaloWidth.toPx()
                    val haloStroke = Stroke(width = haloWidth)
                    val thin = tokens.boxOutlineWidth.toPx()
                    val thick = tokens.boxActiveOutlineWidth.toPx()
                    val thickStroke = Stroke(width = thick)
                    // Chevron: a 12x6 "^" in a [dpadChevronSize] box [dpadChevronInset] in from the
                    // edge, drawn at the top and turned per side.
                    val box = tokens.dpadChevronSize.toPx()
                    val cy = center.y - (radius - tokens.dpadChevronInset.toPx() - box / 2)
                    val half = box * 0.2f
                    val rise = box * 0.1f
                    val chevron = Path().apply {
                        moveTo(center.x - half, cy + rise)
                        lineTo(center.x, cy - rise)
                        lineTo(center.x + half, cy + rise)
                    }
                    val chevronStroke = Stroke(width = box * 0.08f, cap = StrokeCap.Round)
                    fun DrawScope.chevrons(color: Color) {
                        for (side in 0 until 4) {
                            rotate(degrees = side * 90f, pivot = center) {
                                drawPath(chevron, color = color, style = chevronStroke)
                            }
                        }
                    }
                    onDrawBehind {
                        if (fail) {
                            drawCircle(color = tokens.boxErrorFill, radius = radius, center = center)
                            drawCircle(
                                color = tokens.boxErrorOutline,
                                radius = radius - thick / 2,
                                center = center,
                                style = thickStroke
                            )
                            chevrons(tokens.pinErrorText)
                            return@onDrawBehind
                        }
                        val lit = pulse.value
                        val green = successFade.value
                        val pressAlpha = lit * (1f - green)
                        if (pressAlpha > 0f) {
                            drawCircle(accentGlow, radius = glowRadius, center = center, alpha = 0.8f * pressAlpha)
                        }
                        if (green > 0f) {
                            drawCircle(successGlow, radius = glowRadius, center = center, alpha = 0.5f * green)
                        }
                        if (pressAlpha > 0f) {
                            drawCircle(
                                color = tokens.dpadRingHalo,
                                radius = radius + haloWidth / 2,
                                center = center,
                                alpha = pressAlpha,
                                style = haloStroke
                            )
                        }
                        drawCircle(color = tokens.dpadRingFill, radius = radius, center = center)
                        val pressWidth = thin + (thick - thin) * lit
                        val edgeWidth = pressWidth + (thick - pressWidth) * green
                        drawCircle(
                            color = lerp(lerp(tokens.dpadRingEdge, Color.White, lit), tokens.successOutline, green),
                            radius = radius - edgeWidth / 2,
                            center = center,
                            style = Stroke(width = edgeWidth)
                        )
                        chevrons(lerp(lerp(idleChevron, Color.White, lit), tokens.successText, green))
                    }
                }
        )
        Box(
            modifier = Modifier
                .size(tokens.dpadRingCentreSize)
                .background(tokens.dpadRingCentreFill, CircleShape)
                .border(tokens.boxOutlineWidth, tokens.dpadRingEdge, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = centreLabel,
                style = NuvioTheme.textStyles.chip,
                color = NuvioTheme.marquee.onImage3,
                maxLines = 1
            )
        }
    }
}

/**
 * The combination slots (Spec 43 `.slots`): [slotCount] PIN-style boxes, count-only (never a
 * direction); [filledCount] carry a dot, the next one a white edge while [outcome] is Idle; all turn
 * red on Fail and fade to green on Success.
 */
@Composable
internal fun DpadSlots(
    filledCount: Int,
    slotCount: Int,
    outcome: DpadCaptureOutcome,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.profiles
    val shape = RoundedCornerShape(tokens.dpadSlotRadius)
    val success = outcome == DpadCaptureOutcome.Success
    // The ring's success easing, so ring and slots turn green together.
    val colorFade = tween<Color>(tokens.successFadeMs, easing = LinearOutSlowInEasing)
    val widthFade = tween<Dp>(tokens.successFadeMs, easing = LinearOutSlowInEasing)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(tokens.dpadSlotGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(slotCount) { index ->
            val filled = index < filledCount
            val next = outcome == DpadCaptureOutcome.Idle && index == filledCount
            val look = when {
                outcome == DpadCaptureOutcome.Fail ->
                    SlotLook(tokens.boxActiveOutlineWidth, tokens.boxErrorOutline, tokens.boxErrorFill, tokens.pinErrorText)
                success -> SlotLook(tokens.boxActiveOutlineWidth, tokens.successOutline, tokens.successFill, tokens.successText)
                next -> SlotLook(tokens.boxActiveOutlineWidth, Color.White, tokens.boxActiveFill, Color.White)
                filled -> SlotLook(tokens.boxOutlineWidth, tokens.boxFilledOutline, tokens.boxFilledFill, Color.White)
                else -> SlotLook(tokens.boxOutlineWidth, tokens.dpadSlotOutline, Color.Transparent, Color.White)
            }
            // Success fades in with the ring (Spec 43f F3). Every other state shows its target
            // directly (the snap only keeps the animations' start point current), so it stays
            // instant, in the same frame as the ring.
            val edgeColorFade by animateColorAsState(look.edge, if (success) colorFade else snap(), label = "slotEdge")
            val fillColorFade by animateColorAsState(look.fill, if (success) colorFade else snap(), label = "slotFill")
            val dotColorFade by animateColorAsState(look.dot, if (success) colorFade else snap(), label = "slotDot")
            val edgeWidthFade by animateDpAsState(look.edgeWidth, if (success) widthFade else snap(), label = "slotEdgeWidth")
            val edgeColor = if (success) edgeColorFade else look.edge
            val fillColor = if (success) fillColorFade else look.fill
            val dotColor = if (success) dotColorFade else look.dot
            val edgeWidth = if (success) edgeWidthFade else look.edgeWidth
            Box(
                modifier = Modifier
                    .size(tokens.dpadSlotWidth, tokens.dpadSlotHeight)
                    .clip(shape)
                    .background(fillColor)
                    .border(edgeWidth, edgeColor, shape),
                contentAlignment = Alignment.Center
            ) {
                if (filled) {
                    Box(
                        modifier = Modifier
                            .size(tokens.dpadSlotDot)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }
    }
}

/** One slot's state colours: edge width + colour, fill, dot. */
private data class SlotLook(val edgeWidth: Dp, val edge: Color, val fill: Color, val dot: Color)
