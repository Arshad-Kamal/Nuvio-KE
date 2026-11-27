package com.nuvio.tv.ui.screens.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Sizes of the backup panels' own parts (sandbox spec-101-backup-and-restore v12, `.b1*` classes, in
 * the mockup's 960 x 540 frame, so CSS px = dp). Shared shapes (side panel, eyebrow, buttons, keypad,
 * form rows) come from the theme's tokens.
 */
internal object BackupDims {
    val rowMinHeight = 40.dp
    val rowPaddingH = 12.dp
    val rowPaddingV = 5.dp
    val rowRadius = 12.dp
    val rowGap = 10.dp
    val rowIcon = 18.dp
    val listGap = 6.dp
    val boxSize = 18.dp
    val boxRadius = 5.dp
    val boxRing = 1.5.dp
    val fileRadius = 14.dp
    val fileIcon = 30.dp
    val chipHeight = 26.dp
    val chipIcon = 13.dp
    val lineIcon = 16.dp
    val pinHeight = 46.dp
    val pinDot = 11.dp
    val qrSize = 170.dp
    val qrRadius = 10.dp
    const val QR_FILL = 0.84f
    val restFill = Color.White.copy(alpha = 0.06f)
    val focusFill = Color.White.copy(alpha = 0.14f)
    val chipFill = Color.White.copy(alpha = 0.08f)
    val footFill = Color.White.copy(alpha = 0.04f)
}

/**
 * A backup side panel (`.l8p`): [eyebrow] and its rule, [content] scrolling under it, [actions] pinned
 * at the bottom. Back calls [onDismiss]; [initialFocus] takes focus when the window is up.
 */
@Composable
internal fun BackupPanelFrame(
    eyebrow: String,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester?,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val marquee = NuvioTheme.marquee
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = initialFocus,
    ) {
        PanelEyebrow(text = eyebrow)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BackupDims.listGap),
            content = content,
        )
        MarqueePanelActions { actions() }
    }
}

/**
 * `.b1row`: [leading], [title] over [subtitle], [trailing] text or [end] content. Focusable when [onClick]
 * is set (fill .06, focused .14 with a white ring); [plain] rows are read-only lines without a fill.
 */
@Composable
internal fun BackupRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: String? = null,
    end: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    plain: Boolean = false,
    titleEnd: @Composable (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(BackupDims.rowRadius)
    val marquee = NuvioTheme.marquee
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (plain) 30.dp else BackupDims.rowMinHeight)
            .clip(shape)
            .then(
                when {
                    plain -> Modifier
                    focused -> Modifier.background(BackupDims.focusFill).border(2.dp, NuvioPrimitives.white, shape)
                    else -> Modifier.background(BackupDims.restFill)
                },
            )
            .then(
                if (onClick != null) {
                    Modifier
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (plain) 8.dp else BackupDims.rowPaddingH, vertical = if (plain) 2.dp else BackupDims.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BackupDims.rowGap),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = rowTitle, color = NuvioPrimitives.white)
                titleEnd?.invoke()
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(text = subtitle, style = rowSub, color = if (focused) marquee.onImage2 else marquee.onImage3)
            }
        }
        if (trailing != null) Text(text = trailing, style = rowTrailing, color = marquee.onImage2, maxLines = 1)
        end?.invoke()
    }
}

/** A row's leading icon (`.b1row > .ico`). */
@Composable
internal fun BackupRowIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, tint = NuvioTheme.marquee.onImage2, modifier = Modifier.size(BackupDims.rowIcon))
}

