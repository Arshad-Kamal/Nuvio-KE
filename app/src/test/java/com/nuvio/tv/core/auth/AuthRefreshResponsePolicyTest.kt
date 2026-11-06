package com.nuvio.tv.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRefreshResponsePolicyTest {

    @Test
    fun `retries rate limits and request timeouts for refresh endpoint`() {
        assertTrue(refreshResponse(statusCode = 408))
        assertTrue(refreshResponse(statusCode = 429))
    }

    @Test
    fun `retries cloudflare forbidden response`() {
        assertTrue(refreshResponse(statusCode = 403, cloudflareRay = "ray-id"))
        assertTrue(refreshResponse(statusCode = 403, server = "cloudflare"))
    }

    @Test
    fun `does not retry gotrue invalid session responses`() {
        assertFalse(refreshResponse(statusCode = 400))
        assertFalse(refreshResponse(statusCode = 401))
        assertFalse(refreshResponse(statusCode = 403))
    }

    @Test
    fun `does not apply auth policy to other requests`() {
        assertFalse(
            shouldRetryAuthRefreshResponse(
                statusCode = 429,
                path = "/rest/v1/profiles",
                grantType = null,
                server = null,
                cloudflareRay = null
            )
        )
    }

    @Test
    fun `invalidates only explicit gotrue session failures`() {
        assertTrue(isInvalidAuthRefreshResponse(400, "Refresh token is not valid"))
        assertTrue(isInvalidAuthRefreshResponse(401, "refresh_token_not_found"))
        assertTrue(isInvalidAuthRefreshResponse(403, "session_not_found"))
        assertFalse(isInvalidAuthRefreshResponse(403, "Cloudflare request blocked"))
        assertFalse(isInvalidAuthRefreshResponse(429, "Refresh token is not valid"))
    }

    @Test
    fun `message classification covers the merged marker superset`() {
        // "invalid token" lived only in AuthManager's diverged copy before the lists
        // were unified here (single-home fix).
        assertTrue(isInvalidAuthSessionMessage("refresh failed: invalid token"))
        assertTrue(isInvalidAuthSessionMessage("INVALID_GRANT returned"))
        assertFalse(isInvalidAuthSessionMessage("network unreachable"))
        assertTrue(isInvalidAuthRefreshResponse(401, "{\"error\":\"invalid token\"}"))
    }

    private fun refreshResponse(
        statusCode: Int,
        server: String? = null,
        cloudflareRay: String? = null
    ): Boolean = shouldRetryAuthRefreshResponse(
        statusCode = statusCode,
        path = "/auth/v1/token",
        grantType = "refresh_token",
        server = server,
        cloudflareRay = cloudflareRay
    )
}
