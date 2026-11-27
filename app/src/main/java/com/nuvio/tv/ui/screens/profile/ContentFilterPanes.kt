package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.contentfilter.OwnTagSearchResult
import com.nuvio.tv.domain.contentfilter.OwnPack
import com.nuvio.tv.domain.contentfilter.OwnTag
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Sandbox spec-117-content-filters stages 3, 4, 4b, 4c (`.cfc span`, `.cfc span.add`, `.cfgen`, `.btn.sm`).
private val GENRE_HEIGHT = 30.dp
private val GENRE_PADDING_H = 12.dp
private val GENRE_PICKED_FILL = Color.White.copy(alpha = 0.18f)
private val ADD_EDGE = Color.White.copy(alpha = 0.3f)
private val ADD_FIELD_MIN = 120.dp
private val BUTTON_HEIGHT = 30.dp
private val BUTTON_PADDING_H = 12.dp
private const val GENRE_COLUMNS = 3

// A typed line may hold several comma-separated words; each is cut at FILTER_WORD_MAX when added.
private const val ADD_LINE_MAX = 300

/**
 * The frame of an editable pane (stages 3, 4, 4b): the pane's box, its [content] scrolled to
 * whatever holds focus, and [foot] pinned at its bottom. Left out of it returns to [backTo] (the list
 * row); Up / Down / Right past its edges stay in it. [onFocusChanged] reports whether focus is inside
 * (Back then returns to the row).
 */
@Composable
internal fun EditablePane(
    backTo: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    foot: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(PANE_RADIUS)
    Column(
        modifier = Modifier
            .padding(start = PANE_START, top = BODY_TOP, end = PAGE_END, bottom = PANE_BOTTOM)
            .fillMaxSize()
            .testTag(PACK_PANE_TAG)
            .clip(shape)
            .background(PANE_FILL)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusProperties {
                // Only D-pad moves are held in; a programmatic request (Back, Create, Delete) reports Enter.
                onExit = {
                    when (requestedFocusDirection) {
                        FocusDirection.Left -> backTo.requestFocus()
                        FocusDirection.Up, FocusDirection.Down, FocusDirection.Right -> cancelFocusChange()
                        else -> Unit
                    }
                }
            }
            .focusGroup()
            .padding(horizontal = PANE_PADDING_H, vertical = PANE_PADDING_V),
        verticalArrangement = Arrangement.spacedBy(PANE_GAP)
    ) {
        // The content takes all the height the foot leaves (a weighted spacer beside it would halve
        // it: 117 F10, the preview picker showed only Off) and pins the foot at the bottom.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PANE_GAP),
            content = content
        )
        foot()
    }
}

/** `.cfd h3`: the pane's title, with ON / OFF when [enabled] is given. */
@Composable
internal fun PaneTitle(title: String, enabled: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        Text(
            text = title,
            style = paneTitleStyle(),
            color = NuvioPrimitives.white,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (enabled != null) StateTag(enabled)
    }
}

