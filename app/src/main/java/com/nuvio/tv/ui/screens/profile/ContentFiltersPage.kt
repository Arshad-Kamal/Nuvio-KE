package com.nuvio.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.contentfilter.OwnTagSearchResult
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch

// Sandbox spec-117-content-filters stage 2 (`.cfwin`, `.cftop`, `.cfl`, `.cfr`, `.cfd`, `.cfc`) and
// spec-122-filter-preview stage 4 (`.cfc span.al`), 960x540 dp.
private val PAGE_FILL = Color(0xFF0B0B0B)
private val PAGE_START = 40.dp
internal val PAGE_END = 48.dp
private val TOP_ROW_TOP = 24.dp
private val TOP_AVATAR = 32.dp
private val TOP_GAP = 14.dp
internal val BODY_TOP = 76.dp
private val LIST_WIDTH = 400.dp
private val LIST_GAP = 2.dp
private val LIST_FADE = 56.dp
private val ROW_MIN_HEIGHT = 40.dp
private val ROW_RADIUS = 10.dp
private val ROW_PADDING_H = 10.dp
private val ROW_PADDING_V = 6.dp
internal val ROW_GAP = 12.dp
private val ROW_ICON = 18.dp
private val SWITCH_WIDTH = 34.dp
private val SWITCH_HEIGHT = 20.dp
private val SWITCH_KNOB = 14.dp
private val SWITCH_INSET = 3.dp
internal val PANE_START = 472.dp
internal val PANE_BOTTOM = 28.dp
internal val PANE_RADIUS = 20.dp
internal val PANE_PADDING_H = 24.dp
internal val PANE_PADDING_V = 22.dp
internal val PANE_GAP = 12.dp
private val PANE_SCROLL_STEP = 120.dp
private val STATE_TAG_RADIUS = 4.dp
internal val CHIP_HEIGHT = 26.dp
internal val CHIP_GAP = 6.dp
internal val CHIP_PADDING_H = 10.dp
internal val PANE_FILL = Color.White.copy(alpha = 0.05f)
internal val CHIP_FILL = Color.White.copy(alpha = 0.08f)
private val GENRE_CHIP_FILL = Color.White.copy(alpha = 0.14f)
private val ALLOW_CHIP_FILL = Color(0x247FD28B)
private val ALLOW_CHIP_TEXT = Color(0xFFBFE8C5)
private val STATE_ON_BORDER = Color(0x997FD28B)
private val STATE_OFF_BORDER = Color.White.copy(alpha = 0.3f)
private val ROW_SUBTITLE = Color.White.copy(alpha = 0.6f)
internal val ROW_SUBTITLE_FOCUSED = Color.Black.copy(alpha = 0.65f)
private val SWITCH_OFF_TRACK = Color.White.copy(alpha = 0.22f)
private val SWITCH_OFF_TRACK_FOCUSED = Color.Black.copy(alpha = 0.25f)
private val SWITCH_OFF_KNOB = Color.White.copy(alpha = 0.75f)
private val FADE = Color.Black.copy(alpha = 0.95f)

/** The pack pane's test tag (androidTest `ContentFiltersPageFocusTest`). */
internal const val PACK_PANE_TAG = "content_filters_pack_pane"

/** The list's own rows' keys beside the pack ids (own pack ids start with `own:`, built-in ids come from the pack file). */
private const val MY_WORDS_KEY = ContentFilterSettings.MY_WORDS_ID
private const val CREATE_KEY = "create_pack"
private const val PREVIEW_KEY = "preview_of"

/**
 * The Content filters page (Spec 117 P9, M1 stages 2-4; full screen over the profile editor): the
 * profile's avatar, [crumbs] (plus the focused row's name for My words, an own pack or Create a
 * pack) and how many packs are on. The list on the left: My words, the profile's own packs, Create a
 * pack, then the built-in packs A to Z ([listed]). OK flips a pack's switch ([onTogglePack]); on My
 * words or Create a pack it moves into the pane. The pane on the right shows the focused row:
 * My words and own packs edit through [onEdit] (own-pack tags searched with [tagSearch], null with no
 * TMDB key), Create a pack names a new one ([onCreatePack]: its id, focus moves to its row), a
 * built-in pack shows everything it hides, in full (P10) with its Never hidden titles (Spec 122 V5).
 * With [preview] (the primary's page only), "Preview filters of" leads the list (Spec 122 D3,
 * sandbox spec-122-filter-preview stage 1): OK moves into its pane, where OK on a choice writes it
 * at once, outside the editor's Save. Left or Back from the pane returns to its row; Back on the list calls [onDismiss]; the editor's
 * Save writes.
 */
