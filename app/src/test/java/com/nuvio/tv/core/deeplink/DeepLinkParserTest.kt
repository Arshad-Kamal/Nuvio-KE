package com.nuvio.tv.core.deeplink

import com.nuvio.tv.domain.deeplink.AppDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParserTest {
    @Test
    fun parsesMetaQueryDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://meta?type=series&id=tt0944947")
        )
    }

    @Test
    fun parsesAddonInstallDeepLink() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            DeepLinkParser.parse("nuvio://free.nebulapro.xyz/sports/i/free/manifest.json")
        )
    }

    @Test
    fun parsesStremioAddonInstallDeepLink() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            DeepLinkParser.parse("stremio://free.nebulapro.xyz/sports/i/free/manifest.json")
        )
    }

    @Test
    fun stremioInstallStripsUserinfoFromAuthority() {
        // 2026-08-26 review: user:pass@ must not ride into the manifest URL.
        assertEquals(
            AppDeepLink.AddonInstall("https://evil.example/manifest.json"),
            DeepLinkParser.parse("stremio://user:pass@evil.example/manifest.json")
        )
    }

    @Test
    fun stremioInstallRejectsMalformedAuthority() {
        assertNull(DeepLinkParser.parse("stremio://bad host with spaces/manifest.json"))
    }

    @Test
    fun parsesDirectImdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://series/tt0944947")
        )
    }

    @Test
    fun parsesProviderImdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://imdb/series/tt0944947")
        )
    }

    @Test
    fun parsesProviderTmdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tmdb:1399"),
            DeepLinkParser.parse("nuvio://tmdb/tv/1399")
        )
    }

    @Test
    fun doesNotTreatAuthLinkAsAddonInstall() {
        assertNull(DeepLinkParser.parse("nuvio://auth/trakt?code=abc"))
    }

    @Test
    fun doesNotTreatNonHostStremioLinkAsAddonInstall() {
        assertNull(DeepLinkParser.parse("stremio://detail/series/tt0944947"))
    }
}
