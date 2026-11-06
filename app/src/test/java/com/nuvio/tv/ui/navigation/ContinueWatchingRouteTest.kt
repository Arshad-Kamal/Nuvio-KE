package com.nuvio.tv.ui.navigation

import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertTrue
import org.junit.Test

/** Window NOTE N1 (Arshad 2026-10-09): a session started straight from a CW card returns to Home. */
class ContinueWatchingRouteTest {

    @Test
    fun `an in-progress series card returns to Home, not Details`() {
        val route = continueWatchingStreamRoute(inProgress(contentType = "series"))

        assertTrue(route, route.contains("&returnToDetailOnBack=false&"))
        assertTrue(route, route.contains("&returnToHomeOnBack=true&"))
    }

    @Test
    fun `a next-up card returns to Home, not Details`() {
        val route = continueWatchingStreamRoute(nextUp())

        assertTrue(route, route.contains("&returnToDetailOnBack=false&"))
        assertTrue(route, route.contains("&returnToHomeOnBack=true&"))
    }

    @Test
    fun `manual selection and start-from-beginning still ride the route`() {
        val route = continueWatchingStreamRoute(
            inProgress(contentType = "movie"),
            manualSelection = true,
            startFromBeginning = true
        )

        assertTrue(route, route.contains("&manualSelection=true&"))
        assertTrue(route, route.contains("&startFromBeginning=true&"))
    }

    private fun inProgress(contentType: String) = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = "tt123",
            contentType = contentType,
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt123:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Pilot",
            position = 60_000L,
            duration = 3_000_000L,
            lastWatched = 1L
        )
    )

    private fun nextUp() = ContinueWatchingItem.NextUp(
        info = NextUpInfo(
            contentId = "tt123",
            contentType = "series",
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt123:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Next",
            thumbnail = null,
            lastWatched = 1L,
            sortTimestamp = 1L
        )
    )
}
