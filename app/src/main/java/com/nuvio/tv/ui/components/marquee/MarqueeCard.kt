@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

// Marquee card (spec §Components "Landscape card"): the focusable shell every Marquee screen's
// cards share, plus the overlay pieces drawn inside it. Content (art, markers) is the caller's.

private val CARD_SCRIM = Brush.verticalGradient(
    0.45f to Color.Transparent,
    1f to NuvioPrimitives.black.copy(alpha = 0.85f)
)
private val CHIP_FILL = NuvioPrimitives.black.copy(alpha = 0.60f)
private val PROGRESS_TRACK = NuvioPrimitives.white.copy(alpha = 0.25f)

/**
 * Focusable Marquee card: `marquee.cardRadius`, centre-pivot [focusedScale] (default
 * `marquee.cardScale`; 1 keeps the ring without the scale), `marquee.ring` and a glow in
 * [glowColor] when focused, drawn above its neighbours. [showFocusChrome] false drops ring +
 * scale (fast scroll); [ringGap] moves the ring that far outside the card (light cards, where a
 * ring on the edge would vanish); [shape] replaces the `cardRadius` corners (cast circles); [edge]
 * is the unfocused border. [onLongPress] fires on a held select key or MENU; the
 * release that follows never clicks. Callers put `focusRequester`/`onFocusChanged` in
 * [modifier] (they precede the card's focus target, CMP-FOCUS-02).
 */
@Composable
internal fun MarqueeCard(
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    glowColor: Color = Color.Transparent,
    containerColor: Color = Color.Transparent,
    showFocusChrome: Boolean = true,
    ringGap: Dp = 0.dp,
    shape: Shape? = null,
    edge: Border = Border.None,
    focusedScale: Float = NuvioTheme.marquee.cardScale,
    content: @Composable BoxScope.() -> Unit
) {
    val marquee = NuvioTheme.marquee
    var isFocused by remember { mutableStateOf(false) }
    val longPress = rememberMarqueeLongPress()
    val cardShape = shape ?: remember(marquee.cardRadius) { RoundedCornerShape(marquee.cardRadius) }
    val ring = remember(cardShape, marquee.ring, marquee.ringWidth, ringGap) {
        Border(border = BorderStroke(marquee.ringWidth, marquee.ring), inset = ringGap, shape = cardShape)
    }
    Card(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .size(width, height)
            .zIndex(if (isFocused) 1f else 0f)
            .onFocusChanged { isFocused = it.isFocused }
            .then(longPress.modifier(onLongPress)),
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = containerColor
        ),
        border = CardDefaults.border(border = edge, focusedBorder = if (showFocusChrome) ring else Border.None),
        scale = CardDefaults.scale(focusedScale = if (showFocusChrome) focusedScale else 1f),
        glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = glowColor, elevation = marquee.glowCardElevation))
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(cardShape), content = content)
    }
}

// Action tile content (approved sandbox `u4-search-discover-marquee` stage 11).
private val ACTION_TILE_ICON = 26.dp
private val ACTION_TILE_GAP = 8.dp

/**
 * Card-size glass action tile at the end of a row (Discover "Load more"; stage 11): `glassFill`,
 * [icon] over [label] in white; focused = white fill, black content, plus the card's scale, ring
 * and [accent] glow. [focusable] false shows it without taking focus (a load in progress). [detail]
 * is a small line under the label (Library's See all: "18 titles", u7 stage 4).
 */