@Composable
internal fun ContentFiltersPage(
    crumbs: List<String>,
    profileName: String,
    avatarColorHex: String,
    avatarArt: AvatarArt?,
    listed: List<ListedPack>,
    settings: ContentFilterSettings,
    onTogglePack: (String) -> Unit,
    onEdit: ((ContentFilterSettings) -> ContentFilterSettings) -> Unit,
    onCreatePack: (String) -> String?,
    tagSearch: (suspend (String) -> OwnTagSearchResult)?,
    onDismiss: () -> Unit,
    preview: FilterPreviewInputs? = null
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val paneEntry = remember { FocusRequester() }
    val ownPacks = settings.ownPacks.filter { it.hasOwnPackId }
    val firstKey = if (preview != null) PREVIEW_KEY else MY_WORDS_KEY
    var focusedKey by remember { mutableStateOf(firstKey) }
    var paneFocused by remember { mutableStateOf(false) }
    // A row to focus once composed: a new own pack, or Create a pack after a delete.
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    val focusedRow = requester(focusedKey)
    // A row taking focus also ends the pane's: a pane removed while focused (Delete pack) reports nothing.
    fun onRowFocused(key: String) {
        focusedKey = key
        paneFocused = false
    }
    val previewChoices = remember(preview?.profiles, preview?.reads, listed) {
        preview?.let { previewChoices(it.profiles, it.reads, listed) }.orEmpty()
    }
    val pickedPreviewId = preview?.let { pickedPreview(it.reads, it.profiles) }
    val focusedOwn = ownPacks.firstOrNull { it.id == focusedKey }
    val focusedBuiltIn = listed.firstOrNull { it.pack.id == focusedKey }

    BackHandler(onBack = onDismiss)
    // Composed later, so it runs first: Back from the pane returns to its row.
    BackHandler(enabled = paneFocused) { runCatching { focusedRow.requestFocus() } }
    LaunchedEffect(Unit) { requester(firstKey).requestFocusAfterFrames() }
    // Cleared only after the request: clearing first would restart (cancel) this effect before it ran.
    LaunchedEffect(pendingFocus) {
        val key = pendingFocus ?: return@LaunchedEffect
        requester(key).requestFocusAfterFrames()
        pendingFocus = null
    }

    val extraCrumb = when {
        focusedKey == MY_WORDS_KEY -> stringResource(R.string.content_filters_my_words)
        focusedKey == CREATE_KEY -> stringResource(R.string.content_filters_create_pack)
        else -> focusedOwn?.name
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PAGE_FILL)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
    ) {
        PageTopRow(
            crumbs = if (extraCrumb != null) crumbs + extraCrumb else crumbs,
            profileName = profileName,
            avatarColorHex = avatarColorHex,
            avatarArt = avatarArt,
            onCount = enabledPackNames(settings, listed).size
        )
        Box(
            modifier = Modifier
                .padding(start = PAGE_START, top = BODY_TOP)
                .width(LIST_WIDTH)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LIST_GAP)
            ) {
                if (preview != null) {
                    ListRow(
                        title = stringResource(R.string.content_filters_preview_of),
                        subtitle = stringResource(R.string.content_filters_preview_subtitle),
                        trailing = RowTrailing.Open(previewValue(previewChoices, pickedPreviewId)),
                        focusRequester = requester(PREVIEW_KEY),
                        onFocused = { onRowFocused(PREVIEW_KEY) },
                        onClick = { paneEntry.requestFocus() }
                    )
                }
                val wordCount = settings.myWords.size
                ListRow(
                    title = stringResource(R.string.content_filters_my_words),
                    subtitle = if (wordCount == 0) {
                        stringResource(R.string.content_filters_my_words_empty)
                    } else {
                        settings.myWords.joinToString()
                    },
                    trailing = RowTrailing.Open(
                        pluralStringResource(R.plurals.content_filters_word_count, wordCount, wordCount).takeIf { wordCount > 0 }
                    ),
                    focusRequester = requester(MY_WORDS_KEY),
                    onFocused = { onRowFocused(MY_WORDS_KEY) },
                    onClick = { paneEntry.requestFocus() }
                )
                ownPacks.forEach { own ->
                    ListRow(
                        title = own.name,
                        subtitle = ownPackSummary(own.counts()),
                        trailing = RowTrailing.Switch(own.id in settings.enabledPacks),
                        focusRequester = requester(own.id),
                        onFocused = { onRowFocused(own.id) },
                        onClick = { onTogglePack(own.id) }
                    )
                }
                ListRow(
                    title = stringResource(R.string.content_filters_create_pack),
                    subtitle = stringResource(R.string.content_filters_create_subtitle),
                    trailing = RowTrailing.Add,
                    focusRequester = requester(CREATE_KEY),
                    onFocused = { onRowFocused(CREATE_KEY) },
                    onClick = { paneEntry.requestFocus() }
                )
                ListSectionHeader(
                    title = stringResource(R.string.content_filters_packs_heading),
                    detail = stringResource(R.string.content_filters_packs_order)
                )
                listed.forEach { entry ->
                    val id = entry.pack.id
                    ListRow(
                        title = entry.name,
                        subtitle = entry.description,
                        trailing = RowTrailing.Switch(id in settings.enabledPacks),
                        focusRequester = requester(id),
                        onFocused = { onRowFocused(id) },
                        onClick = { onTogglePack(id) }
                    )
                }
                // The last row clears the fade.
                Spacer(modifier = Modifier.height(LIST_FADE))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(LIST_FADE)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, FADE)))
            )
        }
        val onPaneFocus: (Boolean) -> Unit = { paneFocused = it }
        key(focusedKey) {
            when {
                focusedKey == PREVIEW_KEY && preview != null -> PreviewPane(
                    choices = previewChoices,
                    picked = pickedPreviewId,
                    avatarArt = preview.avatarArt,
                    backTo = focusedRow,
                    entryFocus = paneEntry,
                    onFocusChanged = onPaneFocus,
                    onPick = preview.onPick
                )
                focusedKey == MY_WORDS_KEY -> MyWordsPane(
                    profileName = profileName,
                    words = settings.myWords,
                    backTo = focusedRow,
                    entryFocus = paneEntry,
                    onFocusChanged = onPaneFocus,
                    onAdd = { input -> onEdit { it.addMyWords(input) } },
                    onRemove = { word -> onEdit { it.removeMyWord(word) } }
                )
                focusedKey == CREATE_KEY -> CreatePackPane(
                    profileName = profileName,
                    backTo = focusedRow,
                    entryFocus = paneEntry,
                    onFocusChanged = onPaneFocus,
                    onCreate = { name -> onCreatePack(name)?.let { pendingFocus = it } }
                )
                focusedOwn != null -> OwnPackPane(
                    pack = focusedOwn,
                    enabled = focusedOwn.id in settings.enabledPacks,
                    backTo = focusedRow,
                    entryFocus = paneEntry,
                    onFocusChanged = onPaneFocus,
                    onChange = { change -> onEdit { it.updateOwnPack(focusedOwn.id, change) } },
                    onDelete = {
                        onEdit { it.deleteOwnPack(focusedOwn.id) }
                        onRowFocused(CREATE_KEY)
                        pendingFocus = CREATE_KEY
                    },
                    tagSearch = tagSearch
                )
                focusedBuiltIn != null -> PackPane(
                    entry = focusedBuiltIn,
                    enabled = focusedBuiltIn.pack.id in settings.enabledPacks,
                    backTo = focusedRow,
                    focused = paneFocused,
                    onFocusChanged = onPaneFocus
                )
            }
        }
    }
}

