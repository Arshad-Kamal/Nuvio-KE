package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

enum class ProfileEditorTab {
    Avatar,
    Background
}

/** Avatar | Background as a pill pair (u10 stage 8 `.l8chips`; the editor shows it only to members). */
@Composable
fun ProfileEditorTabs(
    selectedTab: ProfileEditorTab,
    onTabSelected: (ProfileEditorTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val padding = NuvioTheme.marquee.playerPanel.segmentPaddingHorizontal
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.profiles.pickerChipGap)
    ) {
        PanelPill(
            text = stringResource(R.string.profile_editor_tab_avatar),
            selected = selectedTab == ProfileEditorTab.Avatar,
            enabled = true,
            horizontalPadding = padding,
            onClick = { onTabSelected(ProfileEditorTab.Avatar) }
        )
        PanelPill(
            text = stringResource(R.string.profile_editor_tab_background),
            selected = selectedTab == ProfileEditorTab.Background,
            enabled = true,
            horizontalPadding = padding,
            onClick = { onTabSelected(ProfileEditorTab.Background) }
        )
    }
}
