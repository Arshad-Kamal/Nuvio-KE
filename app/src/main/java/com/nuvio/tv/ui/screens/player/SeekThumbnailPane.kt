/*
 * NuvioTV-Fork - seek-thumbnail workstream (T-series)
 * Copyright (C) 2026 NuvioTV-Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaybackChapter
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatPlaybackClock
import kotlin.math.max
import kotlin.math.roundToInt
import com.nuvio.tv.core.player.PlayerUiState

/** How long the preview bubble lingers after the last preview input before dismissing. */
private const val PREVIEW_THUMB_LINGER_MS = 3_000L

/**
 * Seek preview bubble (T-series Build 6 thumbnail pane; Marquee stage 3 in U3c). Shows the
 * previewed position (previewThumbPositionMs), held ~PREVIEW_THUMB_LINGER_MS after the last
 * preview input so a settled frame stays visible and sharpens, then auto-dismisses. Thumbnail:
 * pure local lookup - memory hit shows immediately, async disk hits arrive via
 * SeekThumbnails.tick, a true miss renders none. With the OSD ([osdVisible]) the preview time
 * rides under the frame (alone when there is no frame), under the previewed chapter's pill when
 * the source has chapters (F3 stage 2); over the mini seek only the frame shows (its label
 * carries the time).
 *
 * Placement: centred on the playhead (same span as the bar: the TV-safe insets), clamped inside
 * those insets; its bottom 2 dp over the focused OSD bar or 6 dp over the mini seek's label.
 * The frame is sized by the source bitmap's aspect (fixed height, width = height * aspect) so
 * non-16:9 frames don't distort.
 */
@Composable
fun SeekPreviewBubbleHost(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    osdVisible: Boolean,
    modifier: Modifier = Modifier
) {
    // Composable reads first (consistent call order), then early returns.
    val timeline = viewModel.playbackTimeline.collectAsState()
    // Only the duration: position ticks must not recompose the bubble.
    val duration by remember(timeline) { derivedStateOf { timeline.value.duration } }
    val tick by SeekThumbnails.tick
    // Hold the last previewed position for a short window so a settled preview stays visible
    // (and sharpens via tick) after commit, then auto-dismisses after PREVIEW_THUMB_LINGER_MS
    // of no input. Abandoned previews null previewThumbPositionMs upstream -> immediate hide.
    val requestedPositionMs = uiState.previewThumbPositionMs
    var shownPositionMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(requestedPositionMs) {
        val req = requestedPositionMs
        if (req == null) {
            shownPositionMs = null
        } else {
            shownPositionMs = req
            kotlinx.coroutines.delay(PREVIEW_THUMB_LINGER_MS)
            if (shownPositionMs == req) shownPositionMs = null
        }
    }
    val previewPositionMs = shownPositionMs ?: return
    val durationMs = duration
    if (durationMs <= 0L) return
    // The ImageBitmap wrapper is remembered too, so Image keeps its painter across recompositions.
    val frame = remember(previewPositionMs, tick) { SeekThumbnails.thumbFor(previewPositionMs)?.asImageBitmap() }
    if (frame == null && !osdVisible) return

    val osd = NuvioTheme.marquee.osd
    val safe = NuvioTheme.layout.tvSafeHorizontal
    val fraction = (previewPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = if (osdVisible) osd.previewBottomControls else osd.previewBottomHidden)
                // Full width, the bubble placed (absolute, as the LTR bar) centred on the playhead.
                .layout { measurable, constraints ->
                    val bubble = measurable.measure(constraints.copy(minWidth = 0))
                    val width = constraints.maxWidth
                    val safePx = safe.roundToPx()
                    val center = safePx + fraction * (width - 2 * safePx)
                    val x = (center - bubble.width / 2f).roundToInt()
                        .coerceIn(safePx, max(safePx, width - safePx - bubble.width))
                    layout(width, bubble.height) { bubble.place(x, 0) }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(osd.previewGap)
        ) {
            if (frame != null) {
                val aspect = frame.width.toFloat() / frame.height.coerceAtLeast(1)
                val shape = RoundedCornerShape(osd.previewFrameRadius)
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    modifier = Modifier
                        .height(osd.previewFrameHeight)
                        .width(osd.previewFrameHeight * aspect)
                        .clip(shape)
                        .border(1.dp, osd.previewFrameRing, shape)
                )
            }
            if (osdVisible) {
                val chapterIndex = currentChapterIndex(uiState.chapters, previewPositionMs)
                if (chapterIndex >= 0) SeekPreviewChapterPill(uiState.chapters[chapterIndex], chapterIndex)
                Text(
                    text = formatPlaybackClock(previewPositionMs),
                    style = NuvioTheme.textStyles.osdTime,
                    color = NuvioPrimitives.white,
                    modifier = Modifier
                        .background(osd.previewTimeFill, CircleShape)
                        .padding(horizontal = osd.previewTimePadding)
                )
            }
        }
    }
}

/** The previewed chapter: its number then title ("8  Memory Recovery"), or "Chapter 8" alone when untitled (Spec 25 Q5). */
@Composable
private fun SeekPreviewChapterPill(chapter: PlaybackChapter, index: Int) {
    val osd = NuvioTheme.marquee.osd
    Row(
        modifier = Modifier
            .widthIn(max = osd.chapterPillMaxWidth)
            .background(osd.previewTimeFill, RoundedCornerShape(osd.chapterPillRadius))
            .padding(horizontal = osd.chapterPillPaddingH, vertical = osd.chapterPillPaddingV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (chapter.title != null) {
            Text(
                text = (index + 1).toString(),
                style = NuvioTheme.textStyles.caption,
                color = NuvioTheme.marquee.onImage2
            )
            Spacer(modifier = Modifier.width(osd.chapterNumberGap))
        }
        Text(
            text = chapterLabel(chapter, index) { stringResource(R.string.player_chapter_untitled, it) },
            style = NuvioTheme.textStyles.cardLabel,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
