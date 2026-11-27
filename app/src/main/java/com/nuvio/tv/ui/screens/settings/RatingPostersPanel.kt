package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.posters.PosterContentType
import com.nuvio.tv.core.posters.RatingPosterResolver
import com.nuvio.tv.core.posters.RatingPosterSaveCheck
import com.nuvio.tv.core.posters.RatingPosterTemplate
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Spec 114 Rating posters panel (approved sandbox `s114-rating-posters` v1 + v2): stage 2 nothing saved (the
 * note, the phone QR, Done focused); stage 4 saved (the test poster, the host and the key's last 4 characters,
 * never the address; the QR to replace it; Turn off in red, Done focused); stage 8 the same with the amber
 * http line while a home-network http address is saved; stage 5 the Turn off confirm in its place (Cancel
 * focused). [template] null = nothing saved (Off, or still loading). [phoneUrl] null = no QR (no network, or
 * the page isn't running). Back closes the panel, or the confirm.
 */
@Composable
internal fun RatingPostersSidePanel(
    template: RatingPosterTemplate?,
    confirmOff: Boolean,
    phoneUrl: String?,
    onAskTurnOff: () -> Unit,
    onConfirmTurnOff: () -> Unit,
    onCancelTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (confirmOff) {
        RatingPostersOffConfirm(onTurnOff = onConfirmTurnOff, onCancel = onCancelTurnOff)
        return
    }
    val marquee = NuvioTheme.marquee
    val doneFocus = remember { FocusRequester() }
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = doneFocus,
        fillHeight = false,
        alignment = Alignment.TopEnd,
    ) {
        if (template == null) {
            PanelEyebrow(text = stringResource(R.string.rating_posters_title))
            RatingPostersNote()
        } else {
            PanelEyebrow(text = stringResource(R.string.rating_posters_on_eyebrow))
            RatingPostersPreview(template)
            if (template.isHttp) RatingPostersWarnLine(stringResource(R.string.rating_posters_http_warning))
            SettingsPanelCaption(
                text = stringResource(R.string.rating_posters_saved_note),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        phoneUrl?.let {
            SettingsPhoneQrBlock(
                url = it,
                divider = stringResource(
                    if (template == null) R.string.rating_posters_phone_divider else R.string.rating_posters_phone_divider_replace
                ),
                title = stringResource(R.string.rating_posters_phone_title),
            )
        }
        MarqueePanelActions {
            if (template != null) {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.rating_posters_turn_off),
                    onClick = onAskTurnOff,
                    height = marquee.primaryButton.compactHeight,
                    horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall,
                    restingLabelColor = NuvioPrimitives.errorOnImage,
                )
            }
            MarqueePrimaryButton(
                label = stringResource(R.string.rating_posters_done),
                icon = null,
                onClick = onDismiss,
                focusRequester = doneFocus,
                compact = true,
            )
        }
    }
}

/** Stage 2's `.l8p .note`: where the address comes from, "Copy config" in white. */
@Composable
private fun RatingPostersNote() {
    val copyConfig = stringResource(R.string.rating_posters_note_copy_config)
    val note = stringResource(R.string.rating_posters_note, copyConfig)
    val start = note.indexOf(copyConfig)
    Text(
        text = buildAnnotatedString {
            append(note)
            if (start >= 0) {
                addStyle(SpanStyle(color = NuvioPrimitives.white, fontWeight = FontWeight.SemiBold), start, start + copyConfig.length)
            }
        },
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(horizontal = NuvioTheme.marquee.settings.panelTextInset),
    )
}

/**
 * `.s114pv`: Big Buck Bunny's poster from the saved address, loaded through the image client as any card (the
 * host gate applies; the save check already closed its breaker), beside the host and the key's last 4.
 */
@Composable
private fun RatingPostersPreview(template: RatingPosterTemplate) {
    val marquee = NuvioTheme.marquee
    val posterUrl = remember(template) {
        RatingPosterResolver.resolve(template, RatingPosterSaveCheck.PROBE_IDS, PosterContentType.MOVIE)
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 76.dp, height = 114.dp)
                .clip(shape)
                .background(NuvioPrimitives.white.copy(alpha = 0.06f))
                .border(1.dp, NuvioPrimitives.white.copy(alpha = 0.14f), shape),
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 76.dp, height = 114.dp),
                )
            }
        }
        Column {
            Text(
                text = stringResource(R.string.rating_posters_test_poster),
                style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioPrimitives.white,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Text(
                text = template.displayHost,
                style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioPrimitives.white,
            )
            template.keyTail?.let { tail ->
                Text(
                    text = stringResource(R.string.rating_posters_key_tail, tail),
                    style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.Medium),
                    color = marquee.onImage3,
                )
            }
        }
    }
}

/** `.s114warn`: the warning triangle, then [text] in amber (stage 8). */
@Composable
private fun RatingPostersWarnLine(text: String) {
    val tokens = NuvioTheme.marquee.settings
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.messageIconGap),
        modifier = Modifier.padding(horizontal = tokens.panelTextInset, vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = NuvioPrimitives.warning,
            modifier = Modifier.size(tokens.messageIconSize),
        )
        Text(
            text = text,
            style = NuvioTheme.textStyles.settingsNote.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioPrimitives.warningOnTint,
        )
    }
}

/** Stage 5: the compact confirm, Cancel focused (the safe button first), Turn off in red. */
@Composable
private fun RatingPostersOffConfirm(onTurnOff: () -> Unit, onCancel: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val cancelFocus = remember { FocusRequester() }
    MarqueeSidePanelDialog(
        onDismissRequest = onCancel,
        width = marquee.settings.pickerWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = cancelFocus,
        fillHeight = false,
        alignment = Alignment.TopEnd,
    ) {
        PanelEyebrow(text = stringResource(R.string.rating_posters_off_title))
        SettingsPanelCaption(stringResource(R.string.rating_posters_off_body))
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancelFocus),
                height = marquee.primaryButton.compactHeight,
                horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.rating_posters_turn_off),
                onClick = onTurnOff,
                height = marquee.primaryButton.compactHeight,
                horizontalPadding = marquee.collections.headerButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
                restingLabelColor = NuvioPrimitives.errorOnImage,
            )
        }
    }
}