/** `.b1box`: the checklist's tick box. */
@Composable
internal fun BackupTickBox(checked: Boolean) {
    val shape = RoundedCornerShape(BackupDims.boxRadius)
    Box(
        modifier = Modifier
            .size(BackupDims.boxSize)
            .clip(shape)
            .then(
                if (checked) {
                    Modifier.background(NuvioPrimitives.white)
                } else {
                    Modifier.border(BackupDims.boxRing, NuvioPrimitives.white.copy(alpha = 0.6f), shape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = NuvioPrimitives.black, modifier = Modifier.size(14.dp))
    }
}

/** `.b1file`: a file or a verdict card; [warn] turns its icon amber. */
@Composable
internal fun BackupFileCard(icon: ImageVector, title: String, detail: String?, warn: Boolean = false) {
    Row(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(BackupDims.fileRadius))
            .background(BackupDims.restFill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (warn) NuvioPrimitives.warning else NuvioTheme.marquee.readyText,
            modifier = Modifier.size(BackupDims.fileIcon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = rowTitle, color = NuvioPrimitives.white)
            if (detail != null) Text(text = detail, style = rowSub.copy(fontSize = 11.5.sp, lineHeight = 16.sp), color = NuvioTheme.marquee.onImage3)
        }
    }
}

/** One `.b1inc` chip. */
internal data class BackupChip(val icon: ImageVector, val text: String)

/** `.b1inc`: what a saved backup holds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupChips(chips: List<BackupChip>) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            Row(
                modifier = Modifier
                    .height(BackupDims.chipHeight)
                    .clip(CircleShape)
                    .background(BackupDims.chipFill)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(chip.icon, contentDescription = null, tint = NuvioTheme.marquee.onImage2, modifier = Modifier.size(BackupDims.chipIcon))
                Text(text = chip.text, style = rowSub.copy(fontSize = 11.5.sp, lineHeight = 16.sp), color = NuvioTheme.marquee.onImage2)
            }
        }
    }
}

/** `.b1warn` (amber, the error icon) or, [info], `.b1warn.inf` (quiet, the info icon). */
@Composable
internal fun BackupLine(text: AnnotatedString, info: Boolean = true, modifier: Modifier = Modifier) {
    val color = if (info) NuvioTheme.marquee.onImage2 else NuvioPrimitives.warning
    Row(
        modifier = modifier.padding(horizontal = 8.dp).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (info) Icons.Outlined.Info else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(top = 1.dp).size(BackupDims.lineIcon),
        )
        Text(text = text, style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
internal fun BackupLine(text: String, info: Boolean = true) = BackupLine(AnnotatedString(text), info)

/** [lead] in white semibold, then [rest] (`.b1warn b`, `.b1src b`). */
internal fun leadText(lead: String, rest: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = NuvioPrimitives.white, fontWeight = FontWeight.SemiBold)) { append(lead) }
    append(rest)
}

/** `.b1src`: where a backup came from. */
@Composable
internal fun BackupSourceLine(text: AnnotatedString) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium),
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
    )
}

/** `.b1sec`: a small section label inside a panel. */
@Composable
internal fun BackupPanelSection(text: String) {
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.eyebrow.copy(fontSize = 10.5.sp, letterSpacing = 1.6.sp),
        color = NuvioTheme.marquee.onImage3,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 2.dp),
    )
}

/** `.b1ttl` + `.note`: a PIN step's title and note. */
@Composable
internal fun BackupPanelTitle(title: String, note: String) {
    Column(modifier = Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = NuvioTheme.textStyles.settingsTitle.copy(fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp), color = NuvioPrimitives.white)
        Text(text = note, style = NuvioTheme.textStyles.settingsNote, color = NuvioTheme.marquee.onImage2)
    }
}

/** `.b1pin`: one dot per digit typed, the caret, and [count] at the end; [error] rings it red. */
@Composable
internal fun BackupPinField(digits: Int, count: String, error: Boolean) {
    val shape = RoundedCornerShape(BackupDims.rowRadius)
    Row(
        modifier = Modifier
            .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 2.dp)
            .fillMaxWidth()
            .height(BackupDims.pinHeight)
            .clip(shape)
            .background(BackupDims.chipFill)
            .border(2.dp, if (error) NuvioPrimitives.errorOnImage else NuvioPrimitives.white, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        repeat(digits) { Box(Modifier.size(BackupDims.pinDot).background(NuvioPrimitives.white, CircleShape)) }
        Box(Modifier.width(2.dp).height(22.dp).background(NuvioPrimitives.white))
        Spacer(Modifier.weight(1f))
        Text(text = count, style = rowSub.copy(fontSize = 12.sp, lineHeight = 16.sp), color = NuvioTheme.marquee.onImage3)
    }
}

/** `.b1foot`: the pane's safety-copy note. */
@Composable
internal fun BackupFootNote(icon: ImageVector, text: AnnotatedString) {
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(BackupDims.rowRadius))
            .background(BackupDims.footFill)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = NuvioTheme.marquee.readyText, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text = text, style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium), color = NuvioTheme.marquee.onImage2)
    }
}

private val rowTitle @Composable get() = NuvioTheme.textStyles.meta.copy(fontWeight = FontWeight.SemiBold)
private val rowSub @Composable get() = NuvioTheme.textStyles.caption.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
private val rowTrailing @Composable get() = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium)
