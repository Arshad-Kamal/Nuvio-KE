package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.ui.components.marquee.MarqueeCard
import com.nuvio.tv.ui.theme.NuvioTheme

/** The bundled Creatures' category key (Spec 44 D1); pinned right after All and My photos. */
internal const val CREATURES_CATEGORY = "creatures"

/** The My photos category key (Spec 44 D4); pinned right after All. */
internal const val MY_PHOTOS_CATEGORY = "my_photos"

private val PinnedAvatarCategories = listOf("anime", "animation", "tv", "movie", "gaming")

/** One picker tile: a server catalog avatar, a bundled creature or a My photo (Spec 44). [key] is unique across kinds. */
@Immutable
sealed interface AvatarTile {
    val key: String
    val displayName: String
    val category: String
    val art: AvatarArt
    val colorHex: String?
    val memberOnly: Boolean

    data class Server(val item: AvatarCatalogItem) : AvatarTile {
        override val key: String get() = serverKey(item.id)
        override val displayName: String get() = item.displayName
        override val category: String get() = item.category
        override val art: AvatarArt get() = AvatarArt.Image(item.imageUrl)
        override val colorHex: String? get() = item.bgColor
        override val memberOnly: Boolean get() = item.memberOnly
    }

    data class Creature(val creature: BundledCreature) : AvatarTile {
        override val key: String get() = creatureKey(creature.slug)
        override val displayName: String get() = creature.displayName
        override val category: String get() = CREATURES_CATEGORY
        override val art: AvatarArt get() = AvatarArt.Creature(creature)
        override val colorHex: String get() = creature.colorHex
        override val memberOnly: Boolean get() = false
    }

    /** A My photo avatar; [displayName] is its added date, formatted by the host. */
    data class Photo(val photo: MyPhoto, override val displayName: String) : AvatarTile {
        override val key: String get() = photoKey(photo.id)
        override val category: String get() = MY_PHOTOS_CATEGORY
        override val art: AvatarArt get() = AvatarArt.Photo(photo)
        override val colorHex: String? get() = null
        override val memberOnly: Boolean get() = false
    }

    companion object {
        fun serverKey(id: String): String = "server:$id"
        fun creatureKey(slug: String): String = "creature:$slug"
        fun photoKey(id: String): String = "photo:$id"
    }
}

/**
 * The editor's avatar picker (u10 stages 7, 8): category pills over a fixed grid of tiles.
 * [onTileFocused] gets the focused tile, and null once focus leaves the grid (or the grid
 * leaves composition), so the editor preview falls back to the selection. Holding OK or MENU on
 * a My photo tile calls [onPhotoLongPress] (the Use / Remove panel, stage 7). With
 * [onAddFromPhone], an Add from phone tile leads All and My photos (Spec 44 stage 2; My photos then
 * shows even while empty); [onAddFromPhoneFocused] follows its focus.
 */
