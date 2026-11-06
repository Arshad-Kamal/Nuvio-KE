package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.theme.NuvioTheme

/** The one background tile drawn as selected (Spec 44 Design "Editor draft": one pick at a time). */
sealed interface ProfileBackgroundPick {
    /** Normal: the profile colour (and avatar art) field. */
    data object Standard : ProfileBackgroundPick
    data class Catalog(val id: String) : ProfileBackgroundPick
    /** The profile's own custom background URL (set on the website). */
    data object Custom : ProfileBackgroundPick
    /** A TV-only background: bundled, or a My photo (44c). */
    data class Local(val pick: ProfileLookPick) : ProfileBackgroundPick
}

// The tile grid (sandbox u44 stage 6 `.u44bgs`: four 116 x 65 tiles, 12 apart, across the 500 dp
// picker). One dp under 116 so per-tile pixel rounding never wraps the fourth tile.
private const val TILES_PER_ROW = 4
private val TILE_WIDTH = 115.dp
private val TILE_HEIGHT = 65.dp
private val TILE_GAP_HORIZONTAL = 12.dp
private val TILE_GAP_VERTICAL = 10.dp
// `.u44bt i.add`: white .06 fill, 22 px glyph.
private const val ADD_FILL_ALPHA = 0.06f
private val ADD_ICON_SIZE = 22.dp

/**
 * The editor's Background tab: Normal, Add from phone (with [onAddFromPhone], Spec 44 stage 6), the
 * bundled backgrounds, the My photos backgrounds (hold OK or MENU: [onPhotoLongPress]), the
 * profile's custom URL, then the supporter catalog (as built). [selected] marks exactly one tile.
 * [onPickFocused] gets the focused tile's pick, and null once focus leaves the tiles (or the picker
 * leaves composition) or rests on Add from phone.
 */
