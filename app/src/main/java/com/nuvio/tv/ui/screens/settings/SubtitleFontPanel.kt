package com.nuvio.tv.ui.screens.settings

import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.UserSubtitleFont
import com.nuvio.tv.data.local.UserSubtitleFontStore
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

/** What the Font row shows for the stored choice (Spec 132 stages 1, 5, 9). */
internal sealed interface SubtitleFontChoice {
    data object Default : SubtitleFontChoice

    /** The fonts have not been read yet: the row shows the stored name, no warning. */
    data object Unknown : SubtitleFontChoice

    data class Chosen(val font: UserSubtitleFont) : SubtitleFontChoice

    /** The chosen file is gone from this TV: subtitles draw Default and the row says why. */
    data object Missing : SubtitleFontChoice
}

internal fun subtitleFontChoice(fontId: String?, fonts: List<UserSubtitleFont>?): SubtitleFontChoice = when {
    fontId == null -> SubtitleFontChoice.Default
    fonts == null -> SubtitleFontChoice.Unknown
    else -> fonts.firstOrNull { it.id == fontId }?.let(SubtitleFontChoice::Chosen) ?: SubtitleFontChoice.Missing
}

/** Stage 4's size column: whole KB under 1 MB (at least 1), else MB to one decimal. */
@Composable
internal fun subtitleFontSizeLabel(bytes: Long): String =
    if (bytes < BYTES_PER_MB) {
        stringResource(R.string.sub_font_size_kb, (bytes / BYTES_PER_KB).coerceAtLeast(1))
    } else {
        stringResource(R.string.sub_font_size_mb, String.format(Locale.getDefault(), "%.1f", bytes / BYTES_PER_MB.toDouble()))
    }

/** Stage 1 (row after Bold), 5 (the chosen name) and 9 (the gone font, value Default); OK opens the panel. */
@Composable
internal fun SubtitleFontRow(style: SubtitleStyleSettings, fontsViewModel: SubtitleFontsViewModel) {
    val fonts by fontsViewModel.fonts.collectAsStateWithLifecycle()
    val default = stringResource(R.string.sub_font_default)
    val choice = subtitleFontChoice(style.fontId, fonts)
    SettingsValueRow(
        title = stringResource(R.string.sub_font),
        value = when (choice) {
            SubtitleFontChoice.Default, SubtitleFontChoice.Missing -> default
            SubtitleFontChoice.Unknown -> style.fontName ?: default
            is SubtitleFontChoice.Chosen -> choice.font.family
        },
        subtitle = if (choice == SubtitleFontChoice.Missing) {
            style.fontName?.let { stringResource(R.string.sub_font_missing, it) }
                ?: stringResource(R.string.sub_font_missing_unnamed)
        } else {
            null
        },
        onClick = fontsViewModel::openPanel
    )
}

/**
 * The Font panel while open, hosted by the pane (outside the lazy list, so scrolling never disposes
 * it). Home (ON_STOP) or leaving the pane closes it, and with it the phone page.
 */
@Composable
internal fun SubtitleFontPanels(fontsViewModel: SubtitleFontsViewModel, chosenId: String?) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { fontsViewModel.closePanel() }
    DisposableEffect(fontsViewModel) {
        onDispose { fontsViewModel.closePanel() }
    }
    val open by fontsViewModel.panelOpen.collectAsStateWithLifecycle()
    if (!open) return
    val confirm by fontsViewModel.confirm.collectAsStateWithLifecycle()
    confirm?.let { font ->
        SubtitleFontRemovePanel(font = font, onRemove = fontsViewModel::confirmRemove, onCancel = fontsViewModel::cancelConfirm)
        return
    }
    val fonts by fontsViewModel.fonts.collectAsStateWithLifecycle()
    val typefaces by fontsViewModel.typefaces.collectAsStateWithLifecycle()
    val phoneUrl by fontsViewModel.phoneUrl.collectAsStateWithLifecycle()
    SubtitleFontSidePanel(
        fonts = fonts,
        typefaces = typefaces,
        chosenId = chosenId,
        phoneUrl = phoneUrl,
        onPick = fontsViewModel::pick,
        onAskRemove = fontsViewModel::askRemove,
        onDismiss = fontsViewModel::closePanel
    )
}

/**
 * Stages 2, 4 and 7: Default, then one row per font (its name in its own face, the file name and
 * size; OK picks and closes, hold OK or MENU asks to remove), then the phone QR, or at five fonts
 * the limit line in its place. Focus starts on the ticked row; a missing choice ticks Default.
 * [fonts] null: not read yet (Default and nothing else). [phoneUrl] null: no QR.
 */
