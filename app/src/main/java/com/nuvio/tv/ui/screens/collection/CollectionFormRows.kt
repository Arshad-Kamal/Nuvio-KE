package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowValue
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.marqueeFormRowValueStyle
import com.nuvio.tv.ui.components.rememberFolderCoverFallback
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioMarqueeHomeTokens
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.onTvBackDown
import com.nuvio.tv.ui.util.requestFocusAfterFrames

// Spec 28 collections pieces: the `.l8r` text row (stages 10-11; the form row itself and its
// value / switch variants live in ui/components/marquee/MarqueeFormRow.kt),
// the `.l8s` list row with its badge and `.l8act` actions (stages 6, 10, 11), the `.l8fld` field
// pill (stages 8, 15), the `.l8top` header pill, and the folder mini tile.

/**
 * `.l8r` text row: the label, [display] (or [placeholder], dimmed, when [value] is blank) and a
 * chevron. OK opens the field (and the keyboard) on [value]; Done, Back or leaving the field
 * closes it, and only then is the edit passed to [onValueChange] (one update, not one per key).
 * Back and Done return focus to the row ([focusRequester]).
 */
@Composable
internal fun CollectionTextRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    display: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val marquee = NuvioTheme.marquee
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue()) }
    // The field reports "not focused" when it attaches; only a loss after focus closes it.
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (editing) fieldFocus.requestFocusAfterFrames()
    }
    fun finish(refocusRow: Boolean) {
        if (!editing) return
        editing = false
        keyboardController?.hide()
        if (draft.text != value) onValueChange(draft.text)
        if (refocusRow) focusRequester.requestFocus()
    }

    MarqueeFormRow(
        label = label,
        modifier = modifier.focusRequester(focusRequester),
        onClick = {
            draft = TextFieldValue(value, selection = TextRange(value.length))
            fieldFocused = false
            editing = true
        }
    ) { lit ->
        val valueStyle = marqueeFormRowValueStyle
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            fieldFocused = true
                        } else if (fieldFocused) {
                            fieldFocused = false
                            finish(refocusRow = false)
                        }
                    }
                    // TV contract (W5.2): Back while typing leaves the field, never the screen.
                    .onTvBackDown { finish(refocusRow = true) },
                textStyle = valueStyle.copy(color = NuvioPrimitives.white),
                cursorBrush = SolidColor(marquee.ring),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = keyboardType == KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(onDone = { finish(refocusRow = true) }),
                decorationBox = { innerTextField ->
                    if (draft.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = valueStyle,
                            color = marquee.onImage3,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    innerTextField()
                }
            )
        } else {
            MarqueeFormRowValue(
                text = if (value.isBlank()) placeholder else display,
                dimmed = value.isBlank(),
                lit = lit
            )
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/** A URL [CollectionTextRow]: shows host + path (or None), edits the full URL. */
@Composable
internal fun CollectionUrlRow(
    label: String,
    url: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val value = url.orEmpty()
    CollectionTextRow(
        label = label,
        value = value,
        onValueChange = onValueChange,
        display = urlHostPath(value),
        placeholder = stringResource(R.string.action_none),
        modifier = modifier,
        focusRequester = focusRequester,
        keyboardType = KeyboardType.Uri
    )
}

/**
 * `.l8s` list row: [thumb], [title], [summary] (in [summaryColor]), [badge], chevron; OK runs
 * [onClick]. At rest an [outline] (if any) rims it. While focus is in the row or its [actions]
 * the row is lit and the actions (`.l8act` pills, if any) show under it; Down from the row
 * reaches them. Callers key the row by id so a moved row keeps its focused action.
 */
@Composable
internal fun CollectionListRow(
    title: String,
    summary: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    summaryColor: Color = NuvioTheme.marquee.onImage3,
    outline: Color? = null,
    badge: String? = null,
    thumb: @Composable () -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    var focusWithin by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(tokens.rowRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusWithin = it.hasFocus }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .clip(shape)
                .background(if (focusWithin) marquee.glassFill else tokens.rowFill)
                .then(
                    when {
                        focusWithin -> Modifier.border(marquee.ringWidth, marquee.ring, shape)
                        outline != null -> Modifier.border(tokens.sourceOutlineWidth, outline, shape)
                        else -> Modifier
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = tokens.rowPaddingHorizontal, vertical = tokens.rowPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(tokens.rowInnerGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            thumb()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = NuvioTheme.textStyles.button,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = summary,
                    style = NuvioTheme.textStyles.caption,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (badge != null) CollectionBadge(text = badge, color = marquee.onImage2)
            MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
        }
        if (focusWithin && actions != null) {
            Row(
                modifier = Modifier.padding(start = tokens.rowPaddingHorizontal),
                horizontalArrangement = Arrangement.spacedBy(tokens.actionGap),
                content = actions
            )
        }
    }
}

/** `.bdg` / PINNED: an uppercase tech-badge label on a glass chip. */
@Composable
internal fun CollectionBadge(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.techBadge,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .background(NuvioTheme.marquee.glassFill, RoundedCornerShape(NuvioTheme.radii.xs))
            .padding(horizontal = NuvioTheme.spacing.xs)
    )
}

/**
 * `.l8act` pill: a focused list row's action; also the import panel's Fetch URL / Load file. A
 * [dimmed] action (Move up on the first row) stays focusable, so focus never drops, and OK does
 * nothing.
 */
@Composable
internal fun CollectionRowAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    dimmed: Boolean = false
) {
    val tokens = NuvioTheme.marquee.collections
    MarqueeGlassTextButton(
        label = label,
        onClick = { if (!dimmed) onClick() },
        modifier = modifier,
        height = tokens.actionHeight,
        horizontalPadding = tokens.actionPaddingHorizontal,
        textStyle = NuvioTheme.textStyles.panelPill,
        restingLabelColor = when {
            dimmed -> NuvioTheme.marquee.onImage3
            destructive -> NuvioPrimitives.errorOnImage
            else -> NuvioPrimitives.white
        }
    )
}

