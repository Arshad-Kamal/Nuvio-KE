package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** How long the degraded toast stays (approved 2026-10-10: once per playback, 6 s). */
private const val DEGRADED_TOAST_MS = 6_000L
private const val DEGRADED_TOAST_FADE_MS = 200

/**
 * Amber toast (data-additions stage 8): the first degradation of a playback in words, with the small
 * Stats pointer. The 6 s run from the first moment it is [visible] (the picture is up); hiding it
 * later (a recovery overlay) does not restart them. Then [onShown] clears that notice.
 */
@Composable
internal fun DegradedNoticeToast(
    message: String?,
    visible: Boolean,
    onShown: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val osd = NuvioTheme.marquee.osd
    val currentOnShown by rememberUpdatedState(onShown)
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        snapshotFlow { currentVisible }.first { it }
        delay(DEGRADED_TOAST_MS)
        currentOnShown(message)
    }
    // The last text, kept (unobserved) through the fade-out after the notice clears.
    val lastMessage = remember { arrayOfNulls<String>(1) }
    if (message != null) lastMessage[0] = message
    val shown = message ?: lastMessage[0] ?: return
    AnimatedVisibility(
        visible = message != null && visible,
        enter = fadeIn(tween(DEGRADED_TOAST_FADE_MS)),
        exit = fadeOut(tween(DEGRADED_TOAST_FADE_MS)),
        modifier = modifier
    ) {
        val shape = RoundedCornerShape(osd.toastRadius)
        val details = stringResource(R.string.player_degraded_details)
        val captionStyle = NuvioTheme.textStyles.caption.toSpanStyle().copy(color = NuvioTheme.marquee.onImage3)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(osd.toastIconGap),
            modifier = Modifier
                .background(osd.toastFill, shape)
                .border(osd.toastRingWidth, osd.toastRing, shape)
                .padding(
                    start = osd.toastPaddingStart,
                    end = osd.toastPaddingEnd,
                    top = osd.toastPaddingVertical,
                    bottom = osd.toastPaddingVertical
                )
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = NuvioPrimitives.warning,
                modifier = Modifier.size(osd.toastIconSize)
            )
            Text(
                text = buildAnnotatedString {
                    append(shown)
                    append(' ')
                    withStyle(captionStyle) { append(details) }
                },
                style = NuvioTheme.textStyles.meta,
                color = NuvioPrimitives.white,
                maxLines = 1
            )
        }
    }
}
