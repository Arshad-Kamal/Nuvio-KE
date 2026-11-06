package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X1 (QU5-2): AIOStreams is recognised by manifest id or raw name; everything else is not. */
class AddonAioStreamsTest {

    @Test
    fun `default and variant manifest ids are recognised under any name`() {
        assertTrue(addon(id = "com.aiostreams.viren070.ab12cd34ef56", name = "My Streams").isAioStreams())
        assertTrue(addon(id = "com.aiostreams.viren070.ab12cd34ef56.1a2b3c4d", name = "My Streams").isAioStreams())
        assertTrue(addon(id = "com.AIOStreams.example", name = "Hosted").isAioStreams())
    }

    @Test
    fun `a custom id is recognised by the raw manifest name`() {
        assertTrue(addon(id = "org.example.streams", name = "AIOStreams").isAioStreams())
        assertTrue(addon(id = "org.example.streams", name = "aiostreams | ElfHosted").isAioStreams())
    }

    @Test
    fun `the user's display name does not count`() {
        assertFalse(
            addon(id = "org.example.streams", name = "Streams")
                .copy(displayName = "AIOStreams")
                .isAioStreams()
        )
    }

    @Test
    fun `other addons are not recognised`() {
        assertFalse(addon(id = "com.example.aiometadata", name = "AIOMetadata").isAioStreams())
        assertFalse(addon(id = "com.stremio.torrentio.addon", name = "Torrentio").isAioStreams())
    }

    private fun addon(id: String, name: String): Addon = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example.net",
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList()
    )
}
