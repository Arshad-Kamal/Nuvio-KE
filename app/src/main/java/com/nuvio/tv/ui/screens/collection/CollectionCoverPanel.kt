package com.nuvio.tv.ui.screens.collection

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeValueRow
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelPill
import com.nuvio.tv.ui.components.PanelSegments
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Cover panel's pages, in segment order. */
private enum class CoverPage { NONE, EMOJI, IMAGE }

/** How the folder's cover reads: image (even a blank URL: image mode), emoji, or none. */
private fun CollectionFolder.coverPage(): CoverPage = when {
    coverImageUrl != null -> CoverPage.IMAGE
    !coverEmoji.isNullOrBlank() -> CoverPage.EMOJI
    else -> CoverPage.NONE
}

/**
 * The folder form's Cover row (stage 11): the cover as a value ("🎬  Emoji", the image URL's
 * host + path, or None) and a chevron; OK opens the Cover panel ([onClick]).
 */
@Composable
internal fun CoverRow(folder: CollectionFolder, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val emojiLabel = stringResource(R.string.collections_editor_cover_emoji)
    val imageLabel = stringResource(R.string.collections_editor_cover_image_url)
    val page = folder.coverPage()
    MarqueeValueRow(
        label = stringResource(R.string.collections_editor_cover),
        value = when (page) {
            CoverPage.IMAGE -> urlHostPath(folder.coverImageUrl.orEmpty()).ifBlank { imageLabel }
            CoverPage.EMOJI -> "${folder.coverEmoji}  $emojiLabel"
            CoverPage.NONE -> stringResource(R.string.collections_editor_cover_none)
        },
        dimmed = page == CoverPage.NONE,
        onClick = onClick,
        modifier = modifier
    )
}

/**
 * Stage 12, the Cover panel: None / Emoji / Image URL on top. None clears the cover; Emoji shows
 * the as-built categories as chips (OK picks one) over a 6-wide grid with the current emoji
 * ringed, where OK picks ([onPickEmoji]: sets it, clears the image URL, and the caller closes);
 * Image URL switches to image mode ([onImageMode], as built) and shows the URL row and a preview
 * in the folder's Tile shape, landscape when Card shape = Landscape ([landscape]). Opens on the
 * current emoji, the URL row, or
 * the segments. Back closes ([onDismiss]).
 */
@Composable
internal fun CoverPanel(
    folder: CollectionFolder,
    landscape: Boolean,
    onClear: () -> Unit,
    onPickEmoji: (String) -> Unit,
    onImageMode: () -> Unit,
    onImageUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val currentEmoji = folder.coverEmoji?.takeIf { it.isNotBlank() }
    var page by remember { mutableStateOf(folder.coverPage()) }
    // The category holding the current emoji; null when it is in none (set on the phone web
    // page or by an import), and then nothing is ringed and focus opens on the segments.
    val openingCategory = remember {
        emojiCategories.entries.firstOrNull { currentEmoji != null && currentEmoji in it.value }?.key
    }
    var category by remember { mutableStateOf(openingCategory ?: emojiCategories.keys.first()) }
    val segmentsFocus = remember { FocusRequester() }
    val emojiFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val initialFocus = remember {
        when (page) {
            CoverPage.EMOJI -> if (openingCategory != null) emojiFocus else segmentsFocus
            CoverPage.IMAGE -> urlFocus
            CoverPage.NONE -> segmentsFocus
        }
    }

    MarqueeSidePanel(onDismiss = onDismiss, initialFocus = initialFocus, fillHeight = true) {
        PanelEyebrow(text = stringResource(R.string.collections_editor_cover))
        MarqueeFormRow(label = stringResource(R.string.collections_editor_cover)) {
            PanelSegments(
                options = listOf(
                    stringResource(R.string.collections_editor_cover_none),
                    stringResource(R.string.collections_editor_cover_emoji),
                    stringResource(R.string.collections_editor_cover_image_url)
                ),
                selectedIndex = page.ordinal,
                onSelect = { index ->
                    val picked = CoverPage.entries[index]
                    when (picked) {
                        CoverPage.NONE -> onClear()
                        CoverPage.IMAGE -> onImageMode()
                        // Nothing changes until an emoji is picked.
                        CoverPage.EMOJI -> Unit
                    }
                    page = picked
                },
                modifier = Modifier.focusRequester(segmentsFocus)
            )
        }
        when (page) {
            CoverPage.EMOJI -> EmojiPage(
                category = category,
                onCategory = { category = it },
                currentEmoji = currentEmoji,
                emojiFocus = emojiFocus,
                onPick = onPickEmoji
            )
            CoverPage.IMAGE -> ImagePage(
                url = folder.coverImageUrl.orEmpty(),
                tileShape = folder.tileShape,
                landscape = landscape,
                urlFocus = urlFocus,
                onImageUrl = onImageUrl
            )
            CoverPage.NONE -> Unit
        }
    }
}

