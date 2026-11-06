package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

private const val BACKSPACE = '\b'
private val KeypadRows = listOf("123", "456", "789", " 0$BACKSPACE")

/**
 * The on-screen PIN keypad (u10 `.l10kp`: 3x4, digits 1-9, a gap, 0, backspace), shared by the
 * profile PIN screen and the Settings combination gate. [initialFocus] is attached to "5", where
 * the screen puts focus on entry. While not [enabled] (a lockout) the keys dim but stay focusable,
 * so focus never drops, and presses are ignored. A screen opened by a long press wraps it in
 * `holdReleaseGuard` so the release does not press "5".
 */
@Composable
fun PinKeypad(
    enabled: Boolean,
    initialFocus: FocusRequester,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.profiles
    Column(
        modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else tokens.keyDisabledAlpha },
        verticalArrangement = Arrangement.spacedBy(tokens.keyGap)
    ) {
        KeypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.keyGap)) {
                row.forEach { key ->
                    when (key) {
                        ' ' -> Spacer(Modifier.size(tokens.keyWidth, tokens.keyHeight))
                        BACKSPACE -> PinKey(
                            enabled = enabled,
                            description = stringResource(R.string.dpad_setup_pin_backspace),
                            onClick = onBackspace
                        ) { focused ->
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = null,
                                tint = if (focused) NuvioPrimitives.black else NuvioPrimitives.white,
                                modifier = Modifier.size(tokens.keyIconSize)
                            )
                        }
                        else -> PinKey(
                            enabled = enabled,
                            description = key.toString(),
                            onClick = { onDigit(key) },
                            modifier = if (key == '5') Modifier.focusRequester(initialFocus) else Modifier
                        ) { focused ->
                            Text(
                                text = key.toString(),
                                style = NuvioTheme.textStyles.pinKey,
                                color = if (focused) NuvioPrimitives.black else NuvioPrimitives.white
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(tokens.keyWidth, tokens.keyHeight)
            .graphicsLayer {
                val scale = if (isFocused) tokens.keyFocusScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (isFocused) NuvioPrimitives.white else tokens.keyFill)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (enabled) onClick() }
            )
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        content(isFocused)
    }
}