@Composable
internal fun MarqueeActionTile(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    accent: () -> Color,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    detail: String? = null
) {
    val marquee = NuvioTheme.marquee
    var isFocused by remember { mutableStateOf(false) }
    val content = if (isFocused) NuvioPrimitives.black else NuvioPrimitives.white
    MarqueeCard(
        onClick = onClick,
        width = width,
        height = height,
        glowColor = if (isFocused) accent().copy(alpha = marquee.glowCardAlpha) else Color.Transparent,
        containerColor = if (isFocused) NuvioPrimitives.white else marquee.glassFill,
        modifier = modifier
            .focusProperties { canFocus = focusable }
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ACTION_TILE_GAP, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(ACTION_TILE_ICON))
            }
            Text(
                text = label,
                style = NuvioTheme.textStyles.cardLabel,
                color = content,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = NuvioTheme.textStyles.caption,
                    color = if (isFocused) NuvioPrimitives.black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/** Bottom scrim + `cardTitle` bottom-left (12 dp inset, 14 dp from the bottom). */
@Composable
internal fun BoxScope.MarqueeCardTitle(text: String) {
    Box(modifier = Modifier.matchParentSize().background(CARD_SCRIM))
    Text(
        text = text,
        style = NuvioTheme.textStyles.cardLabel,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
    )
}

/**
 * The caption under a card (Library's History cards, stage 7 `.hcard`): [title] in `cardLabel`,
 * then [detail] (when it was watched) in `caption`, [gap] under the card. Both lines always take
 * their height: Library's band geometry counts them.
 */
@Composable
internal fun MarqueeCardCaption(title: String, detail: String?, gap: Dp) {
    Text(
        text = title,
        style = NuvioTheme.textStyles.cardLabel,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = gap)
    )
    Text(
        text = detail.orEmpty(),
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * A credit's caption under its card (person page, Spec 29 stage 3, T8): [role] in [accent]
 * ("as Tilda"; the role is cut first), then " · [detail]" in `onImage3` ("4 eps · 2015"), one
 * `caption` line [gap] under the card. The line always takes its height. [accent] is read at draw
 * time: it animates on every hero settle.
 */
@Composable
internal fun MarqueeCardRoleCaption(role: String?, detail: String?, accent: () -> Color, gap: Dp) {
    val style = NuvioTheme.textStyles.caption
    val muted = NuvioTheme.marquee.onImage3
    Row(modifier = Modifier.padding(top = gap)) {
        BasicText(
            text = role.orEmpty(),
            style = style.copy(fontWeight = FontWeight.SemiBold),
            color = { accent() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (detail != null) {
            Text(
                text = if (role != null) " \u00B7 $detail" else detail,
                style = style,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** [MarqueeCardCaption]'s height under the card: [gap] plus its two lines. */
@Composable
internal fun marqueeCardCaptionHeight(gap: Dp): Dp {
    val styles = NuvioTheme.textStyles
    return gap + with(LocalDensity.current) { styles.cardLabel.lineHeight.toDp() + styles.caption.lineHeight.toDp() }
}

/** Corner chip [inset] in (S1 E3, NEXT; top-left by default): [fill] black .60, radius `xs`, `chip` style. */
@Composable
internal fun BoxScope.MarqueeCardChip(
    text: String,
    color: Color = NuvioTheme.colors.TextPrimary,
    fill: Color = CHIP_FILL,
    alignment: Alignment = Alignment.TopStart,
    inset: Dp = 10.dp
) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.chip,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .align(alignment)
            .padding(inset)
            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Watched tick, top-right [inset] in: a [size] white circle with a black check (fixed, not the palette's). */
@Composable
internal fun BoxScope.MarqueeCardTick(
    contentDescription: String?,
    inset: Dp = 10.dp,
    size: Dp = 20.dp,
    iconSize: Dp = 14.dp
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(inset)
            .size(size)
            .background(NuvioPrimitives.white, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = contentDescription,
            tint = NuvioPrimitives.black,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Progress bar inset [inset] from the sides and [bottom] from the bottom; [fill] is the played part. */
@Composable
internal fun BoxScope.MarqueeCardProgress(fraction: Float, fill: Color, inset: Dp = 12.dp, bottom: Dp = 7.dp) {
    val barHeight = NuvioTheme.marquee.progressHeight
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = inset, end = inset, bottom = bottom)
            .fillMaxWidth()
            .height(barHeight)
            .clip(RoundedCornerShape(barHeight))
            .background(PROGRESS_TRACK)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(barHeight)
                .background(fill)
        )
    }
}
