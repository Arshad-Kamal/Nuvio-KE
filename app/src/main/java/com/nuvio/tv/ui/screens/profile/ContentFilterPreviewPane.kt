package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ContentFilterRead
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

// Sandbox spec-122-filter-preview stage 1 (`.pvl`, `.pvo`, `.pvo.sel`, `.pvo .ck`).
private val OPTION_GAP = 6.dp
private val OPTION_HEIGHT = 44.dp
private val OPTION_RADIUS = 10.dp
private val OPTION_PADDING_H = 12.dp
private val OPTION_AVATAR = 28.dp
private val OPTION_CHECK = 18.dp
private val OPTION_SUMMARY_GAP = 8.dp
private val OPTION_FILL = Color.White.copy(alpha = 0.06f)
private val OPTION_PICKED_EDGE = Color.White.copy(alpha = 0.3f)

/**
 * What the primary's "Preview filters of" picker needs (Spec 122 D3): every profile, their stored
 * filters ([reads], the primary's holds the picked id), their avatars, and [onPick], which writes
 * the choice at once (null = Off).
 */
internal class FilterPreviewInputs(
    val profiles: List<UserProfile>,
    val reads: Map<Int, ContentFilterRead>?,
    val avatarArt: Map<Int, AvatarArt?>,
    val onPick: (Int?) -> Unit
)

/** The picker row's value: Off, or the picked profile's name. */
@Composable
internal fun previewValue(choices: List<PreviewChoice>, picked: Int?): String =
    choices.firstOrNull { it.profileId == picked }?.profile?.name ?: stringResource(R.string.content_filters_preview_off)

/**
 * Stage 1's pane: Off, the primary's own filters, each other profile with what it has on; the
 * [picked] one checked, and [entryFocus] on it. OK on a choice hands it to [onPick].
 */
@Composable
internal fun PreviewPane(
    choices: List<PreviewChoice>,
    picked: Int?,
    avatarArt: Map<Int, AvatarArt?>,
    backTo: FocusRequester,
    entryFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onPick: (Int?) -> Unit
) {
    EditablePane(
        backTo = backTo,
        onFocusChanged = onFocusChanged,
        foot = { PaneText(stringResource(R.string.content_filters_preview_foot), foot = true) }
    ) {
        PaneTitle(stringResource(R.string.content_filters_preview_of))
        PaneText(stringResource(R.string.content_filters_preview_explainer))
        Column(verticalArrangement = Arrangement.spacedBy(OPTION_GAP)) {
            choices.forEach { choice ->
                key(choice.profileId) {
                    val isPicked = choice.profileId == picked
                    PreviewOption(
                        choice = choice,
                        art = choice.profileId?.let(avatarArt::get),
                        picked = isPicked,
                        modifier = if (isPicked) Modifier.focusRequester(entryFocus) else Modifier,
                        onClick = { onPick(choice.profileId) }
                    )
                }
            }
        }
    }
}

/** `.pvo`: a choice's avatar, name and summary, the check when [picked]; white while focused. */
@Composable
private fun PreviewOption(choice: PreviewChoice, art: AvatarArt?, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(OPTION_RADIUS)
    val ink = if (focused) NuvioPrimitives.black else NuvioPrimitives.white
    val dim = if (focused) ROW_SUBTITLE_FOCUSED else NuvioTheme.marquee.onImage3
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(OPTION_HEIGHT)
            .clip(shape)
            .background(if (focused) NuvioPrimitives.white else OPTION_FILL)
            .then(if (picked && !focused) Modifier.border(1.dp, OPTION_PICKED_EDGE, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = OPTION_PADDING_H),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        val profile = choice.profile
        if (profile != null) {
            ProfileAvatarCircle(name = profile.name, colorHex = profile.avatarColorHex, size = OPTION_AVATAR, art = art)
        }
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    profile == null -> stringResource(R.string.content_filters_preview_off)
                    profile.isPrimary -> stringResource(R.string.content_filters_preview_own, profile.name)
                    else -> profile.name
                },
                style = NuvioTheme.textStyles.meta.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            choice.summary?.let { summary ->
                Text(
                    text = previewSummaryText(summary),
                    style = NuvioTheme.textStyles.caption.copy(fontWeight = FontWeight.Normal),
                    color = dim,
                    maxLines = 1,
                    modifier = Modifier.padding(start = OPTION_SUMMARY_GAP)
                )
            }
        }
        if (picked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (focused) NuvioPrimitives.black else NuvioPrimitives.successOnImage,
                modifier = Modifier.size(OPTION_CHECK)
            )
        }
    }
}

@Composable
private fun previewSummaryText(summary: PreviewSummary): String = when (summary) {
    is PreviewSummary.On -> stringResource(R.string.content_filters_on_count, summary.count)
    PreviewSummary.MyWordsOnly -> stringResource(R.string.content_filters_my_words)
    PreviewSummary.NothingOn -> stringResource(R.string.content_filters_preview_nothing_on)
    PreviewSummary.Unreadable -> stringResource(R.string.content_filters_preview_unreadable)
}
