package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.search.LiveChannelSearchIndex
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.NumberFormat

// Live channel search (Spec 111, sandbox `s111-live-search` v1 stages 1-7): the pill row's search
// button, the field that takes the row's place, the count line and the match marks.

/** What a result row marks (`mark`): chars of its number ([onNumber]) or of its shown name. */
internal data class LiveRowMark(val onNumber: Boolean, val range: IntRange)

/** [key]'s match in open search, for its row's mark; null when search is closed or it is no result. */
internal fun LiveSearch?.mark(key: String): LiveRowMark? =
    this?.hit(key)?.let { LiveRowMark(it.tier.onNumber, LiveChannelSearchIndex.highlight(it)) }

/** [text] with [range] (clamped to it; none when it falls outside) on a [fill] mark. */
internal fun liveTvMarked(text: String, range: IntRange?, fill: Color): AnnotatedString {
    val start = range?.first?.coerceAtLeast(0) ?: return AnnotatedString(text)
    val end = (range.last + 1).coerceAtMost(text.length)
    if (start >= end) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = fill), start, end)
    }
}

/**
 * The last search whose results are settled (not pending): what the count line and the no-match line
 * show, so a frame waiting for the index never says "no match" or a stale count (Spec 111). Null while
 * search is closed or before its first settled results.
 */
@Composable
internal fun rememberSettledSearch(search: LiveSearch?): LiveSearch? {
    // A plain holder, written in composition: the value read is always this frame's.
    val last = remember { arrayOfNulls<LiveSearch>(1) }
    if (search == null) last[0] = null else if (!search.pending) last[0] = search
    return last[0]
}

/** The count line's figures: every channel before typing, else the found ones of [total]. */
internal sealed interface LiveSearchCount {
    data class All(val total: Int) : LiveSearchCount
    data class Found(val found: Int, val total: Int) : LiveSearchCount
}

/** What the list says while search shows no result: what to type, or that nothing matched. */
internal enum class LiveSearchNote { START, NO_MATCH }

/** One reading of open search for the count line and the list's message (null = say nothing). */
internal data class LiveSearchStatus(val count: LiveSearchCount?, val note: LiveSearchNote?)

/**
 * [search] as the count line and the message show it, from [settled] ([rememberSettledSearch]): before
 * typing, every channel (no line while the stores load) and the start note; after, only a settled
 * search of the same query counts or says "no match", so a pending frame shows neither (Spec 111).
 */
internal fun liveSearchStatus(search: LiveSearch, settled: LiveSearch?): LiveSearchStatus {
    if (search.query.isBlank()) {
        return LiveSearchStatus(LiveSearchCount.All(search.total).takeIf { search.total > 0 }, LiveSearchNote.START)
    }
    val current = settled?.takeIf { it.query == search.query }
        ?: return LiveSearchStatus(null, null)
    return LiveSearchStatus(
        LiveSearchCount.Found(current.hits.size, current.total),
        LiveSearchNote.NO_MATCH.takeIf { current.hits.isEmpty() }
    )
}

/** The results shown are [text]'s, settled (not pending): Search or Down may enter them (stage 4). */
internal fun LiveSearch?.settledFor(text: String): Boolean = this != null && query == text && !pending

/**
 * Search or Down in the field (stage 4): the first result is entered at once when the typed text's
 * results are settled ([settledFor]), else once they are; typing again, focus leaving the field or
 * search closing drops the wait. One per field (the List screen's, the full-screen overlay's).
 */
internal class LiveSearchEntry {
    private var waitingFor: String? = null

    /** Search or Down on [text] while [search] shows; true = enter the first result now. */
    fun submit(text: String, search: LiveSearch?): Boolean {
        if (search.settledFor(text)) {
            waitingFor = null
            return true
        }
        waitingFor = text
        return false
    }

    /** The text changed or focus left the field: a waiting entry is dropped. */
    fun drop() {
        waitingFor = null
    }

