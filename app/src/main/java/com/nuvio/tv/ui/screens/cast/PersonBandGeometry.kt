package com.nuvio.tv.ui.screens.cast

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioMarqueeTokens

/**
 * The person page's band in its two frames (Spec 29 stages 1, 3, 5): first frame, tabs at
 * `person.bandTop`; scrolled, the cards' bottom at Home's band anchor (`home.focusedCardBottomInset`)
 * with the tabs `person.tabsToCards` above them, so the band moves up by [travel] (the profile
 * leaves by as much). The hero bottom sits `home.heroAboveRow` above the scrolled tabs
 * ([heroBottomInset] from the screen bottom) and never rises above `home.heroMinTop`.
 */
@Immutable
internal data class PersonBandGeometry(
    val cardWidth: Dp,
    val travel: Dp,
    val heroBottomInset: Dp,
    val heroMaxHeight: Dp
)

internal fun personBandGeometry(screenHeight: Dp, landscape: Boolean, marquee: NuvioMarqueeTokens): PersonBandGeometry {
    val home = marquee.home
    val cardHeight = if (landscape) home.landscapeCardHeight else home.portraitCardHeight
    val scrolledTabsTop = screenHeight - home.focusedCardBottomInset - cardHeight - marquee.person.tabsToCards
    val heroBottom = scrolledTabsTop - home.heroAboveRow
    return PersonBandGeometry(
        cardWidth = if (landscape) home.landscapeCardWidth else home.portraitCardWidth,
        travel = (marquee.person.bandTop - scrolledTabsTop).coerceAtLeast(0.dp),
        heroBottomInset = (screenHeight - heroBottom).coerceAtLeast(0.dp),
        heroMaxHeight = (heroBottom - home.heroMinTop).coerceAtLeast(0.dp)
    )
}
