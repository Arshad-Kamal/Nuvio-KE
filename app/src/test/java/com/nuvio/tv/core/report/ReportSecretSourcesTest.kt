package com.nuvio.tv.core.report

import com.nuvio.tv.livetv.source.XtreamLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 116 D6 step 3's sources (P3). */
class ReportSecretSourcesTest {

    @Test
    fun `Live TV logins in a URL path count as credentials at 6+ chars, any case`() {
        assertEquals(
            listOf("famuser", "sunpass"),
            ReportSecretSources.xtreamLogins("http://line.example.net:8080/playlist/famuser/sunpass/m3u_plus"),
        )
        assertEquals(listOf("arshadk"), ReportSecretSources.xtreamLogins("http://h.example/live/arshadk/k3y9q/1.ts"))
        assertEquals(emptyList<String>(), ReportSecretSources.xtreamLogins("http://h.example/get.php?username=famuser"))
        assertEquals(emptyList<String>(), ReportSecretSources.xtreamLogins("not a url"))
        // A numeric or dotted login is still a login.
        assertEquals(listOf("4455667", "pass.word"), ReportSecretSources.xtreamLogins("http://h.example/live/4455667/pass.word/1.ts"))
    }

    @Test
    fun `assemble derives known values from every source and names addons by first install`() {
        val secrets = ReportSecretSources.assemble(
            addonUrls = listOf(
                "https://torrentio.strem.fun/alldebrid=hQwErTyUiOpAsDfGhJkL/manifest.json",
                "https://other.example.org/manifest.json",
            ),
            namedAddons = listOf(
                "https://torrentio.strem.fun/alldebrid=hQwErTyUiOpAsDfGhJkL" to "Torrentio",
                "https://torrentio.strem.fun/lite" to "Torrentio Lite",
            ),
            liveUrls = listOf(
                "http://line.example.net:8080/playlist/famuser/sunpass/m3u_plus",
                "http://epg.example.net/xmltv.php?username=epguser1&password=Zk81mnQx",
            ),
            accessHosts = listOf("private.example.org"),
            credentials = listOf("", "sk_live_9f8e7d6c5b4a", "short"),
            xtreamLogins = emptyList(),
        )
        val known = secrets.knownValues
        for (value in listOf("hQwErTyUiOpAsDfGhJkL", "famuser", "sunpass", "epguser1", "Zk81mnQx", "sk_live_9f8e7d6c5b4a")) {
            assertTrue(value, value in known)
        }
        assertTrue("private.example.org" in known)
        for (word in listOf("playlist", "m3u_plus", "short", "", "torrentio.strem.fun")) assertFalse(word, word in known)
        assertEquals(mapOf("torrentio.strem.fun" to "Torrentio", "other.example.org" to null), secrets.addonHosts)
    }

    @Test
    fun `an Xtream login source's username and password count as credentials (Spec 109)`() {
        val secrets = ReportSecretSources.assemble(
            addonUrls = emptyList(),
            namedAddons = emptyList(),
            // Its record's URL is the bare server: the login is not in it.
            liveUrls = listOf("http://tv.example.net:8080"),
            accessHosts = emptyList(),
            credentials = emptyList(),
            xtreamLogins = listOf(XtreamLogin("http://tv.example.net:8080", "kamal.home", "s3cr3t-Pa55"), XtreamLogin("http://h", "abc", "xy")),
        )
        assertTrue("kamal.home" in secrets.knownValues)
        assertTrue("s3cr3t-Pa55" in secrets.knownValues)
        // Under 6 chars, as for any credential: too common to hide.
        assertFalse("abc" in secrets.knownValues)
    }
}
