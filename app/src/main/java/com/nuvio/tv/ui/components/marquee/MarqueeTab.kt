package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

private const val COUNT_ON_PILL_ALPHA = 0.55f

/**
 * Marquee tab (top bar, detail section strip): `tab` text in white .50, selected = white + 2 dp
 * underline, focused = solid white pill with black text (Arshad pick 2026-10-06). Pill and
 * underline are drawn outside the text box, so focus never reflows the row. [count]: small text
 * after the label (Library's title counts, u7 stage 1), white .50 (black .55 on the pill). [locked]:
 * a lock after the label in the label's colour (a blocked tab, u11 stage 25); it replaces [count]. Callers put
 * `focusRequester`/`focusProperties`/`onFocusChanged` in [modifier].
 */
@Composable
internal fun MarqueeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    locked: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val bar = marquee.topBar
    val pillColor = NuvioPrimitives.white
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .drawBehind {
                if (isFocused) {
                    val padH = bar.pillPaddingHorizontal.toPx()
                    val padV = bar.pillPaddingVertical.toPx()
                    val pillHeight = size.height + 2 * padV
                    drawRoundRect(
                        color = pillColor,
                        topLeft = Offset(-padH, -padV),
                        size = Size(size.width + 2 * padH, pillHeight),
                        cornerRadius = CornerRadius(pillHeight / 2)
                    )
                } else if (selected) {
                    val lineHeight = bar.underlineHeight.toPx()
                    drawRoundRect(
                        color = pillColor,
                        topLeft = Offset(0f, size.height + bar.underlineOffset.toPx()),
                        size = Size(size.width, lineHeight),
                        cornerRadius = CornerRadius(lineHeight / 2)
                    )
                }
            },
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) NuvioTheme.colors.TextPrimary else marquee.onImage3,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = NuvioPrimitives.black,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = NuvioPrimitives.black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        if (locked) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(bar.tabLockGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, style = NuvioTheme.textStyles.tab, maxLines = 1)
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.top_bar_tab_locked),
                    modifier = Modifier.size(bar.tabLockSize)
                )
            }
        } else if (count == null) {
            Text(text = label, style = NuvioTheme.textStyles.tab, maxLines = 1)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(bar.tabCountGap)) {
                Text(text = label, style = NuvioTheme.textStyles.tab, maxLines = 1, modifier = Modifier.alignByBaseline())
                Text(
                    text = count,
                    style = NuvioTheme.textStyles.caption,
                    color = if (isFocused) NuvioPrimitives.black.copy(alpha = COUNT_ON_PILL_ALPHA) else marquee.onImage3,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
    }
}