    /** A new search state landed (null = closed); true = the waiting entry's results settled: enter now. */
    fun settled(search: LiveSearch?): Boolean {
        val wanted = waitingFor ?: return false
        if (search == null) {
            waitingFor = null
            return false
        }
        if (!search.settledFor(wanted)) return false
        waitingFor = null
        return true
    }
}

/** `.srch`: the pill row's first stop, a glass circle with the search icon (white when focused). */
@Composable
internal fun LiveTvSearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    Surface(
        onClick = onClick,
        modifier = modifier.size(live.pillHeight),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = marquee.glassFill,
            contentColor = NuvioPrimitives.white,
            focusedContainerColor = NuvioPrimitives.white,
            focusedContentColor = NuvioPrimitives.black,
            pressedContainerColor = NuvioPrimitives.white,
            pressedContentColor = NuvioPrimitives.black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.live_tv_search),
                modifier = Modifier.size(marquee.search.iconSize)
            )
        }
    }
}

/**
 * `.s111f` + `.s111sc`: the search field in the pill row's place (stages 2-7) and, at the preview's
 * start, the count line ([status]): "Searching all N channels" until something is typed, then "n of N
 * channels". The field starts from [initialText] (the query being searched). Every key goes to [onQueryChange] (results follow it). The
 * keyboard's Search key hides the keyboard and calls [onSubmit]; Down calls [onDown] and is always
 * consumed (no match keeps focus here). OK on the field reopens the keyboard (the text field's own).
 * [onFocusLeft]: focus left the field and its clear circle.
 * [contentRequester]: Down from the top bar lands here while no result shows.
 * [inPanel]: full screen's list overlay (stages 9-10, `.s111pf`): the panel's width, 36 dp tall, no clear
 * circle, the count "n of N" inside at its end (none before typing).
 */
