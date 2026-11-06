package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.nuvio.tv.domain.model.StreamBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBadgeRulesTest {
    @Test
    fun `parses fusion badge json and drops unusable filters`() {
        val import = StreamBadgeRulesParser.parse(
            sourceUrl = " https://example.com/badges.json ",
            payload = fusionPayload()
        )

        assertEquals("https://example.com/badges.json", import.sourceUrl)
        assertEquals(2, import.filters.size)
        assertEquals("Dolby Vision", import.filters[0].name)
        assertEquals("#102030", import.filters[0].tagColor)
        assertEquals("filled", import.filters[0].tagStyle)
        assertEquals(1, import.groups.size)
        assertEquals("Quality", import.groups[0].name)
    }

    @Test
    fun `normalizes imports with one active source and import limit`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport("one", active = false),
                badgeImport("two", active = true),
                badgeImport("three", active = true),
                badgeImport("four", active = false)
            )
        ).normalized()

        assertEquals(3, rules.imports.size)
        assertEquals(listOf(false, true, false), rules.imports.map { it.isActive })
    }

    @Test
    fun `serializes active import state for web clients`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport("one", active = true),
                badgeImport("two", active = false)
            )
        ).normalized()

        val json = Json.encodeToString(StreamBadgeRules.serializer(), rules)

        assertTrue(json.contains("\"isActive\":true"))
        assertTrue(json.contains("\"isActive\":false"))
    }

    @Test
    fun `reads legacy active import state name`() {
        val rules = relaxedJson.decodeFromString<StreamBadgeRules>(
            """
            {
              "imports": [
                {
                  "sourceUrl": "one",
                  "active": false,
                  "filters": [
                    {"name": "One", "pattern": "One"}
                  ]
                },
                {
                  "sourceUrl": "two",
                  "active": true,
                  "filters": [
                    {"name": "Two", "pattern": "Two"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).normalized()

        assertEquals(listOf(false, true), rules.imports.map { it.isActive })
    }

    @Test
    fun `matches active badge filters against stream metadata`() {
        val stream = clientResolveStream()
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport("inactive", active = false, name = "Inactive", pattern = "Movie"),
                StreamBadgeImport(
                    sourceUrl = "active",
                    isActive = true,
                    filters = listOf(
                        StreamBadgeFilter(name = "Dolby Vision", pattern = "DV", imageURL = "https://cdn/dv.png"),
                        StreamBadgeFilter(name = "Atmos", pattern = "Atmos", imageURL = "https://cdn/atmos.png"),
                        StreamBadgeFilter(name = "Disabled", pattern = "Movie", isEnabled = false)
                    )
                )
            )
        )

        val badges = StreamBadgeMatcher.matchedBadges(stream, StreamBadgeMatcher.compile(rules))

        assertEquals(listOf("Dolby Vision", "Atmos"), badges.map { it.name })
    }

    @Test
    fun `matches badge filters against regular addon streams`() {
        val stream = Stream(
            name = "Movie 2026 2160p WEB-DL DV Atmos",
            title = "Movie",
            description = "Movie.2026.2160p.WEB-DL.DV.Atmos.mkv",
            url = "https://example.com/movie.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(name = "Dolby Vision", pattern = "DV", imageURL = "https://cdn/dv.png"),
                        StreamBadgeFilter(name = "Atmos", pattern = "Atmos", imageURL = "https://cdn/atmos.png")
                    )
                )
            )
        )

        val badges = StreamBadgeMatcher.matchedBadges(stream, StreamBadgeMatcher.compile(rules))

        assertEquals(listOf("Dolby Vision", "Atmos"), badges.map { it.name })
    }

    @Test
    fun `matched badges dedupe by key and resolve to the pooled instance`() {
        val stream = clientResolveStream()
        val dvImage = "https://cdn/dv.png"
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "active",
                    filters = listOf(
                        StreamBadgeFilter(name = "DV", pattern = "DV", imageURL = dvImage),
                        // Same dedupeKey (imageURL) before and after the map path opens.
                        StreamBadgeFilter(name = "Dolby Vision", pattern = "DV", imageURL = dvImage),
                        StreamBadgeFilter(name = "Atmos", pattern = "Atmos", imageURL = "https://cdn/atmos.png"),
                        StreamBadgeFilter(name = "DoVi", pattern = "DV", imageURL = dvImage)
                    )
                )
            )
        )
        val pooled = StreamBadge(name = "DV", imageURL = dvImage)

        val badges = StreamBadgeMatcher.matchedBadges(
            stream,
            StreamBadgeMatcher.compile(rules),
            pool = mapOf(dvImage to pooled)
        )

        assertEquals(listOf("DV", "Atmos"), badges.map { it.name })
        assertSame(pooled, badges[0])
    }

    private fun badgeImport(
        sourceUrl: String,
        active: Boolean,
        name: String = "Badge",
        pattern: String = "Badge"
    ): StreamBadgeImport =
        StreamBadgeImport(
            sourceUrl = sourceUrl,
            isActive = active,
            filters = listOf(StreamBadgeFilter(name = name, pattern = pattern))
        )

    private fun clientResolveStream(): Stream =
        Stream(
            name = "Movie 2026 DV Atmos",
            title = "Movie",
            description = null,
            url = "https://stream.example/movie.mkv",
            ytId = null,
            infoHash = "abc123",
            fileIdx = 0,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "Movie.2026.DV.Atmos.mkv"
            ),
            addonName = "Fusion",
            addonLogo = null,
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = "abc123",
                fileIdx = 0,
                sources = null,
                torrentName = "Movie.2026.DV.Atmos",
                filename = "Movie.2026.DV.Atmos.mkv",
                mediaType = "movie",
                title = "Movie",
                season = null,
                episode = null,
                service = "torbox",
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        torrentName = "Movie.2026.DV.Atmos",
                        filename = "Movie.2026.DV.Atmos.mkv",
                        size = 1_000L,
                        network = null,
                        parsed = StreamClientResolveParsed(
                            rawTitle = "Movie",
                            parsedTitle = "Movie",
                            year = 2026,
                            resolution = "2160p",
                            seasons = emptyList(),
                            episodes = emptyList(),
                            quality = "WEB-DL",
                            hdr = listOf("DV"),
                            codec = "HEVC",
                            audio = listOf("Atmos"),
                            channels = listOf("5.1"),
                            languages = listOf("en"),
                            group = "Nuvio",
                            network = null,
                            edition = null,
                            duration = null,
                            bitDepth = null,
                            extended = null,
                            theatrical = null,
                            remastered = null,
                            unrated = null
                        )
                    )
                )
            )
        )

    private fun fusionPayload(): String =
        """
        {
          "filters": [
            {
              "id": "dv",
              "groupId": "quality",
              "name": "Dolby Vision",
              "pattern": "DV",
              "imageURL": "https://cdn.example/dv.png",
              "tagColor": "#102030",
              "tagStyle": "filled",
              "textColor": "#ffffff",
              "borderColor": "#405060"
            },
            {
              "id": "atmos",
              "groupId": "quality",
              "name": "Atmos",
              "pattern": "Atmos",
              "imageURL": "https://cdn.example/atmos.png"
            },
            {
              "id": "blank",
              "groupId": "quality",
              "name": "",
              "pattern": "Ignored"
            }
          ],
          "groups": [
            {
              "id": "quality",
              "name": "Quality",
              "color": "#ffffff"
            }
          ]
        }
        """.trimIndent()

    private companion object {
        val relaxedJson = Json {
            ignoreUnknownKeys = true
        }
    }
}