@Composable
fun AvatarPickerGrid(
    tiles: List<AvatarTile>,
    selectedKey: String?,
    onTileSelected: (AvatarTile) -> Unit,
    onTileFocused: ((AvatarTile?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onPhotoLongPress: (AvatarTile.Photo) -> Unit = {},
    onAddFromPhone: (() -> Unit)? = null,
    onAddFromPhoneFocused: (Boolean) -> Unit = {}
) {
    val tokens = NuvioTheme.marquee.profiles
    val hasAddTile = onAddFromPhone != null
    val categories = remember(tiles, hasAddTile) {
        buildList {
            add("all")
            if (hasAddTile || tiles.any { it is AvatarTile.Photo }) add(MY_PHOTOS_CATEGORY)
            if (tiles.any { it is AvatarTile.Creature }) add(CREATURES_CATEGORY)
            if (tiles.any { it.memberOnly }) add("supporter")

            val normalizedCategories = tiles
                .filterNot { it.memberOnly }
                .mapNotNull { tile -> tile.category.trim().takeIf { it.isNotEmpty() } }
                .filterNot { it.equals(CREATURES_CATEGORY, ignoreCase = true) || it.equals(MY_PHOTOS_CATEGORY, ignoreCase = true) }
                .distinct()

            PinnedAvatarCategories.forEach { category ->
                if (normalizedCategories.any { it.equals(category, ignoreCase = true) }) {
                    add(category)
                }
            }

            normalizedCategories
                .filterNot { category ->
                    PinnedAvatarCategories.any { it.equals(category, ignoreCase = true) }
                }
                .sortedBy { it.lowercase() }
                .forEach(::add)
        }
    }
    var selectedCategory by remember { mutableStateOf("all") }
    val categoryRequesters = remember(categories) {
        categories.associateWith { FocusRequester() }
    }

    LaunchedEffect(categories) {
        if (selectedCategory !in categories) {
            selectedCategory = "all"
        }
    }

    val latestOnTileFocused by rememberUpdatedState(onTileFocused)
    val latestOnAddFocused by rememberUpdatedState(onAddFromPhoneFocused)
    DisposableEffect(Unit) {
        onDispose {
            latestOnTileFocused?.invoke(null)
            latestOnAddFocused(false)
        }
    }

    val filteredTiles = remember(tiles, selectedCategory) {
        when (selectedCategory) {
            "all" -> tiles
            "supporter" -> tiles.filter { it.memberOnly }
            else -> tiles.filter {
                !it.memberOnly && it.category.equals(selectedCategory, ignoreCase = true)
            }
        }
    }
    val tileRequesters = remember(filteredTiles) {
        filteredTiles.associate { it.key to FocusRequester() }
    }
    // Before the LaunchedEffect above resets a vanished category, its requester is gone too.
    val selectedCategoryRequester = categoryRequesters[selectedCategory]
    val showAddTile = onAddFromPhone != null &&
        (selectedCategory == "all" || selectedCategory == MY_PHOTOS_CATEGORY)
    val addTileRequester = remember { FocusRequester() }
    val firstTileRequester = if (showAddTile) {
        addTileRequester
    } else {
        filteredTiles.firstOrNull()?.let { tileRequesters[it.key] }
    }
    val tileIndexOffset = if (showAddTile) 1 else 0

    Column(modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.pickerChipGap),
            verticalArrangement = Arrangement.spacedBy(tokens.pickerChipGap)
        ) {
            categories.forEach { category ->
                val downTarget = if (selectedCategory == category) firstTileRequester else null
                PanelPill(
                    text = avatarCategoryLabel(category),
                    selected = selectedCategory == category,
                    enabled = true,
                    horizontalPadding = NuvioTheme.marquee.playerPanel.segmentPaddingHorizontal,
                    onClick = { selectedCategory = category },
                    modifier = Modifier
                        .focusRequester(categoryRequesters.getValue(category))
                        .then(
                            if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
                        )
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(tokens.pickerColumns),
            contentPadding = PaddingValues(vertical = tokens.pickerGridPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(tokens.pickerGapHorizontal),
            verticalArrangement = Arrangement.spacedBy(tokens.pickerGapVertical),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.pickerChipGap)
                // Moving between tiles keeps focus inside the grid; leaving it clears the preview.
                .onFocusChanged { if (!it.hasFocus) latestOnTileFocused?.invoke(null) }
        ) {
            if (showAddTile) {
                item(key = ADD_FROM_PHONE_KEY) {
                    AddFromPhoneGridItem(
                        focusRequester = addTileRequester,
                        upFocusRequester = selectedCategoryRequester,
                        onFocusChanged = { focused ->
                            latestOnAddFocused(focused)
                            if (focused) latestOnTileFocused?.invoke(null)
                        },
                        onClick = onAddFromPhone
                    )
                }
            }
            itemsIndexed(filteredTiles, key = { _, tile -> tile.key }) { index, tile ->
                AvatarGridItem(
                    tile = tile,
                    isSelected = tile.key == selectedKey,
                    focusRequester = tileRequesters.getValue(tile.key),
                    upFocusRequester = if (index + tileIndexOffset < tokens.pickerColumns) selectedCategoryRequester else null,
                    onFocused = { latestOnTileFocused?.invoke(tile) },
                    onClick = { onTileSelected(tile) },
                    onLongPress = (tile as? AvatarTile.Photo)?.let { photo -> { onPhotoLongPress(photo) } }
                )
            }
        }
    }
}

/** One avatar (`.l10g .l10av`): the selected one in a dim ring; focused scales up with the white
 *  ring and a glow in the avatar's colour (`marquee.profiles` picker tokens). */
@Composable
private fun AvatarGridItem(
    tile: AvatarTile,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.profiles
    var isFocused by remember { mutableStateOf(false) }
    // No colour: an empty hex parses to the default blue.
    val colorHex = tile.colorHex.orEmpty()
    val avatarColor = remember(colorHex) { parseProfileColor(colorHex) }
    val selectedRing = remember(tokens.pickerSelectedRingWidth, tokens.pickerSelectedRing, tokens.pickerRingGap) {
        Border(
            border = BorderStroke(tokens.pickerSelectedRingWidth, tokens.pickerSelectedRing),
            inset = tokens.pickerRingGap,
            shape = CircleShape
        )
    }
    MarqueeCard(
        onClick = onClick,
        onLongPress = onLongPress,
        width = tokens.pickerAvatarSize,
        height = tokens.pickerAvatarSize,
        shape = CircleShape,
        ringGap = tokens.pickerRingGap,
        edge = if (isSelected) selectedRing else Border.None,
        focusedScale = tokens.pickerFocusScale,
        glowColor = if (isFocused) avatarColor.copy(alpha = marquee.glowCardAlpha) else Color.Transparent,
        // The card is the cell's root (centred, not stretched to the cell) so its focus zIndex
        // lifts the scaled ring and glow above the neighbouring cells.
        modifier = Modifier
            .wrapContentSize()
            .focusRequester(focusRequester)
            .then(
                if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
    ) {
        ProfileAvatarCircle(
            name = tile.displayName,
            colorHex = colorHex,
            size = tokens.pickerAvatarSize,
            art = tile.art
        )
    }
}

// Not a valid AvatarTile key (those carry a kind prefix), so it never collides.
private const val ADD_FROM_PHONE_KEY = "add-from-phone"

/**
 * Add from phone (sandbox u44 stage 2 `.u44add`): a phone-plus glyph on a faint disc with a thin
 * ring; focused, a brighter disc, the white ring and the picker's scale.
 */
@Composable
private fun AddFromPhoneGridItem(
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    var isFocused by remember { mutableStateOf(false) }
    val restingRing = remember {
        Border(border = BorderStroke(ADD_RING_WIDTH, Color.White.copy(alpha = ADD_RING_ALPHA)), shape = CircleShape)
    }
    MarqueeCard(
        onClick = onClick,
        width = tokens.pickerAvatarSize,
        height = tokens.pickerAvatarSize,
        shape = CircleShape,
        ringGap = tokens.pickerRingGap,
        edge = restingRing,
        containerColor = Color.White.copy(alpha = if (isFocused) ADD_FILL_FOCUSED_ALPHA else ADD_FILL_ALPHA),
        focusedScale = tokens.pickerFocusScale,
        modifier = Modifier
            .wrapContentSize()
            .focusRequester(focusRequester)
            .then(
                if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier
            )
            .onFocusChanged {
                if (isFocused != it.isFocused) {
                    isFocused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
            }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add_from_phone),
            contentDescription = stringResource(R.string.profile_add_from_phone),
            tint = Color.White,
            modifier = Modifier.align(Alignment.Center).size(ADD_ICON_SIZE)
        )
    }
}

// `.u44add`: fill white .06 (.16 focused), inset ring 1.5 px white .32, 24 px glyph.
private const val ADD_FILL_ALPHA = 0.06f
private const val ADD_FILL_FOCUSED_ALPHA = 0.16f
private const val ADD_RING_ALPHA = 0.32f
private val ADD_RING_WIDTH = 1.5.dp
private val ADD_ICON_SIZE = 24.dp

/** A catalog category's label (the pinned ones translated, others capitalised). */
@Composable
internal fun avatarCategoryLabel(category: String): String {
    return when (category.lowercase()) {
        "all" -> stringResource(R.string.profile_avatar_category_all)
        "supporter" -> stringResource(R.string.profile_avatar_category_supporter)
        CREATURES_CATEGORY -> stringResource(R.string.profile_avatar_category_creatures)
        MY_PHOTOS_CATEGORY -> stringResource(R.string.profile_avatar_category_my_photos)
        "anime" -> stringResource(R.string.profile_avatar_category_anime)
        "animation" -> stringResource(R.string.profile_avatar_category_animation)
        "movie" -> stringResource(R.string.profile_avatar_category_movie)
        "tv" -> stringResource(R.string.profile_avatar_category_tv)
        "gaming" -> stringResource(R.string.profile_avatar_category_gaming)
        else -> category.replaceFirstChar { it.uppercase() }
    }
}