/** An own pack's row line (stage 4): "Your pack · 6 words, 1 genre", the zero counts left out. */
@Composable
private fun ownPackSummary(counts: OwnPackCounts): String {
    val parts = listOfNotNull(
        counts.words.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.content_filters_word_count, it, it) },
        counts.genres.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.content_filters_genre_count, it, it) },
        counts.tags.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.content_filters_tag_count, it, it) }
    )
    return if (parts.isEmpty()) {
        stringResource(R.string.content_filters_own_pack_empty)
    } else {
        stringResource(R.string.content_filters_own_pack_summary, parts.joinToString())
    }
}

/** `.cftop`: avatar, the breadcrumb (last part white) and "N on · set on this TV only". */
@Composable
private fun PageTopRow(
    crumbs: List<String>,
    profileName: String,
    avatarColorHex: String,
    avatarArt: AvatarArt?,
    onCount: Int
) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = PAGE_START, end = PAGE_END, top = TOP_ROW_TOP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TOP_GAP)
    ) {
        ProfileAvatarCircle(name = profileName, colorHex = avatarColorHex, size = TOP_AVATAR, art = avatarArt)
        val lastCrumb = NuvioTheme.colors.TextPrimary
        Text(
            text = remember(crumbs, lastCrumb) {
                buildAnnotatedString {
                    crumbs.forEachIndexed { index, part ->
                        if (index > 0) append("  \u203A  ")
                        if (index == crumbs.lastIndex) withStyle(SpanStyle(color = lastCrumb)) { append(part) } else append(part)
                    }
                }
            },
            style = NuvioTheme.textStyles.tab,
            color = marquee.onImage2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.content_filters_page_count, onCount),
            style = NuvioTheme.textStyles.meta,
            color = marquee.onImage2,
            maxLines = 1
        )
    }
}