/** `.cfd .grp`: an uppercase group heading with its [note]. */
@Composable
internal fun GroupHeading(title: String, note: String? = null) {
    val eyebrow = NuvioTheme.textStyles.eyebrow
    val color = NuvioTheme.marquee.onImage3
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title.uppercase(), style = eyebrow, color = color, maxLines = 1)
        if (note != null) {
            Text(
                text = note,
                style = eyebrow.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/** `.cfd p` / `.foot`: the pane's body text and its foot. */
@Composable
internal fun PaneText(text: String, foot: Boolean = false) {
    Text(
        text = text,
        style = if (foot) NuvioTheme.textStyles.settingsNote else NuvioTheme.textStyles.panelBody,
        color = if (foot) NuvioTheme.marquee.onImage3 else NuvioTheme.marquee.onImage2
    )
}

/**
 * Chips that act on OK (`.cfc span`, white while focused): [entries] by [label]. [adds] false: each
 * ends in "×" and OK removes it; true: outlined, "+ name", OK adds it. Then [trailing] (an add chip).
 * After an OK takes an entry away, focus goes to the chip now in its place, else the one before,
 * else [fallback]; so the host stays composed with [entries] empty (nothing shown but [trailing]).
 */
@Composable
internal fun <T> ActionChips(
    entries: List<T>,
    label: (T) -> String,
    key: (T) -> Any,
    adds: Boolean,
    onClick: (T) -> Unit,
    fallback: FocusRequester?,
    trailing: (@Composable () -> Unit)? = null
) {
    val requesters = remember { HashMap<Any, FocusRequester>() }
    // A new holder per OK, so a second OK at the same index restarts the effect.
    var refocus by remember { mutableStateOf<Refocus?>(null) }
    val current by rememberUpdatedState(entries)
    // Cleared only after the request: clearing first would restart (cancel) this effect before it ran.
    LaunchedEffect(refocus) {
        val at = refocus?.at ?: return@LaunchedEffect
        // The target is picked once the removal has recomposed, so a chip gone meanwhile is never it.
        repeat(2) { withFrameNanos { } }
        val target = current.getOrNull(at) ?: current.getOrNull(at - 1)
        (target?.let { requesters[key(it)] } ?: fallback)?.requestFocusAfterFrames(frames = 0)
        refocus = null
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP), verticalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        entries.forEachIndexed { index, entry ->
            FocusChip(
                text = if (adds) "+ ${label(entry)}" else label(entry),
                removes = !adds,
                outlined = adds,
                modifier = Modifier.focusRequester(requesters.getOrPut(key(entry)) { FocusRequester() }),
                onClick = {
                    refocus = Refocus(index)
                    onClick(entry)
                }
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun FocusChip(text: String, removes: Boolean, outlined: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val marquee = NuvioTheme.marquee
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            .background(
                when {
                    focused -> NuvioPrimitives.white
                    outlined -> Color.Transparent
                    else -> CHIP_FILL
                }
            )
            .then(if (outlined && !focused) Modifier.border(1.dp, ADD_EDGE, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = CHIP_PADDING_H),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val style = NuvioTheme.textStyles.caption
        Text(text = text, style = style, color = if (focused) NuvioPrimitives.black else marquee.onImage2, maxLines = 1)
        if (removes) {
            Text(
                text = "\u00D7",
                style = style,
                color = if (focused) ROW_SUBTITLE_FOCUSED else marquee.onImage3,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/**
 * `.cfc span.add`: an outlined [label] chip. OK opens the keyboard in it; Done hands the typed text
 * to [onSubmit] (blank text: nothing), empties it and keeps focus here for the next one; leaving
 * the chip ends typing and drops the text.
 */
@Composable
internal fun AddChip(label: String, onSubmit: (String) -> Unit, focusRequester: FocusRequester, maxLength: Int = ADD_LINE_MAX) {
    val marquee = NuvioTheme.marquee
    var shellFocused by remember { mutableStateOf(false) }
    var textFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val textFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        if (textFocus.requestFocus()) keyboard?.show() else editing = false
    }
    val focused = shellFocused || textFocused
    val shape = RoundedCornerShape(CHIP_HEIGHT / 2)
    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { shellFocused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = true }
            .height(CHIP_HEIGHT)
            .clip(shape)
            .background(if (focused) NuvioPrimitives.white else Color.Transparent)
            .then(if (focused) Modifier else Modifier.border(1.dp, ADD_EDGE, shape))
            .padding(horizontal = CHIP_PADDING_H),
        contentAlignment = Alignment.CenterStart
    ) {
        val style = NuvioTheme.textStyles.caption
        val ink = if (focused) NuvioPrimitives.black else marquee.onImage2
        if (!editing || text.isEmpty()) {
            Text(text = if (editing) "" else "+ $label", style = style, color = ink, maxLines = 1)
        }
        BasicTextField(
            value = text,
            onValueChange = { if (it.length <= maxLength) text = it },
            modifier = Modifier
                .then(if (editing) Modifier.widthIn(min = ADD_FIELD_MIN) else Modifier.widthIn(max = 0.dp))
                .focusProperties { canFocus = editing }
                .focusRequester(textFocus)
                .onFocusChanged {
                    if (textFocused && !it.isFocused) {
                        editing = false
                        text = ""
                    }
                    textFocused = it.isFocused
                },
            textStyle = style.copy(color = NuvioPrimitives.black),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val typed = text
                text = ""
                editing = false
                focusRequester.requestFocus()
                if (typed.isNotBlank()) onSubmit(typed)
            }),
            cursorBrush = SolidColor(NuvioPrimitives.black)
        )
    }
}

/** `.btn.sm`: a small pill button, white while focused. */
@Composable
internal fun SmallButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(BUTTON_HEIGHT)
            .clip(RoundedCornerShape(BUTTON_HEIGHT / 2))
            .background(if (focused) NuvioPrimitives.white else NuvioTheme.marquee.glassFill)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = BUTTON_PADDING_H),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = NuvioTheme.textStyles.panelPill,
            color = if (focused) NuvioPrimitives.black else NuvioPrimitives.white,
            maxLines = 1
        )
    }
}

/** Stage 3: My words, their chips (OK removes one) and Add a word. */
@Composable
internal fun MyWordsPane(
    profileName: String,
    words: List<String>,
    backTo: FocusRequester,
    entryFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    EditablePane(
        backTo = backTo,
        onFocusChanged = onFocusChanged,
        foot = { PaneText(stringResource(R.string.content_filters_my_words_foot), foot = true) }
    ) {
        PaneTitle(stringResource(R.string.content_filters_my_words))
        PaneText(stringResource(R.string.content_filters_my_words_explainer, profileName))
        GroupHeading(stringResource(R.string.content_filters_words_and_phrases))
        ActionChips(
            entries = words,
            label = { it },
            key = { it },
            adds = false,
            onClick = onRemove,
            fallback = entryFocus,
            trailing = { AddChip(stringResource(R.string.content_filters_add_word), onAdd, entryFocus) }
        )
    }
}

/** Stage 4b: Create a pack's name field; Done hands the name to [onCreate]. */
@Composable
internal fun CreatePackPane(
    profileName: String,
    backTo: FocusRequester,
    entryFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    EditablePane(
        backTo = backTo,
        onFocusChanged = onFocusChanged,
        foot = { PaneText(stringResource(R.string.content_filters_create_foot), foot = true) }
    ) {
        PaneTitle(stringResource(R.string.content_filters_create_pack))
        PaneText(stringResource(R.string.content_filters_create_explainer, profileName))
        GroupHeading(stringResource(R.string.content_filters_name))
        MarqueeNameField(
            value = name,
            onValueChange = { name = it },
            maxLength = OWN_PACK_NAME_MAX,
            placeholder = stringResource(R.string.content_filters_name_placeholder),
            focusRequester = entryFocus,
            onDone = {
                val typed = name
                name = ""
                onCreate(typed)
            },
            showCounter = false,
            keyboardOnOk = true
        )
    }
}

/** Where [ActionChips] puts focus after an OK takes chip [at] away; identity, not value, equality. */
private class Refocus(val at: Int)

/** Where Find a tag stands: [result] null while TMDB is asked. */
private data class TagSearch(val query: String, val result: OwnTagSearchResult?)

/**
 * Stage 4: an own pack's name, words, genres and tags, and Delete pack; stage 4c: Find a tag lists
 * TMDB's matches under Tags (OK adds one). [tagSearch] null: no TMDB key, so Find a tag is not shown.
 */
@Composable
internal fun OwnPackPane(
    pack: OwnPack,
    enabled: Boolean,
    backTo: FocusRequester,
    entryFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onChange: ((OwnPack) -> OwnPack) -> Unit,
    onDelete: () -> Unit,
    tagSearch: (suspend (String) -> OwnTagSearchResult)?
) {
    // The caller composes one pane per row (key(focusedKey)), so this state is per pack.
    var nameText by remember { mutableStateOf(pack.name) }
    val wordsAdd = remember { FocusRequester() }
    EditablePane(
        backTo = backTo,
        onFocusChanged = onFocusChanged,
        foot = { SmallButton(stringResource(R.string.content_filters_delete_pack), onDelete) }
    ) {
        PaneTitle(pack.name, enabled)
        GroupHeading(stringResource(R.string.content_filters_name))
        MarqueeNameField(
            value = nameText,
            onValueChange = { typed ->
                nameText = typed
                onChange { it.renamed(typed) }
            },
            maxLength = OWN_PACK_NAME_MAX,
            placeholder = pack.name,
            focusRequester = entryFocus,
            onDone = { if (nameText.isBlank()) nameText = pack.name },
            showCounter = false,
            keyboardOnOk = true
        )
        GroupHeading(stringResource(R.string.content_filters_words_and_phrases))
        ActionChips(
            entries = pack.words,
            label = { it },
            key = { it },
            adds = false,
            onClick = { word -> onChange { it.withoutWord(word) } },
            fallback = wordsAdd,
            trailing = { AddChip(stringResource(R.string.content_filters_add), { input -> onChange { it.withWords(input) } }, wordsAdd) }
        )
        GroupHeading(stringResource(R.string.content_filters_genres))
        GenreGrid(pack = pack, onToggle = { genre -> onChange { it.withGenreToggled(genre) } })
        OwnPackTags(pack = pack, onChange = onChange, tagSearch = tagSearch, noSearchFallback = entryFocus)
    }
}

/**
 * Stage 4's Tags group and stage 4c: the pack's tags (OK removes one), Find a tag when [tagSearch]
 * is set (a new search cancels the last), and the matches under them (OK adds one). Focus with
 * nothing left to land on goes to Find a tag, else [noSearchFallback].
 */
@Composable
private fun OwnPackTags(
    pack: OwnPack,
    onChange: ((OwnPack) -> OwnPack) -> Unit,
    tagSearch: (suspend (String) -> OwnTagSearchResult)?,
    noSearchFallback: FocusRequester
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf<TagSearch?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val findTag = remember { FocusRequester() }
    GroupHeading(stringResource(R.string.content_filters_tags), stringResource(R.string.content_filters_tags_source))
    ActionChips(
        entries = pack.tags,
        label = { it.name },
        key = { it.id },
        adds = false,
        onClick = { tag -> onChange { it.withoutTag(tag.id) } },
        fallback = if (tagSearch != null) findTag else noSearchFallback,
        trailing = if (tagSearch == null) {
            null
        } else {
            {
                AddChip(
                    label = stringResource(R.string.content_filters_find_tag),
                    focusRequester = findTag,
                    onSubmit = { typed ->
                        val query = typed.trim()
                        searchJob?.cancel()
                        search = TagSearch(query, null)
                        searchJob = scope.launch { search = TagSearch(query, tagSearch(query)) }
                    }
                )
            }
        }
    )
    val current = search ?: return
    val found = current.result as? OwnTagSearchResult.Found
    val matches = found?.let { tagMatches(it.tags, pack) }
    GroupHeading(
        stringResource(R.string.content_filters_tag_matches, current.query),
        stringResource(R.string.content_filters_tag_matches_note).takeIf { !matches.isNullOrEmpty() }
    )
    when {
        current.result == null -> PaneText(stringResource(R.string.content_filters_tag_searching), foot = true)
        found == null -> PaneText(stringResource(R.string.content_filters_tag_failed), foot = true)
        found.tags.none { it.isUsable } -> PaneText(stringResource(R.string.content_filters_tag_none, current.query), foot = true)
        // Composed even once every match is added, so the last add hands focus to Find a tag.
        else -> ActionChips(
            entries = matches.orEmpty(),
            label = OwnTag::name,
            key = OwnTag::id,
            adds = true,
            onClick = { tag -> onChange { it.withTag(tag) } },
            fallback = findTag
        )
    }
}

/** `.cfgen`: every [OwnPackGenre] in three columns; picked ones lit with a check, OK flips. */
@Composable
private fun GenreGrid(pack: OwnPack, onToggle: (OwnPackGenre) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        OwnPackGenre.entries.chunked(GENRE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                row.forEach { genre ->
                    GenreCell(stringResource(genre.label), genre.isPickedIn(pack), { onToggle(genre) }, Modifier.weight(1f))
                }
                repeat(GENRE_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GenreCell(label: String, picked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(GENRE_HEIGHT)
            .clip(RoundedCornerShape(GENRE_HEIGHT / 2))
            .background(
                when {
                    focused -> NuvioPrimitives.white
                    picked -> GENRE_PICKED_FILL
                    else -> CHIP_FILL
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = GENRE_PADDING_H),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val ink = when {
            focused -> NuvioPrimitives.black
            picked -> NuvioPrimitives.white
            else -> marquee.onImage2
        }
        Text(text = label, style = NuvioTheme.textStyles.caption, color = ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (picked) Text(text = "\u2713", style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.Bold), color = ink)
    }
}