@Composable
fun ProfileBackgroundPicker(
    backgrounds: List<ProfileBackgroundCatalogItem>,
    bundledBackgrounds: List<BundledBackground>,
    photoBackgrounds: List<MyPhoto>,
    customBackgroundUrl: String?,
    selected: ProfileBackgroundPick,
    standardBackgroundColor: Color,
    onStandardBackgroundSelected: () -> Unit,
    onBundledBackgroundSelected: (BundledBackground) -> Unit,
    onBackgroundSelected: (ProfileBackgroundCatalogItem) -> Unit,
    onCustomBackgroundSelected: () -> Unit,
    onPhotoBackgroundSelected: (MyPhoto) -> Unit,
    modifier: Modifier = Modifier,
    onPickFocused: (ProfileBackgroundPick?) -> Unit = {},
    onPhotoLongPress: (MyPhoto) -> Unit = {},
    onAddFromPhone: (() -> Unit)? = null
) {
    val latestOnPickFocused by rememberUpdatedState(onPickFocused)
    DisposableEffect(Unit) {
        onDispose { latestOnPickFocused(null) }
    }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            // Moving between tiles keeps focus inside the row; leaving it clears the preview.
            .onFocusChanged { if (!it.hasFocus) latestOnPickFocused(null) },
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP_VERTICAL),
        maxItemsInEachRow = TILES_PER_ROW
    ) {
        ProfileBackgroundItem(
            label = stringResource(R.string.profile_background_normal),
            pick = ProfileBackgroundPick.Standard,
            selected = selected,
            onFocused = latestOnPickFocused,
            onClick = onStandardBackgroundSelected
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                lerp(NuvioTheme.colors.BackgroundElevated, standardBackgroundColor, 0.3f),
                                lerp(NuvioTheme.colors.Background, standardBackgroundColor, 0.14f),
                                NuvioTheme.colors.Background
                            )
                        )
                    )
            )
        }
        if (onAddFromPhone != null) {
            ProfileBackgroundItem(
                label = stringResource(R.string.profile_add_from_phone),
                pick = null,
                selected = selected,
                onFocused = latestOnPickFocused,
                onClick = onAddFromPhone
            ) {
                // `.u44bt i.add`: the phone-plus glyph on a faint fill.
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = ADD_FILL_ALPHA)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_from_phone),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(ADD_ICON_SIZE)
                    )
                }
            }
        }
        bundledBackgrounds.forEach { background ->
            ProfileBackgroundItem(
                label = background.displayName,
                pick = ProfileBackgroundPick.Local(ProfileLookPick.Bundled(background.slug)),
                selected = selected,
                onFocused = latestOnPickFocused,
                onClick = { onBundledBackgroundSelected(background) }
            ) {
                BundledBackgroundImage(
                    background = background,
                    contentDescription = background.displayName,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        photoBackgrounds.forEach { photo ->
            // Keyed: after a Remove the next photo keeps its own tile state (focus, long-press flag).
            key(photo.id) {
                ProfileBackgroundItem(
                    label = stringResource(R.string.profile_my_photo),
                    pick = ProfileBackgroundPick.Local(ProfileLookPick.Photo(photo.id)),
                    selected = selected,
                    onFocused = latestOnPickFocused,
                    onClick = { onPhotoBackgroundSelected(photo) },
                    onLongPress = { onPhotoLongPress(photo) }
                ) {
                    PhotoBackgroundImage(
                        photo = photo,
                        contentDescription = stringResource(R.string.profile_my_photo),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        customBackgroundUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            ProfileBackgroundItem(
                label = stringResource(R.string.profile_background_custom),
                pick = ProfileBackgroundPick.Custom,
                selected = selected,
                onFocused = latestOnPickFocused,
                onClick = onCustomBackgroundSelected
            ) {
                CustomProfileBackgroundImage(
                    imageUrl = imageUrl,
                    contentDescription = stringResource(R.string.profile_background_custom),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        backgrounds.forEach { background ->
            ProfileBackgroundItem(
                label = background.displayName,
                pick = ProfileBackgroundPick.Catalog(background.id),
                selected = selected,
                onFocused = latestOnPickFocused,
                onClick = { onBackgroundSelected(background) }
            ) {
                ProfileBackgroundImage(
                    background = background,
                    contentDescription = background.displayName,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** One tile; [pick] null is an action (Add from phone): never selected, and its focus reports null. */
@Composable
private fun ProfileBackgroundItem(
    label: String,
    pick: ProfileBackgroundPick?,
    selected: ProfileBackgroundPick,
    onFocused: (ProfileBackgroundPick?) -> Unit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isSelected = pick != null && selected == pick
    val longPress = rememberMarqueeLongPress()
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(150),
        label = "profileBackgroundScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 3.dp else if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(120),
        label = "profileBackgroundBorder"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected || isFocused) Color.White else NuvioTheme.colors.TextSecondary,
        animationSpec = tween(120),
        label = "profileBackgroundLabel"
    )
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .width(TILE_WIDTH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused(pick)
            }
            .then(longPress.modifier(onLongPress))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { longPress.click(onClick) }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_HEIGHT)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .border(
                    border = if (isSelected || isFocused) {
                        NuvioTheme.focusRing.border(borderWidth)
                    } else {
                        BorderStroke(borderWidth, NuvioTheme.colors.Border)
                    },
                    shape = shape
                )
        ) {
            content()
        }

        Text(
            text = label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ProfileBackgroundImage(
    background: ProfileBackgroundCatalogItem,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(background.imageFile)
            .memoryCacheKey("profile-background-${background.id}-v${background.assetVersion}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .allowRgb565(false)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

/** A bundled background (a 1920x1080 WebP, [LocalBackgroundImage]). */
@Composable
fun BundledBackgroundImage(
    background: BundledBackground,
    contentDescription: String?,
    modifier: Modifier = Modifier
) = LocalBackgroundImage(background.drawable, contentDescription, modifier)

/** A My photos background (a 1920x1080 JPEG on this TV, [LocalBackgroundImage]). */
@Composable
fun PhotoBackgroundImage(
    photo: MyPhoto,
    contentDescription: String?,
    modifier: Modifier = Modifier
) = LocalBackgroundImage(photo.file, contentDescription, modifier)

/**
 * A background stored on the TV, decoded at the size it is drawn (a tile subsamples it; the full
 * screen gets it whole); RGB565 off, as the other backgrounds, so the gradients don't band.
 */
@Composable
private fun LocalBackgroundImage(data: Any, contentDescription: String?, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(data)
            .allowRgb565(false)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

@Composable
fun CustomProfileBackgroundImage(
    imageUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey("custom-profile-background-$imageUrl")
            .diskCacheKey("custom-profile-background-$imageUrl")
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowRgb565(false)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}