/** `.cfsec`: an uppercase group heading with its note at the end ("A to Z · all off to start"). */
@Composable
private fun ListSectionHeader(title: String, detail: String) {
    val eyebrow = NuvioTheme.textStyles.eyebrow
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ROW_PADDING_H, end = ROW_PADDING_H, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title.uppercase(), style = eyebrow, color = NuvioTheme.marquee.onImage3, maxLines = 1)
        Text(
            text = detail,
            style = eyebrow.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
            color = NuvioTheme.marquee.onImage3,
            maxLines = 1
        )
    }
}

/** What a list row ends with: a pack's switch, My words' count and chevron, Create a pack's plus. */
private sealed interface RowTrailing {
    data class Switch(val checked: Boolean) : RowTrailing
    data class Open(val value: String?) : RowTrailing
    data object Add : RowTrailing
}

/** `.cfr`: a row's name and line with its [trailing]; a white pill with dark content while focused. */
@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    trailing: RowTrailing,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(if (focused) NuvioPrimitives.white else Color.Transparent)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = rowTitleStyle(),
                color = if (focused) NuvioPrimitives.black else NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.Normal),
                color = if (focused) ROW_SUBTITLE_FOCUSED else ROW_SUBTITLE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val iconTint = if (focused) NuvioPrimitives.black else NuvioTheme.marquee.onImage3
        when (trailing) {
            is RowTrailing.Switch -> PackSwitch(checked = trailing.checked, focused = focused)
            is RowTrailing.Open -> {
                trailing.value?.let {
                    Text(
                        text = it,
                        style = NuvioTheme.textStyles.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = if (focused) ROW_SUBTITLE_FOCUSED else NuvioTheme.marquee.onImage2,
                        maxLines = 1
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = iconTint, modifier = Modifier.size(ROW_ICON))
            }
            RowTrailing.Add -> Icon(Icons.Filled.Add, contentDescription = null, tint = iconTint, modifier = Modifier.size(ROW_ICON))
        }
    }
}

@Composable
private fun rowTitleStyle() = NuvioTheme.textStyles.meta.copy(fontSize = 14.sp, lineHeight = 19.sp)

/** `.cfd h3`'s type. */
@Composable
internal fun paneTitleStyle() = NuvioTheme.textStyles.panelHeadName.copy(fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp)

/** `.l8sw` inside a `.cfr`: inverted on the focused (white) row. */
@Composable
private fun PackSwitch(checked: Boolean, focused: Boolean) {
    val track = when {
        checked && focused -> NuvioPrimitives.black
        checked -> NuvioPrimitives.white
        focused -> SWITCH_OFF_TRACK_FOCUSED
        else -> SWITCH_OFF_TRACK
    }
    val knob = when {
        focused -> NuvioPrimitives.white
        checked -> NuvioPrimitives.black
        else -> SWITCH_OFF_KNOB
    }
    Box(
        modifier = Modifier
            .size(width = SWITCH_WIDTH, height = SWITCH_HEIGHT)
            .background(track, CircleShape)
            .padding(horizontal = SWITCH_INSET),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(modifier = Modifier.size(SWITCH_KNOB).background(knob, CircleShape))
    }
}

/**
 * `.cfd`: the focused pack's name with ON / OFF, what it matches, and its groups in full. Focusable
 * only while its content overflows; then Up / Down scroll it and Left returns to [backTo]; ringed
 * while [focused].
 */