/** `.l8chips` category chips over the `.l8emo-g` grid of [category]'s emoji. */
@Composable
private fun ColumnScope.EmojiPage(
    @StringRes category: Int,
    onCategory: (Int) -> Unit,
    currentEmoji: String?,
    emojiFocus: FocusRequester,
    onPick: (String) -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    FlowRow(
        modifier = Modifier.padding(top = tokens.panelGap),
        horizontalArrangement = Arrangement.spacedBy(tokens.panelGap),
        verticalArrangement = Arrangement.spacedBy(tokens.panelGap)
    ) {
        emojiCategories.keys.forEach { key ->
            PanelPill(
                text = stringResource(key),
                selected = key == category,
                enabled = true,
                horizontalPadding = NuvioTheme.marquee.playerPanel.segmentPaddingHorizontal,
                onClick = { onCategory(key) }
            )
        }
    }
    val emojis = emojiCategories.getValue(category)
    // A new category starts at its top; the opening one starts at the current emoji's line.
    key(category) {
        val gridState = rememberLazyGridState(
            initialFirstVisibleItemIndex = currentEmoji?.let { emojis.indexOf(it) }?.coerceAtLeast(0) ?: 0
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(EMOJI_COLUMNS),
            state = gridState,
            modifier = Modifier.weight(1f).padding(top = tokens.panelGap),
            contentPadding = PaddingValues(horizontal = tokens.panelGap, vertical = tokens.emojiGap / 2),
            horizontalArrangement = Arrangement.spacedBy(tokens.emojiGap),
            verticalArrangement = Arrangement.spacedBy(tokens.emojiGap)
        ) {
            items(count = emojis.size, key = { emojis[it] }) { index ->
                val emoji = emojis[index]
                val current = emoji == currentEmoji
                EmojiCell(
                    emoji = emoji,
                    current = current,
                    onClick = { onPick(emoji) },
                    modifier = if (current) Modifier.focusRequester(emojiFocus) else Modifier
                )
            }
        }
    }
}

/** One `.l8emo-g` cell: [current] in glass with a ring, focused white and a little larger. */
@Composable
private fun EmojiCell(emoji: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(tokens.emojiCellRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                val scale = if (focused) tokens.emojiFocusScale else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                when {
                    focused -> NuvioPrimitives.white
                    current -> marquee.glassFill
                    else -> tokens.emojiCellFill
                }
            )
            .then(
                if (current && !focused) Modifier.border(marquee.ringWidth, tokens.emojiSelectedRing, shape) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, style = MaterialTheme.typography.headlineLarge)
    }
}

