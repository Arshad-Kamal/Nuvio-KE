package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * `.l8r` form row (u8 collections, u11 settings): the label (after [leadingIcon] when set, Spec 52
 * `.s52ki`; followed by [labelBadge] when set, Spec 52 `.s52new`; + [helper] wrapping under it, or
 * [helperContent]), then [trailing] at the end, told
 * whether the row is lit. Lit (glass fill + ring) while focus is in it. With [onClick] the row
 * itself is focusable (put a focusRequester in [modifier]); without, only its trailing controls
 * are (e.g. segments).
 *
 * A dimmed row (`enabled = false`) draws faded and STAYS focusable so D-pad paths never skip it;
 * OK does nothing and the caller puts the reason in [helper]. Never use `clickable(enabled =
 * false)` for this: it drops focusability.
 */
@Composable
internal fun MarqueeFormRow(
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    minHeight: Dp = NuvioTheme.marquee.formRow.minHeight,
    helperContent: (@Composable (lit: Boolean) -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    labelBadge: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.(lit: Boolean) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.formRow
    var lit by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .onFocusChanged { lit = it.hasFocus }
            .then(if (enabled) Modifier else Modifier.alpha(tokens.dimAlpha).semantics { disabled() })
            .formRowSurface(lit)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (enabled) onClick() }
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = tokens.paddingHorizontal, vertical = tokens.paddingVertical),
        horizontalArrangement = Arrangement.spacedBy(tokens.innerGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.widthIn(max = tokens.labelMaxWidth)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = if (lit) NuvioPrimitives.white else marquee.onImage2,
                        modifier = Modifier
                            .padding(end = tokens.leadingIconGap)
                            .size(tokens.leadingIconSize)
                    )
                }
                Text(
                    text = label,
                    style = NuvioTheme.textStyles.button,
                    color = if (destructive) tokens.destructiveText else NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                labelBadge?.invoke()
            }
            if (helperContent != null) {
                helperContent(lit)
            } else if (!helper.isNullOrBlank()) {
                Text(text = helper, style = NuvioTheme.textStyles.panelHelper, color = marquee.onImage3)
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(tokens.innerGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            trailing(lit)
        }
    }
}

/** The `.l8r` surface: [NuvioMarqueeFormRowTokens.fill] at rest, glass + ring while [lit] (rows and Settings cards). */
@Composable
internal fun Modifier.formRowSurface(lit: Boolean): Modifier {
    val marquee = NuvioTheme.marquee
    val shape = RoundedCornerShape(marquee.formRow.radius)
    return clip(shape)
        .background(if (lit) marquee.glassFill else marquee.formRow.fill)
        .then(if (lit) Modifier.border(marquee.ringWidth, marquee.ring, shape) else Modifier)
}

/**
 * `.l8r` row that opens something: the label, [value] ([dimmed] for "None", [emphasized] white
 * at rest for a set value, Spec 52 `.vl.set`) and [trailingIcon].
 */
@Composable
internal fun MarqueeValueRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null,
    dimmed: Boolean = false,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    /** Null draws nothing after the value (a row that shows a value without opening anything). */
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    minHeight: Dp = NuvioTheme.marquee.formRow.minHeight,
    helperContent: (@Composable (lit: Boolean) -> Unit)? = null,
    leadingIcon: ImageVector? = null
) {
    MarqueeFormRow(
        label = label,
        modifier = modifier,
        helper = helper,
        onClick = onClick,
        enabled = enabled,
        destructive = destructive,
        minHeight = minHeight,
        helperContent = helperContent,
        leadingIcon = leadingIcon
    ) { lit ->
        if (!value.isNullOrBlank()) MarqueeFormRowValue(text = value, dimmed = dimmed, lit = lit || emphasized)
        if (trailingIcon != null) MarqueeFormRowIcon(trailingIcon)
    }
}

/** `.l8r` + `.l8sw`: OK on the row flips [checked] (a dimmed row ignores OK). */
@Composable
internal fun MarqueeSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null,
    enabled: Boolean = true,
    minHeight: Dp = NuvioTheme.marquee.formRow.minHeight
) {
    MarqueeFormRow(
        label = label,
        modifier = modifier,
        helper = helper,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        minHeight = minHeight
    ) {
        MarqueeSwitch(checked = checked)
    }
}

/** `.l8sw`: the switch track and knob (white + black when on). */
@Composable
internal fun MarqueeSwitch(checked: Boolean) {
    val tokens = NuvioTheme.marquee.formRow
    Box(
        modifier = Modifier
            .size(width = tokens.switchWidth, height = tokens.switchHeight)
            .background(if (checked) NuvioPrimitives.white else tokens.switchOffTrack, CircleShape)
            .padding(horizontal = tokens.switchKnobInset),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(tokens.switchKnob)
                .background(if (checked) NuvioPrimitives.black else tokens.switchOffKnob, CircleShape)
        )
    }
}

/** `.l8r .vl`: end-aligned, white while the row is [lit], onImage3 when [dimmed]. */
@Composable
internal fun RowScope.MarqueeFormRowValue(text: String, dimmed: Boolean, lit: Boolean) {
    val marquee = NuvioTheme.marquee
    Text(
        text = text,
        style = marqueeFormRowValueStyle,
        color = when {
            dimmed -> marquee.onImage3
            lit -> NuvioPrimitives.white
            else -> marquee.onImage2
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false)
    )
}

internal val marqueeFormRowValueStyle: TextStyle
    @Composable get() = NuvioTheme.textStyles.meta.copy(textAlign = TextAlign.End)

/** `.l8r .ico`: a trailing icon (chevron, QR, sign-out) in onImage3. */
@Composable
internal fun MarqueeFormRowIcon(icon: ImageVector, contentDescription: String? = null) {
    val marquee = NuvioTheme.marquee
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = marquee.onImage3,
        modifier = Modifier.size(marquee.formRow.iconSize)
    )
}