/**
 * `.l8fld` pill (stages 8, 15): [icon], then the field on [value] when [onValueChange] is set
 * ([placeholder] while empty; IME Done runs [onAction]; Back leaves the field for the action), or
 * [value] as a note when not; then the `.l8act` [actionLabel] button ([actionFocus]). Lit like
 * the search field while the field has focus.
 */
@Composable
internal fun CollectionFieldPill(
    icon: ImageVector,
    value: String,
    onValueChange: ((String) -> Unit)?,
    placeholder: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionFocus: FocusRequester,
    modifier: Modifier = Modifier,
    fieldFocus: FocusRequester = remember { FocusRequester() },
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val marquee = NuvioTheme.marquee
    val search = marquee.search
    val tokens = marquee.collections
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    val lit = onValueChange != null && fieldFocused
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(tokens.fieldHeight)
            .background(if (lit) search.fieldFocusedFill else search.fieldFill, CircleShape)
            .border(
                width = if (lit) marquee.ringWidth else NuvioTheme.spacing.hairline,
                color = if (lit) marquee.ring else search.fieldEdge,
                shape = CircleShape
            )
            .padding(start = tokens.fieldPaddingStart, end = search.fieldPaddingEnd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = marquee.onImage3,
            modifier = Modifier.size(search.iconSize)
        )
        Spacer(modifier = Modifier.width(search.fieldIconGap))
        if (onValueChange != null) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    // TV contract (W5.2): Back while typing leaves the field for the action; the
                    // next Back closes the panel.
                    .onTvBackDown {
                        keyboardController?.hide()
                        actionFocus.requestFocus()
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioPrimitives.white),
                cursorBrush = SolidColor(marquee.ring),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = keyboardType == KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboardController?.hide()
                    onAction()
                }),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = marquee.onImage3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = marquee.onImage2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.width(search.fieldIconGap))
        CollectionRowAction(
            label = actionLabel,
            onClick = onAction,
            modifier = Modifier.focusRequester(actionFocus)
        )
    }
}

/** Stage 6 `.l8top`: a compact glass pill (also the import panel's and the editor's Cancel). */
@Composable
internal fun CollectionsHeaderButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.collections
    MarqueeGlassTextButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        height = NuvioTheme.marquee.primaryButton.compactHeight,
        horizontalPadding = tokens.headerButtonPaddingHorizontal,
        textStyle = NuvioTheme.textStyles.panelButtonSmall
    )
}

/**
 * A folder's mini tile size in a preview whose poster tile is [posterWidth] x [posterHeight]: the
 * landscape tile [landscapeWidth] x [landscapeHeight] when the row is landscape (Home's rule), else the
 * folder's own Tile shape at the poster height (Spec 41 D5; square, or Home's 299:168 wide card).
 */
internal fun folderPreviewTileSize(
    tileShape: PosterShape,
    landscapeOverride: Boolean,
    posterWidth: Dp,
    posterHeight: Dp,
    landscapeWidth: Dp,
    landscapeHeight: Dp,
    home: NuvioMarqueeHomeTokens
): DpSize = if (landscapeOverride) {
    DpSize(landscapeWidth, landscapeHeight)
} else {
    when (tileShape) {
        PosterShape.POSTER -> DpSize(posterWidth, posterHeight)
        PosterShape.SQUARE -> DpSize(posterHeight, posterHeight)
        PosterShape.LANDSCAPE -> DpSize(posterHeight * (home.wideCardWidth / home.portraitCardHeight), posterHeight)
    }
}

/**
 * A folder tile in small: [imageUrl] cropped, else [emoji] centred, else the bare fill. A failed
 * image falls back to the emoji and joins the shared broken-image list, as Home's card does.
 */
@Composable
internal fun FolderMiniTile(
    imageUrl: String?,
    emoji: String?,
    width: Dp,
    height: Dp,
    radius: Dp,
    emojiStyle: TextStyle
) {
    val fallback = rememberFolderCoverFallback(imageUrl)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(radius))
            .background(NuvioTheme.marquee.collections.thumbFill),
        contentAlignment = Alignment.Center
    ) {
        when {
            imageUrl != null && !fallback.failed -> AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { fallback.onCoverError(imageUrl) },
                modifier = Modifier.fillMaxSize()
            )
            !emoji.isNullOrBlank() -> Text(text = emoji, style = emojiStyle)
        }
    }
}

/**
 * A URL as its host + path ("elephantsdream.org/stills/proog.jpg"): scheme, "www.", query and
 * fragment dropped. Rows show this; the field edits the full URL.
 */
internal fun urlHostPath(url: String): String = url.trim()
    .substringBefore('#')
    .substringBefore('?')
    .replaceFirst(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
    .removePrefix("www.")
    .trimEnd('/')
