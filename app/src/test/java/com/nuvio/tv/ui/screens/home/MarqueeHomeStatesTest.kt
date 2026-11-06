package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingItem
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U1c loading skeleton: the band's card shape follows the row that will lead Home. */
class MarqueeHomeStatesTest {

    private val portraitCatalogs = HomeUiState(modernLandscapePostersEnabled = false)

    @Test
    fun `continue watching leads while its first pass is pending`() {
        assertTrue(homeSkeletonLandscape(portraitCatalogs, continueWatchingPending = true))
        assertFalse(
            homeSkeletonLandscape(
                portraitCatalogs.copy(continueWatchingCardStyle = ContinueWatchingCardStyle.POSTER),
                continueWatchingPending = true
            )
        )
    }

    @Test
    fun `resolved empty continue watching falls back to the catalog card shape`() {
        assertFalse(homeSkeletonLandscape(portraitCatalogs, continueWatchingPending = false))
        assertTrue(
            homeSkeletonLandscape(
                portraitCatalogs.copy(modernLandscapePostersEnabled = true),
                continueWatchingPending = false
            )
        )
    }

    @Test
    fun `continue watching with items leads, and off never does`() {
        val withItems = portraitCatalogs.copy(continueWatchingItems = listOf(mockk<ContinueWatchingItem>()))
        assertTrue(homeSkeletonLandscape(withItems, continueWatchingPending = false))
        assertFalse(
            homeSkeletonLandscape(withItems.copy(continueWatchingEnabled = false), continueWatchingPending = true)
        )
    }
}
