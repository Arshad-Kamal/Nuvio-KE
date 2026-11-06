package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.LookAvatar
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.ui.theme.NuvioPrimitives

/** A stored profile / avatar colour (`#RRGGBB`); an unparsable value falls back to the default blue. */
internal fun parseProfileColor(colorHex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(NuvioPrimitives.blue500)

/** What an avatar circle draws (Spec 44). */
@Immutable
sealed interface AvatarArt {
    /** An opaque square image (server catalog, a website URL), cropped to the circle on the profile colour. */
    data class Image(val url: String) : AvatarArt

    /** A bundled transparent creature, inset on a disc in its own colour (sandbox u44 stage 1). */
    data class Creature(val creature: BundledCreature) : AvatarArt

    /** A My photo (a 512x512 JPEG on this TV), cropped to the circle like [Image]. */
    data class Photo(val photo: MyPhoto) : AvatarArt

    /** The Coil model to load. */
    val model: Any
        get() = when (this) {
            is Image -> url
            is Creature -> creature.drawable
            is Photo -> photo.file
        }
}

/** The art for a resolved avatar against [library]; null draws the initial on the profile colour. */
fun LookAvatar.toArt(library: LocalLookLibrary): AvatarArt? = when (this) {
    is LookAvatar.Synced -> AvatarArt.Image(url)
    is LookAvatar.Local -> when (val pick = pick) {
        is ProfileLookPick.Bundled -> BundledCreatures.bySlug[pick.slug]?.let(AvatarArt::Creature)
        is ProfileLookPick.Photo -> library.photosById[pick.id]?.let(AvatarArt::Photo)
    }
}

// The creature disc (sandbox `.u44d.fl`): a radial gradient of the creature colour toward black,
// lit from above centre; the art at 76% of the circle, a touch below centre.
private const val CREATURE_ART_FRACTION = 0.76f
private const val CREATURE_ART_DROP = 0.015f
private const val CREATURE_DISC_LIGHT = 0.62f
private const val CREATURE_DISC_DARK = 0.30f

/**
 * A profile avatar: [art] on the circle, else the name's initial on [colorHex]. A creature draws on
 * its own colour disc, so a TV-only pick never changes the synced profile colour (Spec 44 Design).
 */
@Composable
fun ProfileAvatarCircle(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    art: AvatarArt? = null,
    imageCrossfade: Boolean = true
) {
    val creature = (art as? AvatarArt.Creature)?.creature
    val avatarColor = parseProfileColor(creature?.colorHex ?: colorHex)
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val fontSize = (size.value * 0.4f).sp
    val context = LocalContext.current
    val imageRequest = remember(context, art, imageCrossfade) {
        art?.let {
            ImageRequest.Builder(context)
                .data(it.model)
                .crossfade(imageCrossfade)
                .build()
        }
    }

    val disc = if (creature != null) {
        Modifier.drawWithCache {
            val brush = Brush.radialGradient(
                colors = listOf(
                    lerp(Color.Black, avatarColor, CREATURE_DISC_LIGHT),
                    lerp(Color.Black, avatarColor, CREATURE_DISC_DARK)
                ),
                // `this.size`: the draw size, not the composable's `size: Dp` parameter.
                center = Offset(this.size.width * 0.5f, this.size.height * 0.35f),
                // CSS `circle` = farthest corner from (50%, 35%).
                radius = this.size.minDimension * 0.82f
            )
            onDrawBehind { drawRect(brush) }
        }
    } else {
        Modifier.background(avatarColor, CircleShape)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(disc),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = name,
                modifier = if (creature != null) {
                    Modifier
                        .size(size * CREATURE_ART_FRACTION)
                        .offset(y = size * CREATURE_ART_DROP)
                } else {
                    Modifier
                        .size(size)
                        .clip(CircleShape)
                },
                contentScale = if (creature != null) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
