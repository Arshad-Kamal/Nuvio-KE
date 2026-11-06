package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class StreamBadgePresentationTest {

    private val presentation = StreamBadgePresentation(dataStore = mockk(relaxed = true))

    private val groups = listOf(
        AddonStreams(
            addonName = "Addon",
            addonLogo = null,
            streams = listOf(
                Stream(
                    name = "Movie 2160p DV Atmos",
                    title = null,
                    description = null,
                    url = "https://example.com/movie.mkv",
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = null,
                    addonName = "Addon",
                    addonLogo = null
                )
            )
        )
    )

    private fun rules(name: String, pattern: String) = StreamBadgeRules(
        imports = listOf(
            StreamBadgeImport(
                sourceUrl = "https://example.com/badges.json",
                filters = listOf(
                    StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://cdn/$pattern.png")
                )
            )
        )
    )

    private fun badgesOf(result: List<AddonStreams>): List<StreamBadge> = result.single().streams.single().badges

    @Test
    fun `changed rules rebuild the badges and an equal copy reuses them`() {
        val rulesA = rules("Dolby Vision", "DV")
        val rulesB = rules("Atmos", "Atmos")

        assertEquals(listOf("Dolby Vision"), badgesOf(presentation.apply(groups, rulesA)).map { it.name })

        val fromB = badgesOf(presentation.apply(groups, rulesB))
        assertEquals(listOf("Atmos"), fromB.map { it.name })

        val equalCopy = rulesB.copy()
        assertNotSame(rulesB, equalCopy)
        val fromCopy = badgesOf(presentation.apply(groups, equalCopy))
        assertEquals(1, fromCopy.size)
        assertSame(fromB.single(), fromCopy.single())
    }
}