@Composable
private fun SubtitleFontSidePanel(
    fonts: List<UserSubtitleFont>?,
    typefaces: Map<String, Typeface>,
    chosenId: String?,
    phoneUrl: String?,
    onPick: (UserSubtitleFont?) -> Unit,
    onAskRemove: (UserSubtitleFont) -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tickedFocus = remember { FocusRequester() }
    val ticked = fonts?.firstOrNull { it.id == chosenId }
    val belowCap = fonts != null && fonts.size < UserSubtitleFontStore.CAP
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = tickedFocus,
        fillHeight = false,
        alignment = Alignment.TopEnd
    ) {
        PanelEyebrow(text = stringResource(R.string.sub_font_panel_title))
        // The rows scroll (the focused one comes into view); the lines and QR below hold no focus, so they stay whole.
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            PlayerPanelRow(
                title = stringResource(R.string.sub_font_default),
                selected = ticked == null,
                onClick = { onPick(null) },
                subtitle = stringResource(R.string.sub_font_default_sub),
                focusRequester = tickedFocus.takeIf { ticked == null },
                trailingIconTint = marquee.readyText,
                selectedFill = false
            )
            fonts?.forEach { font ->
                SubtitleFontPanelRow(
                    font = font,
                    typeface = typefaces[font.id],
                    selected = font == ticked,
                    focusRequester = tickedFocus.takeIf { font == ticked },
                    onPick = { onPick(font) },
                    onAskRemove = { onAskRemove(font) }
                )
            }
        }
        when {
            fonts == null -> Unit
            fonts.isEmpty() -> Text(
                text = stringResource(R.string.sub_font_empty),
                style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium),
                color = marquee.onImage3,
                modifier = Modifier.padding(horizontal = marquee.settings.panelTextInset, vertical = marquee.settings.messageIconGap)
            )
            belowCap -> SettingsPanelCaption(
                text = stringResource(R.string.sub_font_hold_hint),
                modifier = Modifier.padding(top = marquee.settings.messageIconGap)
            )
            else -> SubtitleFontFullLine(count = fonts.size)
        }
        if (belowCap && phoneUrl != null) {
            SettingsPhoneQrBlock(
                url = phoneUrl,
                divider = stringResource(R.string.sub_font_phone_divider),
                title = stringResource(R.string.sub_font_phone_title),
                note = if (fonts.isNullOrEmpty()) {
                    stringResource(R.string.sub_font_phone_note_empty, MAX_MB)
                } else {
                    stringResource(R.string.sub_font_phone_note, fonts.size, UserSubtitleFontStore.CAP, MAX_MB)
                }
            )
        }
    }
}

/** One font: [typeface] draws its name (none: the app's font); OK picks, hold OK or MENU asks to remove. */
@Composable
private fun SubtitleFontPanelRow(
    font: UserSubtitleFont,
    typeface: Typeface?,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onPick: () -> Unit,
    onAskRemove: () -> Unit
) {
    val longPress = rememberMarqueeLongPress()
    val family = remember(typeface) { typeface?.let(::FontFamily) }
    PlayerPanelRow(
        title = font.family,
        selected = selected,
        onClick = { longPress.click(onPick) },
        modifier = longPress.modifier(onAskRemove),
        subtitle = font.fileName,
        titleEnd = subtitleFontSizeLabel(font.sizeBytes),
        focusRequester = focusRequester,
        trailingIconTint = NuvioTheme.marquee.readyText,
        titleFontFamily = family,
        selectedFill = false
    )
}

/** Stage 7 (`.s132full`): the info icon, "5 of 5 fonts on this TV", and how to add another. */
@Composable
private fun SubtitleFontFullLine(count: Int) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.messageIconGap),
        modifier = Modifier.padding(horizontal = tokens.panelTextInset, vertical = tokens.messageIconGap)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = tokens.infoIconTint,
            modifier = Modifier.size(tokens.infoIconSize)
        )
        Column {
            Text(
                text = stringResource(R.string.sub_font_full_title, count, UserSubtitleFontStore.CAP),
                style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioPrimitives.white
            )
            Text(
                text = stringResource(R.string.sub_font_full_body),
                style = NuvioTheme.textStyles.settingsNote,
                color = marquee.onImage2
            )
        }
    }
}

/** Stage 6: the compact confirm, Cancel focused (the safe button first), Remove in red. */
@Composable
private fun SubtitleFontRemovePanel(font: UserSubtitleFont, onRemove: () -> Unit, onCancel: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val cancelFocus = remember { FocusRequester() }
    MarqueeSidePanelDialog(
        onDismissRequest = onCancel,
        width = marquee.settings.pickerWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = cancelFocus,
        fillHeight = false,
        // Opened by a held OK (or MENU): its repeats and release must not press Cancel.
        openedByHold = true,
        alignment = Alignment.TopEnd
    ) {
        PanelEyebrow(text = stringResource(R.string.sub_font_remove_title, font.family))
        SettingsPanelCaption(stringResource(R.string.sub_font_remove_body))
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancelFocus),
                height = marquee.primaryButton.compactHeight,
                horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.sub_font_remove),
                onClick = onRemove,
                height = marquee.primaryButton.compactHeight,
                horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
                restingLabelColor = NuvioPrimitives.errorOnImage
            )
        }
    }
}

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private const val MAX_MB = (UserSubtitleFontStore.MAX_BYTES / BYTES_PER_MB).toInt()