@Composable
internal fun LiveTvSearchField(
    initialText: String,
    status: LiveSearchStatus,
    fieldRequester: FocusRequester,
    contentRequester: FocusRequester?,
    onQueryChange: (String) -> Unit,
    onSubmit: (text: String) -> Unit,
    onDown: (text: String) -> Unit,
    onHideKeyboard: () -> Unit,
    onFocusLeft: () -> Unit,
    modifier: Modifier = Modifier,
    inPanel: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val tokens = marquee.search
    val iconSize = if (inPanel) live.overlaySearchIconSize else tokens.iconSize
    val numbers = remember { NumberFormat.getIntegerInstance() }
    // The field owns its text (the derived results trail it); seeded from the VM's query on a return.
    var value by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var focused by remember { mutableStateOf(false) }
    val setText = { text: String ->
        value = TextFieldValue(text, TextRange(text.length))
        onQueryChange(text)
    }
    Row(
        modifier = modifier
            .then(if (contentRequester != null) Modifier.focusRequester(contentRequester) else Modifier)
            .onFocusChanged { if (!it.hasFocus) onFocusLeft() }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
                if (key.action == KeyEvent.ACTION_DOWN) onDown(value.text)
                true
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .then(if (inPanel) Modifier.weight(1f) else Modifier.width(live.searchFieldWidth))
                .height(if (inPanel) live.overlaySearchFieldHeight else live.searchFieldHeight)
                .background(if (focused) tokens.fieldFocusedFill else tokens.fieldFill, CircleShape)
                .border(
                    width = if (focused) marquee.ringWidth else NuvioTheme.spacing.hairline,
                    color = if (focused) marquee.ring else tokens.fieldEdge,
                    shape = CircleShape
                )
                .then(
                    if (inPanel) Modifier.padding(horizontal = live.overlaySearchFieldPadding)
                    else Modifier.padding(start = live.searchFieldPaddingStart, end = live.searchFieldPaddingEnd)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (inPanel) live.overlaySearchFieldGap else tokens.fieldIconGap)
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = marquee.onImage3,
                modifier = Modifier.size(iconSize)
            )
            val textStyle = if (inPanel) {
                NuvioTheme.textStyles.rowTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
            } else {
                NuvioTheme.textStyles.rowTitle.copy(fontWeight = FontWeight.Medium, lineHeight = 22.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = {
                    val changed = it.text != value.text
                    value = it
                    if (changed) onQueryChange(it.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        // A hardware keyboard's Enter acts as the soft keyboard's Search key.
                        val code = event.nativeKeyEvent.keyCode
                        if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) return@onPreviewKeyEvent false
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            onHideKeyboard()
                            onSubmit(value.text)
                        }
                        true
                    },
                textStyle = textStyle.copy(color = NuvioPrimitives.white),
                cursorBrush = SolidColor(marquee.ring),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = {
                    onHideKeyboard()
                    onSubmit(value.text)
                }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.live_tv_search_placeholder),
                                style = textStyle,
                                color = marquee.onImage3,
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
            if (inPanel) {
                (status.count as? LiveSearchCount.Found)?.let { c ->
                    Text(
                        text = stringResource(R.string.live_tv_search_count_short, numbers.format(c.found), numbers.format(c.total)),
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage3,
                        maxLines = 1
                    )
                }
            } else if (value.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        setText("")
                        // The clear circle leaves with the text: the field keeps focus.
                        fieldRequester.requestFocus()
                    },
                    modifier = Modifier.size(live.searchClearSize),
                    scale = IconButtonDefaults.scale(focusedScale = 1f),
                    shape = IconButtonDefaults.shape(shape = CircleShape),
                    colors = IconButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = marquee.onImage2,
                        focusedContainerColor = NuvioPrimitives.white,
                        focusedContentColor = NuvioPrimitives.black,
                        pressedContainerColor = NuvioPrimitives.white,
                        pressedContentColor = NuvioPrimitives.black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_clear_search),
                        modifier = Modifier.size(tokens.iconSize)
                    )
                }
            }
        }
        // The count line starts where the preview does.
        if (inPanel) return@Row
        Spacer(modifier = Modifier.width(live.previewStart - NuvioTheme.layout.tvSafeHorizontal - live.searchFieldWidth))
        val count: Pair<String, String>? = when (val c = status.count) {
            is LiveSearchCount.All -> numbers.format(c.total).let { all ->
                pluralStringResource(R.plurals.live_tv_search_all, c.total, all) to all
            }
            is LiveSearchCount.Found -> numbers.format(c.found).let { n ->
                pluralStringResource(R.plurals.live_tv_search_count, c.total, n, numbers.format(c.total)) to n
            }
            null -> null
        }
        count?.let { (text, figure) ->
            val at = text.indexOf(figure)
            Text(
                text = buildAnnotatedString {
                    append(text)
                    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = marquee.onImage2), at, at + figure.length)
                },
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * `.s111msg` in the list's place while search shows no result ([note], [liveSearchStatus]): what to type
 * before anything is, "No channel matches [query]" once a settled search found none.
 */
@Composable
internal fun LiveTvSearchMessage(note: LiveSearchNote?, query: String, modifier: Modifier = Modifier) {
    val (title, hint) = when (note) {
        LiveSearchNote.START -> stringResource(R.string.live_tv_search_start) to stringResource(R.string.live_tv_search_start_hint)
        LiveSearchNote.NO_MATCH ->
            stringResource(R.string.live_tv_search_none, query.trim()) to stringResource(R.string.live_tv_search_none_hint)
        null -> return
    }
    LiveTvListMessage(title = title, hint = hint, modifier = modifier)
}

/** The list's one-line message (Q-L7b sandbox v4 stage 17, Spec 111 `.s111msg`): a title, an optional hint. */
@Composable
internal fun LiveTvListMessage(title: String, hint: String?, modifier: Modifier = Modifier) {
    Column(
        // 14 dp above, 10 dp in, 4 dp between.
        modifier = modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = title, style = NuvioTheme.textStyles.rowTitle, color = NuvioPrimitives.white)
        if (hint != null) Text(text = hint, style = NuvioTheme.textStyles.meta, color = NuvioTheme.marquee.onImage3)
    }
}
