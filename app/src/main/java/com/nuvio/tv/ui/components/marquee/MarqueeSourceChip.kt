package com.nuvio.tv.ui.components.marquee

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Addon filter chip (approved sandbox `u5-stream-screen-marquee` stages 1-5): a `stream.chipHeight`
 * glass pill with the addon [name] and its stream [count] (SUCCESS chips only). Selected = white
 * text + inset white ring; focused = white fill, black text, no scale; LOADING adds a spinner
 * before the name; ERROR = error text, fill and edge, with the legacy chip's shake. [warning]: the
 * stream limit's chip (Spec 51 P6), amber text, fill and edge behind a leading warning icon, an
 * amber ring when selected. Callers put `focusRequester`/`onFocusChanged`/`focusProperties` in
 * [modifier].
 */
@Composable
internal fun MarqueeSourceChip(
    name: String,
    isSelected: Boolean,
    status: SourceChipStatus,
    isSelectable: Boolean,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warning: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val stream = marquee.stream
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val isError = status == SourceChipStatus.ERROR
    val errorShake = rememberChipErrorShake(isError)
    var focused by remember { mutableStateOf(false) }
    val restingText = when {
        isError -> stream.chipErrorText
        warning -> if (isSelected) stream.limitChipSelectedText else stream.limitChipText
        isSelected -> white
        else -> marquee.onImage2
    }
    val restingBorder = when {
        isError -> Border(BorderStroke(EDGE_WIDTH, stream.chipErrorEdge), shape = CircleShape)
        warning && isSelected -> Border(BorderStroke(stream.chipSelectedRing, stream.limitWarn), shape = CircleShape)
        warning -> Border(BorderStroke(EDGE_WIDTH, stream.limitChipEdge), shape = CircleShape)
        isSelected -> Border(BorderStroke(stream.chipSelectedRing, white), shape = CircleShape)
        else -> Border.None
    }
    val restingFill = when {
        isError -> stream.chipErrorFill
        warning -> stream.limitChipFill
        else -> marquee.glassFill
    }
    Button(
        onClick = { if (isSelectable) onClick() },
        modifier = modifier
            .height(stream.chipHeight)
            .chipErrorShake(errorShake)
            .onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = restingFill,
            contentColor = restingText,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        border = ButtonDefaults.border(
            border = restingBorder,
            focusedBorder = Border.None,
            pressedBorder = Border.None
        ),
        contentPadding = PaddingValues(horizontal = stream.chipPadding)
    ) {
        if (status == SourceChipStatus.LOADING) {
            MarqueeSpinner(
                trackColor = if (focused) black.copy(alpha = 0.2f) else white.copy(alpha = 0.25f),
                arcColor = if (focused) black else white
            )
            Spacer(modifier = Modifier.width(stream.chipInnerGap))
        }
        if (warning) {
            // Tinted by the button's content colour: amber at rest, black when focused.
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(stream.limitIconSize)
            )
            Spacer(modifier = Modifier.width(stream.chipInnerGap))
        }
        Text(text = name, style = NuvioTheme.textStyles.cardLabel, maxLines = 1)
        if (count != null && status == SourceChipStatus.SUCCESS) {
            Spacer(modifier = Modifier.width(stream.chipInnerGap))
            Text(
                text = count.toString(),
                style = NuvioTheme.textStyles.caption,
                color = when {
                    focused -> black.copy(alpha = SUFFIX_ALPHA)
                    warning -> stream.limitChipCount
                    else -> marquee.onImage3
                },
                maxLines = 1
            )
        }
    }
}

/** The 1 dp edge of a failed chip and of the limit chip. */
private val EDGE_WIDTH = 1.dp

/** A failed chip's entrance: a brief alpha pulse, then two gentle shakes (applied by [chipErrorShake]). */
@Stable
private class ChipErrorShake {
    val offsetPx = Animatable(0f)
    val alpha = Animatable(1f)
}

/** Plays the [ChipErrorShake] each time [isError] turns true; resets it otherwise. */
@Composable
private fun rememberChipErrorShake(isError: Boolean): ChipErrorShake {
    val shake = remember { ChipErrorShake() }
    LaunchedEffect(isError) {
        shake.offsetPx.snapTo(0f)
        shake.alpha.snapTo(1f)
        if (!isError) return@LaunchedEffect

        shake.alpha.animateTo(
            targetValue = 0.9f,
            animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
        )
        shake.alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
        )

        // Two gentle shakes (less abrupt than the previous infinite shake/blink loop).
        shake.offsetPx.animateTo(3f, tween(80, easing = FastOutSlowInEasing))
        shake.offsetPx.animateTo(-3f, tween(110, easing = FastOutSlowInEasing))
        shake.offsetPx.animateTo(2f, tween(90, easing = FastOutSlowInEasing))
        shake.offsetPx.animateTo(-2f, tween(90, easing = FastOutSlowInEasing))
        shake.offsetPx.animateTo(0f, tween(140, easing = FastOutSlowInEasing))
    }
    return shake
}

/** Draws [shake] in the layer only (no recomposition per frame). */
private fun Modifier.chipErrorShake(shake: ChipErrorShake): Modifier = graphicsLayer {
    alpha = shake.alpha.value
    translationX = shake.offsetPx.value
}
