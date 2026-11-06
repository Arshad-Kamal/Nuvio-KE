package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SCROLL_STEP_PX = 260

/**
 * The shell of a full-screen About / Read more sheet (Detail's About sheet; the person page's Read
 * more, Spec 29 stage 2): `detail.aboutVeil` over the page (the caller fades its content out), one
 * D-pad scroll area placed by [area] with a thin scrollbar at its end edge, and the "BACK Close"
 * hint. Back closes ([onDismiss]).
 *
 * With [hasFocusTargets], [firstFocus] sits on the caller's first chip or row and Up/Down move focus
 * between targets, scrolling a step only when focus cannot move; without, the scroll area itself
 * takes [firstFocus] and Up/Down scroll.
 */
@Composable
internal fun AboutSheetScaffold(
    onDismiss: () -> Unit,
    hasFocusTargets: Boolean,
    firstFocus: FocusRequester,
    area: Modifier,
    content: @Composable () -> Unit
) {
    val detail = NuvioTheme.marquee.detail
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var requestedScrollPosition by remember { mutableIntStateOf(0) }
    var scrollAnimationJob by remember { mutableStateOf<Job?>(null) }

    // Frame-paced exponential ease toward the latest requested position (held keys retarget it).
    fun requestSmoothScroll(target: Int) {
        requestedScrollPosition = target.coerceIn(0, scrollState.maxValue)
        if (scrollAnimationJob?.isActive == true) return
        scrollAnimationJob = coroutineScope.launch {
            scrollState.scroll {
                var previousFrame = withFrameNanos { it }
                while (true) {
                    val frame = withFrameNanos { it }
                    val elapsedSeconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    previousFrame = frame
                    val distance = requestedScrollPosition.toFloat() - scrollState.value.toFloat()
                    if (abs(distance) < 0.5f) {
                        scrollBy(distance)
                        break
                    }
                    scrollBy(distance * (1f - exp(-12f * elapsedSeconds)))
                }
            }
        }
    }

    // Placed once per open, and again only when a target lands while the scroll area holds focus
    // (the area stops being focusable then); late data never pulls focus from a chosen target.
    LaunchedEffect(hasFocusTargets) { firstFocus.requestFocusAfterFrames() }

    MarqueeVeilDialog(onDismissRequest = onDismiss, veil = detail.aboutVeil) {
        // Read inside the Dialog: it is its own window with its own focus owner. The page's FocusManager
        // (read outside) never held the sheet's focus, so every moveFocus failed and Up/Down only scrolled
        // (Spec 50 I3, Spec 12 F-4).
        val focusManager = LocalFocusManager.current
        Box(
            modifier = area
                // Clears the key hint below the content.
                .padding(bottom = detail.hintBottom + detail.hintKeyHeight + detail.aboutBlockGap)
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val maxScroll = scrollState.maxValue.toFloat()
                    if (maxScroll <= 0f) return@drawWithContent
                    val viewport = size.height
                    val inset = 8.dp.toPx()
                    val track = (viewport - inset * 2f).coerceAtLeast(0f)
                    val thumb = (track * viewport / (viewport + maxScroll))
                        .coerceAtLeast(36.dp.toPx())
                        .coerceAtMost(track)
                    val top = inset + (track - thumb) * (scrollState.value / maxScroll)
                    val x = size.width - 2.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.07f),
                        start = Offset(x, inset),
                        end = Offset(x, viewport - inset),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.30f),
                        start = Offset(x, top),
                        end = Offset(x, top + thumb),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val down = when (event.key) {
                        Key.DirectionDown -> true
                        Key.DirectionUp -> false
                        else -> return@onPreviewKeyEvent false
                    }
                    if (hasFocusTargets &&
                        focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)
                    ) {
                        return@onPreviewKeyEvent true
                    }
                    // From the running ease's target, else where focus moves left the column.
                    val base = if (scrollAnimationJob?.isActive == true) requestedScrollPosition else scrollState.value
                    if (down && scrollState.value < scrollState.maxValue) {
                        requestSmoothScroll(base + SCROLL_STEP_PX)
                    } else if (!down && scrollState.value > 0) {
                        requestSmoothScroll(base - SCROLL_STEP_PX)
                    }
                    true
                }
                .then(if (hasFocusTargets) Modifier else Modifier.focusRequester(firstFocus).focusable())
                .verticalScroll(scrollState)
        ) {
            content()
        }
        DetailKeyHint(
            key = stringResource(R.string.detail_key_back),
            label = stringResource(R.string.detail_hint_close),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = detail.hintBottom)
        )
    }
}
