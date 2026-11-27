package com.nuvio.tv.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 116 D6: fixtures under src/test/resources/report. */
class ReportScrubTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/report/$name")) { "missing fixture $name" }.readText().trimEnd('\n')

    private val addonHosts = mapOf("torrentio.strem.fun" to "Torrentio", "AIOStreams.elfhosted.com" to "AIOStreams")

    // Live TV keeps its login only inside its URL; P3 reads it from the path and passes it as a
    // credential (ReportSecretSources.xtreamLogins). "4321" is too short to count (D6 step 3).
    private val known = ReportScrub.knownValuesFrom(
        storedUrls = fixture("stored-urls.txt").lines(),
        credentials = listOf("arshadk", "Pw7xQ2", "4321"),
        addonHosts = addonHosts.keys,
    )

    private fun scrub(text: String) = ReportScrub.scrub(text, known, addonHosts)

    private fun bare(text: String, addons: Map<String, String?> = emptyMap()) =
        ReportScrub.scrub(text, emptySet(), addons)

    @Test
    fun `no secret from the fixture survives`() {
        val out = scrub(fixture("secrets.txt"))
        val survivors = fixture("secret-values.txt").lines().filter { it.isNotBlank() && it in out }
        assertTrue("survived: $survivors\n$out", survivors.isEmpty())
    }

    @Test
    fun `secrets fixture comes out with the expected markers`() {
        assertEquals(fixture("secrets.expected.txt"), scrub(fixture("secrets.txt")))
    }

    @Test
    fun `ordinary diagnostic lines come out byte-identical`() {
        val text = fixture("unchanged.txt")
        assertEquals(text, scrub(text))
    }

    @Test
    fun `derivation takes credential positions at any case and skips path words`() {
        assertTrue("hQwErTyUiOpAsDfGhJkL" in known)
        assertTrue("qwertyuiopasdfgh" in known) // all lowercase, after a debrid name
        assertTrue("famuser" in known)
        assertTrue("correcthorse" in known) // userinfo password
        assertTrue("epguser" in known) // userinfo user
        assertTrue("sunshinelemon" in known) // JSON config in the path
        assertTrue("jackettusr" in known) // JSON username, plain lowercase, not passed as a credential
        assertTrue("ab&cd/ef12" in known) // `_`-tail name; query split before decoding keeps the encoded `&` and `/`
        assertFalse(known.any { it.equals("apiPassword", ignoreCase = true) }) // a name is never a value
        // Step 3 and step 4 share one name grammar: these names are never values, their values always are.
        assertTrue("short1" in known) // `tmdb_api_key`: a listed name after a `_` prefix
        assertTrue("abc123" in known) // `authToken`: camelCase
        assertTrue("abcdefg" in known) // `premiumizeToken` in a JSON config
        assertTrue("xcuser" in known) // `xc_username`
        for (name in listOf("tmdb_api_key", "authToken", "premiumizeToken", "xc_username")) {
            assertFalse(name, known.any { it.equals(name, ignoreCase = true) })
        }
        assertFalse("seeders" in known) // `sort_key` is not a credential name
        assertTrue("ab12cd" in known) // `x-auth-token`: the grammar after `-`, as step 4 reads it
        assertFalse(known.any { it.equals("x-auth-token", ignoreCase = true) })
        assertTrue("k9q2zz" in known) // a header name counts as a credential name in step 3
        assertFalse(known.any { it.equals("x-api-key", ignoreCase = true) })
        assertFalse("k3y9q" in known) // 5 chars: only ever inside a URL, which step 1 drops whole
        assertFalse("4321" in known)
        assertFalse(known.any { it.equals("manifest.json", ignoreCase = true) }) // also after `torbox/`
        assertFalse(known.any { it.equals("torrentio.strem.fun", ignoreCase = true) })
        assertFalse("m3u_plus" in known)
        assertFalse("providers" in known) // a plain lowercase word, not a secret
    }

    @Test
    fun `addon hosts take the first installed name, other-profile hosts no name`() {
        val text = "a Playing: https://torrentio.strem.fun/resolve/x/y.mkv\n" +
            "b ADDON_HOST paused host=torrentio.strem.fun. code=429\n" +
            "c GET https://other.addon.example/manifest.json\n" +
            "d mirror sub.torrentio.strem.fun"
        assertEquals(
            "a Playing: <url addon=Torrentio>\n" +
                "b ADDON_HOST paused host=<addon=Torrentio>. code=429\n" +
                "c GET <url host=other.addon.example>\n" +
                "d mirror sub.torrentio.strem.fun",
            scrub(text),
        )
        assertEquals(
            "c GET <url addon>",
            bare("c GET https://other.addon.example/manifest.json", mapOf("other.addon.example" to null)),
        )
        assertEquals(
            "host=<addon=First>",
            bare("host=one.example", linkedMapOf("ONE.example" to "First", "one.example" to "Second")),
        )
    }

    @Test
    fun `URL markers keep only the host and trailing punctuation`() {
        assertEquals(
            "(<url host=api.themoviedb.org>), <url ip lan>",
            bare("(https://api.themoviedb.org/3/movie/1?api_key=zz), http://192.168.1.4:8090/x"),
        )
    }

    @Test
    fun `URL host is read before percent-decoding the authority`() {
        assertEquals("x <url host=host.example> y", bare("x https://bob:p%2Fss@host.example/x y"))
        assertEquals("x <url host=host.example>", bare("x https://bob:hunter%2322@host.example/epg"))
        assertEquals("x <url host=host.example>", bare("x https://bob%3Ahunter!22%40host.example/epg"))
        assertEquals("x <url host=host.example>", bare("x https://host.example%2Flive%2Fuser%2FPw7xQ2"))
        assertEquals("x <url host=host.example>", bare("x https://bob:hunter%40host.example/x"))
        assertEquals("x <url host=h.example>", bare("x https://h.example%2Flive%40x"))
    }

    @Test
    fun `URLs after a digit or a percent escape are still whole URLs`() {
        assertEquals(
            "q=%3D<url host=panel.example.net>",
            bare("q=%3Dhttps%3A%2F%2Fpanel.example.net%2Fget.php%3Fpassword%3DZk81mnQx"),
        )
        assertEquals("#1<url host=a.example>", bare("#1https://a.example/k"))
    }

    @Test
    fun `addresses beside punctuation are found, versions and separators are not`() {
        assertEquals("to <ip>. near ...<ip lan>:8090", bare("to 81.2.69.160. near ...192.0.2.20:8090"))
        assertEquals("v1.2.3.4.5", bare("v1.2.3.4.5"))
        assertEquals(
            "/<ip>:443 <ip> a :: b ::1",
            bare("/2a02:c7f:7e1f:4100:1c2d:3e4f:5a6b:7c8d:443 fe80::1 a :: b ::1"),
        )
    }

    @Test(timeout = 10_000)
    fun `long alphanumeric runs scan in linear time`() {
        assertEquals("<token>", bare("a1".repeat(100_000)))
        bare("a_".repeat(100_000) + "=x") // credential-name prefix segments: no stack overflow
        bare("\"" + "-a_".repeat(100_000)) // JSON name prefix: one scan per quote
        val snake = "A_1".repeat(70_000)
        assertEquals(snake, bare(snake)) // a SCREAMING_SNAKE shape, kept; checked without a regex: no stack overflow
    }

    @Test
    fun `empty known values and hosts change nothing`() {
        assertEquals("abc def", ReportScrub.scrub("abc def", setOf(""), mapOf("" to "X")))
    }

    @Test
    fun `watch history lines are dropped`() {
        val text = "11-14 19:42:01.877  8812  8812 D WatchProgressPrefs: merged key=tt0111161\n" +
            "11-14 19:42:01.878  8812  8812 I PlayerViewModel: kept"
        assertEquals("11-14 19:42:01.878  8812  8812 I PlayerViewModel: kept", scrub(text))
    }

    @Test
    fun `control characters are escaped, tab and newline kept`() {
        assertEquals("a\\u0085b\\u0099c\\u001Bd\te\nf\\u000D", bare("a\u0085b\u0099c\u001Bd\te\nf\r"))
    }

    @Test
    fun `crash recorder header lines keep thread names`() {
        val line = ReportScrub.CRASH_HEADER_PREFIX + "thread=DefaultDispatcher-worker-12"
        assertEquals(line, bare(line))
    }
}