@Composable
private fun PackPane(
    entry: ListedPack,
    enabled: Boolean,
    backTo: FocusRequester,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { PANE_SCROLL_STEP.toPx() }
    // Where the running scroll is headed: a press while one runs adds to it, so held keys keep pace.
    var scrollTarget by remember { mutableStateOf(0f) }
    val contents = packContents(
        entry.pack,
        adultLabel = stringResource(R.string.content_filters_flag_adult),
        japaneseAnimationLabel = stringResource(R.string.content_filters_flag_japanese_animation)
    )
    LaunchedEffect(entry.pack.id) { scroll.scrollTo(0) }
    val scrollable = scroll.maxValue > 0
    val shape = RoundedCornerShape(PANE_RADIUS)
    Column(
        modifier = Modifier
            .padding(start = PANE_START, top = BODY_TOP, end = PAGE_END, bottom = PANE_BOTTOM)
            .fillMaxSize()
            .testTag(PACK_PANE_TAG)
            .clip(shape)
            .background(PANE_FILL)
            .then(if (focused) Modifier.border(marquee.ringWidth, marquee.ring, shape) else Modifier)
            .then(
                if (scrollable) {
                    Modifier
                        .focusProperties { left = backTo }
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val by = when (event.key) {
                                Key.DirectionDown -> step
                                Key.DirectionUp -> -step
                                else -> return@onPreviewKeyEvent false
                            }
                            val from = if (scroll.isScrollInProgress) scrollTarget else scroll.value.toFloat()
                            scrollTarget = (from + by).coerceIn(0f, scroll.maxValue.toFloat())
                            scope.launch { scroll.animateScrollTo(scrollTarget.toInt()) }
                            true
                        }
                        .focusable()
                } else {
                    Modifier
                }
            )
            .verticalScroll(scroll)
            .padding(horizontal = PANE_PADDING_H, vertical = PANE_PADDING_V),
        verticalArrangement = Arrangement.spacedBy(PANE_GAP)
    ) {
        PaneTitle(entry.name, enabled)
        PaneText(stringResource(R.string.content_filters_pack_explainer))
        ChipGroup(stringResource(R.string.content_filters_genres), null, contents.genres, ChipKind.Genre)
        ChipGroup(stringResource(R.string.content_filters_words), null, contents.words, ChipKind.Plain)
        ChipGroup(
            stringResource(R.string.content_filters_tags),
            stringResource(R.string.content_filters_tags_source),
            contents.tags,
            ChipKind.Plain
        )
        ChipGroup(
            stringResource(R.string.content_filters_never_hidden),
            stringResource(R.string.content_filters_never_hidden_sub),
            contents.neverHidden,
            ChipKind.Allowed
        )
        PaneText(stringResource(R.string.content_filters_foot), foot = true)
    }
}

/** `.cfd h3 .st`: ON in green, OFF in grey, outlined. */
@Composable
internal fun StateTag(on: Boolean) {
    val color = if (on) NuvioPrimitives.successOnImage else NuvioTheme.marquee.onImage3
    Text(
        text = stringResource(if (on) R.string.content_filters_state_on else R.string.content_filters_state_off),
        style = NuvioTheme.textStyles.eyebrow.copy(lineHeight = 16.sp, letterSpacing = 1.sp),
        color = color,
        modifier = Modifier
            .border(1.dp, if (on) STATE_ON_BORDER else STATE_OFF_BORDER, RoundedCornerShape(STATE_TAG_RADIUS))
            .padding(horizontal = 6.dp)
    )
}

private enum class ChipKind { Plain, Genre, Allowed }

/** `.grp` + `.cfc`: a heading (with its [note]) and every entry as a chip; nothing when [entries] is empty. */
@Composable
private fun ChipGroup(title: String, note: String?, entries: List<String>, kind: ChipKind) {
    if (entries.isEmpty()) return
    val marquee = NuvioTheme.marquee
    Column(verticalArrangement = Arrangement.spacedBy(PANE_GAP)) {
        GroupHeading(title, note)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
            verticalArrangement = Arrangement.spacedBy(CHIP_GAP)
        ) {
            entries.forEach { entry ->
                val (fill, text) = when (kind) {
                    ChipKind.Plain -> CHIP_FILL to marquee.onImage2
                    ChipKind.Genre -> GENRE_CHIP_FILL to NuvioPrimitives.white
                    ChipKind.Allowed -> ALLOW_CHIP_FILL to ALLOW_CHIP_TEXT
                }
                Box(
                    modifier = Modifier
                        .height(CHIP_HEIGHT)
                        .clip(RoundedCornerShape(CHIP_HEIGHT / 2))
                        .background(fill)
                        .padding(horizontal = CHIP_PADDING_H),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = entry, style = NuvioTheme.textStyles.caption, color = text, maxLines = 1)
                }
            }
        }
    }
}
