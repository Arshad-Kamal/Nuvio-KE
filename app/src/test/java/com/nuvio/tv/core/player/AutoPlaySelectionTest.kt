package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shared inputs the stream screen and the prefetch ranking both build (B7). */
class AutoPlaySelectionTest {

    private val lookups = mutableListOf<String>()
    private val cache: suspend (String) -> String? = { id -> lookups += id; "grp-$id" }

    @Test
    fun `persisted binge group needs both prefer and reuse`() = runTest {
        val both = PlayerSettings(
            streamAutoPlayPreferBingeGroupForNextEpisode = true,
            streamAutoPlayReuseBingeGroup = true
        )
        assertEquals("grp-tt1", AutoPlaySelection.persistedBingeGroup(both, "tt1", cache))
        assertNull(AutoPlaySelection.persistedBingeGroup(both, null, cache))

        val noReuse = both.copy(streamAutoPlayReuseBingeGroup = false)
        val noPrefer = both.copy(streamAutoPlayPreferBingeGroupForNextEpisode = false)
        assertNull(AutoPlaySelection.persistedBingeGroup(noReuse, "tt2", cache))
        assertNull(AutoPlaySelection.persistedBingeGroup(noPrefer, "tt3", cache))
        assertEquals("the cache is read only when both are on", listOf("tt1"), lookups)
    }

    @Test
    fun `inputs from settings carry every selection setting`() {
        val settings = PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
            streamAutoPlayRegex = "2160p",
            streamAutoPlaySource = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            streamAutoPlaySelectedAddons = setOf("B")
        )
        val inputs = AutoPlaySelection.Inputs.fromSettings(settings, listOf("A", "B", "A"), "grp", setOf("https://x/bad.mkv"), setOf("k"))
        assertEquals(
            AutoPlaySelection.Inputs(
                mode = StreamAutoPlayMode.REGEX_MATCH,
                regexPattern = "2160p",
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                installedAddonNames = setOf("A", "B"),
                selectedAddons = setOf("B"),
                preferredBingeGroup = "grp",
                rejectedUrls = setOf("https://x/bad.mkv"),
                hiddenKeys = setOf("k")
            ),
            inputs
        )
    }

    // Spec 42 P2 (F-2): a URL the runtime guard rejected is never auto-selected again, even when
    // it ranks first or matches the remembered binge group.
    @Test
    fun `select skips rejected urls`() {
        val badUrl = "https://x/wrong-film.m3u8"
        val bad = stream(url = badUrl, name = "wrong")
        val good = stream(url = "https://x/right.mkv", name = "right")
        val inputs = AutoPlaySelection.Inputs(
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            hiddenKeys = emptySet()
        )
        assertEquals(bad, AutoPlaySelection.select(listOf(bad, good), inputs))
        assertEquals(good, AutoPlaySelection.select(listOf(bad, good), inputs.copy(rejectedUrls = setOf(badUrl))))
        assertNull(AutoPlaySelection.select(listOf(bad), inputs.copy(rejectedUrls = setOf(badUrl))))
        // Spec 51 P5: a stream the limit hides is skipped the same way, by its limit key.
        assertEquals(good, AutoPlaySelection.select(listOf(bad, good), inputs.copy(hiddenKeys = setOf(bad.limitKey()))))
        assertNull(AutoPlaySelection.select(listOf(bad), inputs.copy(hiddenKeys = setOf(bad.limitKey()))))
    }

    private fun stream(
        url: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        name: String? = "n"
    ) = Stream(
        name = name, title = "t", description = "d", url = url, ytId = null,
        infoHash = infoHash, fileIdx = fileIdx, externalUrl = null, behaviorHints = null,
        addonName = "Addon", addonLogo = null
    )

    @Test
    fun `badge merge key ignores badges and keys torrents by lowercase hash and file`() {
        val plain = stream(url = "https://x/a.mkv")
        val badged = plain.copy(badges = listOf(StreamBadge(name = "4K")))
        assertEquals(plain.badgeMergeKey(), badged.badgeMergeKey())

        assertEquals(
            stream(infoHash = "ABC", fileIdx = 2).badgeMergeKey(),
            stream(infoHash = "abc", fileIdx = 2).badgeMergeKey()
        )
        assertNotEquals(
            stream(infoHash = "abc", fileIdx = 1).badgeMergeKey(),
            stream(infoHash = "abc", fileIdx = 2).badgeMergeKey()
        )
        // No playable url: informational entries stay distinct by name.
        assertNotEquals(stream(name = "a").badgeMergeKey(), stream(name = "b").badgeMergeKey())
        // clientResolve without a url keys by its raw filename, else its hash; name is ignored.
        fun resolve(filename: String?, hash: String?) = StreamClientResolve(
            type = null, infoHash = hash, fileIdx = null, sources = null, torrentName = null,
            filename = null, mediaType = null, title = null, season = null, episode = null,
            service = null,
            stream = StreamClientResolveStream(
                StreamClientResolveRaw(torrentName = null, filename = filename, size = null, network = null, parsed = null)
            )
        )
        assertEquals(
            stream(name = "a").copy(clientResolve = resolve("f.mkv", "h1")).badgeMergeKey(),
            stream(name = "b").copy(clientResolve = resolve("f.mkv", "h2")).badgeMergeKey()
        )
        assertEquals("Addon|h1", stream().copy(clientResolve = resolve(null, "h1")).badgeMergeKey())
    }
}
