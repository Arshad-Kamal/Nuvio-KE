package com.nuvio.tv.core.update

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 52 P6/P7: asset pick, the public-build gate, the outcome mapping and the daily throttle. */
class UpdateReleaseTest {

    private val digest = "sha256:" + "AB".repeat(32)

    private fun asset(
        name: String,
        state: String? = "uploaded",
        size: Long? = 41_000_000L,
        digest: String? = this.digest,
        url: String? = "https://github.com/Arshad-Kamal/Nuvio-KE/releases/download/v1/$name"
    ) = GitHubAssetDto(name = name, state = state, size = size, digest = digest, browserDownloadUrl = url)

    private fun release(vararg assets: GitHubAssetDto, body: String? = "Fixes") =
        GitHubReleaseDto(name = "Nuvio KE 0.9.0-ke51", body = body, publishedAt = "2026-11-03T10:00:00Z", assets = assets.toList())

    private val v7a = "NuvioKE-0.9.0-ke51-1392-armeabi-v7a.apk"
    private val v8a = "NuvioKE-0.9.0-ke51-1392-arm64-v8a.apk"

    @Test
    fun `picks the first supported ABI's APK and reads name, code and digest`() {
        val offer = releaseOffer(release(asset(v8a), asset(v7a)), listOf("armeabi-v7a", "armeabi"))!!
        assertEquals("armeabi-v7a", offer.abi)
        assertEquals("0.9.0-ke51", offer.versionName)
        assertEquals(1392, offer.versionCode)
        assertEquals("ab".repeat(32), offer.sha256)
        assertEquals(41_000_000L, offer.sizeBytes)
        assertEquals("Fixes", offer.notes)
        assertEquals("2026-11-03T10:00:00Z", offer.publishedAt)
        assertEquals("arm64-v8a", releaseOffer(release(asset(v7a), asset(v8a)), listOf("arm64-v8a", "armeabi-v7a"))!!.abi)
        assertNull(releaseOffer(release(asset(v8a)), listOf("armeabi-v7a")))
        assertNull(releaseOffer(release(asset(v7a)), listOf("x86_64")))
    }

    @Test
    fun `an asset counts only when name, state, size, digest and URL all hold`() {
        fun offered(asset: GitHubAssetDto) = releaseOffer(release(asset), listOf("armeabi-v7a")) != null
        assertTrue(offered(asset(v7a)))
        assertFalse(offered(asset("NuvioKE-0.9.0-ke51-armeabi-v7a.apk")))
        assertFalse(offered(asset("NuvioKE-0.9.0-ke51-1392-universal.apk")))
        assertFalse(offered(asset("Other-0.9.0-ke51-1392-armeabi-v7a.apk")))
        assertFalse(offered(asset("NuvioKE-0.9.0-ke51-99999999999-armeabi-v7a.apk")))
        assertFalse(offered(asset(v7a, state = "open")))
        assertFalse(offered(asset(v7a, size = 0L)))
        assertFalse(offered(asset(v7a, size = MAX_ASSET_BYTES + 1)))
        assertTrue(offered(asset(v7a, size = MAX_ASSET_BYTES)))
        assertFalse(offered(asset(v7a, digest = null)))
        assertFalse(offered(asset(v7a, digest = "sha512:" + "ab".repeat(32))))
        assertFalse(offered(asset(v7a, digest = "sha256:abc")))
        assertFalse(offered(asset(v7a, url = "http://github.com/x/$v7a")))
        assertFalse(offered(asset(v7a, url = null)))
    }

    @Test
    fun `of two APKs for one ABI the higher code wins, and notes are capped`() {
        val older = asset("NuvioKE-0.9.0-ke50-1391-armeabi-v7a.apk")
        val offer = releaseOffer(release(older, asset(v7a), body = "x".repeat(MAX_NOTES_CHARS + 10)), listOf("armeabi-v7a"))!!
        assertEquals(1392, offer.versionCode)
        assertEquals(MAX_NOTES_CHARS, offer.notes.length)
    }