/** The cover URL row (edits the full URL) and the cover as the folder tile draws it. */
@Composable
private fun ImagePage(
    url: String,
    tileShape: PosterShape,
    landscape: Boolean,
    urlFocus: FocusRequester,
    onImageUrl: (String) -> Unit
) {
    val tokens = NuvioTheme.marquee.collections
    val tile = folderPreviewTileSize(
        tileShape, landscape, tokens.coverPreviewWidth, tokens.coverPreviewHeight,
        tokens.coverPreviewLandscapeWidth, tokens.coverPreviewLandscapeHeight, NuvioTheme.marquee.home
    )
    CollectionUrlRow(
        label = stringResource(R.string.collections_editor_cover_image_url),
        url = url,
        onValueChange = onImageUrl,
        modifier = Modifier.padding(top = tokens.panelGap),
        focusRequester = urlFocus
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = tokens.sectionTopPadding),
        contentAlignment = Alignment.Center
    ) {
        FolderMiniTile(
            imageUrl = url.takeIf { it.isNotBlank() },
            emoji = null,
            width = tile.width,
            height = tile.height,
            radius = tokens.previewTileRadius,
            emojiStyle = NuvioTheme.textStyles.panelValueLarge
        )
    }
}

private const val EMOJI_COLUMNS = 6

