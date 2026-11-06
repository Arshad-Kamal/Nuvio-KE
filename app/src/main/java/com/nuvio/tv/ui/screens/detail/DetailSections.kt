package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.marquee.MarqueeTab
import com.nuvio.tv.ui.theme.NuvioTheme

/** Detail sections, in tab order (Marquee spec §Screens "Detail as decided at the U2 slice"). */
internal enum class DetailSection { CAST, MORE_LIKE_THIS, RATINGS, TRAILERS, COLLECTION, COMPANIES }

@Immutable
internal data class DetailSectionTab(val section: DetailSection, val label: String)

/**
 * The section band: one tab strip ([tabs], content-gated by the caller) over the [selected]
 * section's [content] in a fixed `sectionContentHeight` box, so the tabs never move. Tabs select
 * on focus (views, not destinations) and report [onTabFocused]; entering the strip lands on the
 * selected tab; Up from a tab goes to [upTarget] (resolved when focus moves), Right from the
 * last tab stays put. Content items take Up to their own tab's requester in [tabRequesters]. [trailingLabel] sits right-aligned in the strip (the
 * TMDB attribution while More like this shows).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DetailSectionsBand(
    tabs: List<DetailSectionTab>,
    selected: DetailSection,
    tabRequesters: Map<DetailSection, FocusRequester>,
    upTarget: () -> FocusRequester,
    onSelect: (DetailSection) -> Unit,
    onTabFocused: () -> Unit,
    trailingLabel: String?,
    modifier: Modifier = Modifier,
    content: @Composable (DetailSection) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(detail.sectionTabsHeight)
                .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
                // D-pad entry (Down from the hero, Up from content) lands on the selected tab.
                .focusRestorer(tabRequesters.getValue(selected))
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(detail.sectionTabGap)
        ) {
            val last = tabs.lastOrNull()?.section
            tabs.forEach { tab ->
                key(tab.section) {
                    MarqueeTab(
                        label = tab.label,
                        selected = tab.section == selected,
                        onClick = { onSelect(tab.section) },
                        modifier = Modifier
                            .focusRequester(tabRequesters.getValue(tab.section))
                            .focusProperties {
                                up = upTarget()
                                if (tab.section == last) right = FocusRequester.Cancel
                            }
                            .onFocusChanged {
                                if (!it.isFocused) return@onFocusChanged
                                onSelect(tab.section)
                                onTabFocused()
                            }
                    )
                }
            }
            if (trailingLabel != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = trailingLabel,
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(detail.sectionTabsToContent))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(detail.sectionContentHeight)
        ) {
            // A plain swap (no crossfade): one section composed at a time, and focus can never land
            // in an outgoing section.
            key(selected) { content(selected) }
        }
    }
}