    @Test
    fun `outcome per answer`() {
        val abis = listOf("armeabi-v7a")
        assertEquals(1392, (checkOutcome(200, release(asset(v7a)), abis) as CheckOutcome.Answered).offer?.versionCode)
        assertEquals(CheckOutcome.Answered(null), checkOutcome(200, release(), abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(200, null, abis))
        assertEquals(CheckOutcome.NoRelease, checkOutcome(404, null, abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(403, null, abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(429, null, abis))
        assertEquals(CheckOutcome.Failed, checkOutcome(500, null, abis))
    }

    @Test
    fun `GitHub's rate limit is its own outcome, with the reset time it gives`() {
        val now = 1_763_340_000_000L
        fun h(vararg kv: String) = okhttp3.Headers.headersOf(*kv)
        assertEquals(
            CheckOutcome.RateLimited(1_763_350_000_000L),
            rateLimitOutcome(403, h("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1763350000"), now)
        )
        assertEquals(CheckOutcome.RateLimited(now + 60_000L), rateLimitOutcome(429, h("retry-after", "60"), now))
        // The primary limit's reset wins; a secondary limit (requests left) uses retry-after; neither readable = no time.
        assertEquals(
            CheckOutcome.RateLimited(1_763_350_000_000L),
            rateLimitOutcome(429, h("retry-after", "60", "x-ratelimit-remaining", "0", "x-ratelimit-reset", "1763350000"), now)
        )
        assertEquals(
            CheckOutcome.RateLimited(now + 60_000L),
            rateLimitOutcome(403, h("retry-after", "60", "x-ratelimit-remaining", "40", "x-ratelimit-reset", "1763350000"), now)
        )
        assertEquals(CheckOutcome.RateLimited(null), rateLimitOutcome(429, h("retry-after", "99999999999999999"), now))
        assertEquals(CheckOutcome.RateLimited(null), rateLimitOutcome(403, h("x-ratelimit-remaining", "0"), now))
        assertEquals(CheckOutcome.RateLimited(null), rateLimitOutcome(429, h("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT"), now))
        // Not the rate limit: another status, or a 403 with requests left (a plain refusal).
        assertNull(rateLimitOutcome(403, h("x-ratelimit-remaining", "12"), now))
        assertNull(rateLimitOutcome(403, h(), now))
        assertNull(rateLimitOutcome(500, h("x-ratelimit-remaining", "0"), now))
    }

    @Test
    fun `a failed answer's note carries the status and GitHub's rate-limit headers`() {
        val limited = okhttp3.Headers.headersOf("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1763350000", "etag", "x")
        assertEquals("http 403 x-ratelimit-remaining=0 x-ratelimit-reset=1763350000", gitHubAnswerNote(403, limited))
        assertEquals("http 429 retry-after=60", gitHubAnswerNote(429, okhttp3.Headers.headersOf("retry-after", "60")))
        assertEquals("http 500", gitHubAnswerNote(500, okhttp3.Headers.headersOf()))
    }

    @Test
    fun `auto check at most once a day, and when the clock went back`() {
        val day = AUTO_CHECK_INTERVAL_MS
        assertTrue(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 0L))
        assertFalse(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 10 * day - day + 1))
        assertTrue(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 9 * day))
        assertTrue(autoCheckDue(nowMs = 10 * day, lastAttemptMs = 10 * day + 1))
    }

    @Test
    fun `badge, rail dot and pill show only for a known newer release with Check automatically on`() {
        val release = releaseOffer(release(asset(v7a)), listOf("armeabi-v7a"))!!
        val shown = UpdateState(supported = true, autoCheck = true, available = release)
        assertTrue(shown.noticeShown)
        assertFalse(shown.copy(autoCheck = false).noticeShown)
        assertFalse(shown.copy(available = null).noticeShown)
        assertFalse(shown.copy(supported = false).noticeShown)
    }

    @Test
    fun `only a keyless release build under the KE id checks`() {
        assertTrue(updateCheckSupported("", debugBuild = false, applicationId = "com.nuvio.tv.ke"))
        assertFalse(updateCheckSupported("0123abcd", debugBuild = false, applicationId = "com.nuvio.tv.ke"))
        assertFalse(updateCheckSupported("", debugBuild = true, applicationId = "com.nuvio.tv.ke"))
        assertFalse(updateCheckSupported("", debugBuild = false, applicationId = "com.nuvio.tv.ke.debug"))
    }

    // --- Spec 89 D5: Get test releases ---

    private fun named(code: Int, prerelease: Boolean = false, draft: Boolean = false) = GitHubReleaseDto(
        name = "r$code", prerelease = prerelease, draft = draft,
        assets = listOf(asset("NuvioKE-1.2.0-$code-armeabi-v7a.apk"))
    )

    @Test
    fun `the newest offer is the highest versionCode, drafts skipped, a published release wins a tie`() {
        val abis = listOf("armeabi-v7a")
        val offer = newestOffer(listOf(named(1431, draft = true), named(1430, prerelease = true), named(1423)), abis)!!
        assertEquals(1430, offer.versionCode)
        assertTrue(offer.prerelease)
        assertFalse(newestOffer(listOf(named(1430, prerelease = true), named(1430)), abis)!!.prerelease)
        assertFalse(releaseOffer(named(1423), abis)!!.prerelease)
        assertNull(newestOffer(emptyList(), abis))
        assertNull(newestOffer(listOf(named(1431, draft = true)), abis))
    }

    @Test
    fun `the list outcome maps like the latest one`() {
        val abis = listOf("armeabi-v7a")
        assertEquals(1430, (listCheckOutcome(200, listOf(named(1430, prerelease = true)), abis) as CheckOutcome.Answered).offer?.versionCode)
        assertEquals(CheckOutcome.Answered(null), listCheckOutcome(200, emptyList(), abis))
        assertEquals(CheckOutcome.NoRelease, listCheckOutcome(404, null, abis))
        assertEquals(CheckOutcome.Failed, listCheckOutcome(403, null, abis))
        assertEquals(CheckOutcome.Failed, listCheckOutcome(200, null, abis))
    }
}