/** The as-built emoji, by category label, in chip order. */
private val emojiCategories: Map<Int, List<String>> = linkedMapOf(
    R.string.collections_editor_emoji_category_streaming to listOf("🎬", "🎭", "🎥", "📺", "🍿", "🎞️", "📽️", "🎦", "📡", "📻"),
    R.string.collections_editor_emoji_category_genres to listOf("💀", "👻", "🔪", "💣", "🚀", "🛸", "🧙", "🦸", "🧟", "🤖", "💘", "😂", "😱", "🤯", "🥺", "😈"),
    R.string.collections_editor_emoji_category_sports to listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏒", "🥊", "🏎️", "🏆", "🎯", "🏋️"),
    R.string.collections_editor_emoji_category_music to listOf("🎵", "🎶", "🎤", "🎸", "🥁", "🎹", "🎷", "🎺", "🎻", "🪗"),
    R.string.collections_editor_emoji_category_nature to listOf("🌍", "🌊", "🏔️", "🌋", "🌅", "🌙", "⭐", "🔥", "❄️", "🌈", "🌸", "🍀"),
    R.string.collections_editor_emoji_category_animals to listOf("🐕", "🐈", "🦁", "🐻", "🦊", "🐺", "🦅", "🐉", "🦋", "🐬", "🦈", "🐙"),
    R.string.collections_editor_emoji_category_food to listOf("🍕", "🍔", "🍣", "🍜", "🍩", "🍰", "🍷", "🍺", "☕", "🧁", "🌮", "🥗"),
    R.string.collections_editor_emoji_category_travel to listOf("✈️", "🚂", "🚗", "⛵", "🏖️", "🗼", "🏰", "🗽", "🎡", "🏕️", "🌆", "🛣️"),
    R.string.collections_editor_emoji_category_people to listOf("👨‍👩‍👧‍👦", "👫", "👶", "🧒", "👩", "👨", "🧓", "💃", "🕺", "🥷", "🧑‍🚀", "🧑‍🎨"),
    R.string.collections_editor_emoji_category_objects to listOf("📱", "💻", "🎮", "🕹️", "📷", "🔮", "💡", "🔑", "💎", "🎁", "📚", "✏️"),
    R.string.collections_editor_emoji_category_flags to listOf(
        "🏳️‍🌈", "🏴‍☠️",
        "🇦🇫", "🇦🇱", "🇩🇿", "🇦🇸", "🇦🇩", "🇦🇴", "🇦🇮", "🇦🇬", "🇦🇷", "🇦🇲", "🇦🇼", "🇦🇺",
        "🇦🇹", "🇦🇿", "🇧🇸", "🇧🇭", "🇧🇩", "🇧🇧", "🇧🇾", "🇧🇪", "🇧🇿", "🇧🇯", "🇧🇲", "🇧🇹",
        "🇧🇴", "🇧🇦", "🇧🇼", "🇧🇷", "🇧🇳", "🇧🇬", "🇧🇫", "🇧🇮", "🇰🇭", "🇨🇲", "🇨🇦", "🇨🇻",
        "🇨🇫", "🇹🇩", "🇨🇱", "🇨🇳", "🇨🇴", "🇰🇲", "🇨🇬", "🇨🇩", "🇨🇷", "🇨🇮", "🇭🇷", "🇨🇺",
        "🇨🇼", "🇨🇾", "🇨🇿", "🇩🇰", "🇩🇯", "🇩🇲", "🇩🇴", "🇪🇨", "🇪🇬", "🇸🇻", "🇬🇶", "🇪🇷",
        "🇪🇪", "🇸🇿", "🇪🇹", "🇫🇯", "🇫🇮", "🇫🇷", "🇬🇦", "🇬🇲", "🇬🇪", "🇩🇪", "🇬🇭", "🇬🇷",
        "🇬🇩", "🇬🇹", "🇬🇳", "🇬🇼", "🇬🇾", "🇭🇹", "🇭🇳", "🇭🇰", "🇭🇺", "🇮🇸", "🇮🇳", "🇮🇩",
        "🇮🇷", "🇮🇶", "🇮🇪", "🇮🇱", "🇮🇹", "🇯🇲", "🇯🇵", "🇯🇴", "🇰🇿", "🇰🇪", "🇰🇮", "🇰🇼",
        "🇰🇬", "🇱🇦", "🇱🇻", "🇱🇧", "🇱🇸", "🇱🇷", "🇱🇾", "🇱🇮", "🇱🇹", "🇱🇺", "🇲🇴", "🇲🇬",
        "🇲🇼", "🇲🇾", "🇲🇻", "🇲🇱", "🇲🇹", "🇲🇷", "🇲🇺", "🇲🇽", "🇫🇲", "🇲🇩", "🇲🇨", "🇲🇳",
        "🇲🇪", "🇲🇦", "🇲🇿", "🇲🇲", "🇳🇦", "🇳🇷", "🇳🇵", "🇳🇱", "🇳🇿", "🇳🇮", "🇳🇪", "🇳🇬",
        "🇰🇵", "🇲🇰", "🇳🇴", "🇴🇲", "🇵🇰", "🇵🇼", "🇵🇸", "🇵🇦", "🇵🇬", "🇵🇾", "🇵🇪", "🇵🇭",
        "🇵🇱", "🇵🇹", "🇵🇷", "🇶🇦", "🇷🇴", "🇷🇺", "🇷🇼", "🇰🇳", "🇱🇨", "🇻🇨", "🇼🇸", "🇸🇲",
        "🇸🇹", "🇸🇦", "🇸🇳", "🇷🇸", "🇸🇨", "🇸🇱", "🇸🇬", "🇸🇰", "🇸🇮", "🇸🇧", "🇸🇴", "🇿🇦",
        "🇰🇷", "🇸🇸", "🇪🇸", "🇱🇰", "🇸🇩", "🇸🇷", "🇸🇪", "🇨🇭", "🇸🇾", "🇹🇼", "🇹🇯", "🇹🇿",
        "🇹🇭", "🇹🇱", "🇹🇬", "🇹🇴", "🇹🇹", "🇹🇳", "🇹🇷", "🇹🇲", "🇹🇻", "🇺🇬", "🇺🇦", "🇦🇪",
        "🇬🇧", "🇺🇸", "🇺🇾", "🇺🇿", "🇻🇺", "🇻🇪", "🇻🇳", "🇾🇪", "🇿🇲", "🇿🇼"
    ),
    R.string.collections_editor_emoji_category_symbols to listOf("❤️", "💜", "💙", "💚", "💛", "🧡", "🖤", "🤍", "✅", "❌", "⚡", "💯")
)
