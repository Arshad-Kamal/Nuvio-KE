package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaybackChapter
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatPlaybackClock
import kotlinx.coroutines.delay

/**
 * Chapter list (approved sandbox `f3-chapters-marquee` stage 5, option A): the compact panel, one
 * row per chapter (title or "Chapter N", start time at the end), the chapter playing at open
 * ticked and focused. OK seeks to its start and closes; Back (PlayerScreen) reopens the More tray
 * on the Chapters pill (Spec 25 Q4).
 */
@Composable
internal fun ChaptersPanel(
    visible: Boolean,
    chapters: List<PlaybackChapter>,
    positionMs: () -> Long,
    onChapterSelected: (PlaybackChapter) -> Unit,
    modifier: Modifier = Modifier
) {
    // Read once per opening: the tick and focus stay on the chapter playing when it opened.
    val currentIndex = remember(visible, chapters) { currentChapterIndex(chapters, positionMs()) }
    val initialIndex = currentIndex.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(visible, chapters) {
        if (!visible || chapters.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(initialIndex)
        delay(120)
        runCatching { initialFocusRequester.requestFocus() }
    }
    PlayerCompactPanel(
        visible = visible,
        title = stringResource(R.string.player_tray_chapters),
        modifier = modifier
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
            modifier = Modifier
                .heightIn(max = 500.dp)
                .fillMaxWidth()
        ) {
            // Positional keys: normalized chapters may share a start time.
            itemsIndexed(items = chapters, key = { index, _ -> index }) { index, chapter ->
                PlayerPanelRow(
                    title = chapterLabel(chapter, index) { stringResource(R.string.player_chapter_untitled, it) },
                    titleEnd = formatPlaybackClock(chapter.startMs),
                    selected = index == currentIndex,
                    onClick = { onChapterSelected(chapter) },
                    focusRequester = if (index == initialIndex) initialFocusRequester else null
                )
            }
        }
    }
}

/** The chapter playing at [positionMs]: the last one starting at or before it; -1 before the first (or none). */
internal fun currentChapterIndex(chapters: List<PlaybackChapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs }

/**
 * Where the seek bar cuts a chapter gap (F3 stage 1): each chapter start strictly inside the
 * timeline as a fraction of [durationMs], in input order (normalized chapters are sorted), without repeats. A start at 0 (or at/after
 * the end) marks nothing; no duration yet (mpv may read 0 early) means no marks.
 */
internal fun chapterMarkFractions(chapters: List<PlaybackChapter>, durationMs: Long): List<Float> {
    if (durationMs <= 0L) return emptyList()
    return chapters.asSequence()
        .filter { it.startMs in 1..<durationMs }
        .map { it.startMs.toFloat() / durationMs }
        .distinct()
        .toList()
}

/** A chapter's display name: its title, else [untitled] with its 1-based number ("Chapter 8", F3 Q4/Q5). */
internal inline fun chapterLabel(chapter: PlaybackChapter, index: Int, untitled: (number: Int) -> String): String =
    chapter.title ?: untitled(index + 1)
