package com.nuvio.tv.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AuthSessionValidationPolicyTest {

    // The two marker lists as they stood before L9-54 moved them into one home;
    // pinned so the shared-core refactor provably classifies every marker the same.
    private val preMergeRefreshMarkers = listOf(
        "invalid refresh token", "refresh token is not valid", "refresh token not found",
        "refresh_token_not_found", "invalid_grant", "session not found", "session_not_found",
        "invalid session", "invalid token"
    )
    private val preMergeRemoteMarkers = listOf(
        "session not found", "session_not_found", "invalid session", "invalid token",
        "invalid jwt", "jwt expired", "user not found", "user does not exist"
    )

    @Test
    fun `every marker of either list classifies exactly as before the merge`() {
        val union = (preMergeRefreshMarkers + preMergeRemoteMarkers).distinct()
        for (marker in union + "network unreachable") {
            for (text in listOf(marker, marker.uppercase(), "auth error: $marker (code 400)")) {
                val lower = text.lowercase()
                assertEquals(text, preMergeRefreshMarkers.any(lower::contains), isInvalidAuthSessionMessage(text))
                assertEquals(text, preMergeRemoteMarkers.any(lower::contains), isInvalidRemoteSessionResponse(null, text))
            }
        }
    }

    @Test
    fun `cause walk finds the first matching cause and joins every message`() {
        val root = IOException("Invalid JWT")
        val error = IllegalStateException("refresh failed", RuntimeException(null, root))

        assertSame(root, error.findCause<IOException>())
        assertNull(error.findCause<ArithmeticException>())
        assertEquals("refresh failed Invalid JWT", error.causeMessages())
    }

    @Test
    fun `jwt expired is detected anywhere in the cause chain`() {
        assertTrue(IllegalStateException("outer", IOException("JWT expired")).isJwtExpiredAuthError())
        assertFalse(IllegalStateException("outer", IOException("jwt")).isJwtExpiredAuthError())
    }

    @Test
    fun `invalidates unauthorized auth responses`() {
        assertTrue(isInvalidRemoteSessionResponse(401, "Unauthorized"))
    }

    @Test
    fun `invalidates explicit revoked session responses`() {
        assertTrue(isInvalidRemoteSessionResponse(400, "session_not_found"))
        assertTrue(isInvalidRemoteSessionResponse(403, "Invalid session"))
        assertTrue(isInvalidRemoteSessionResponse(null, "Invalid JWT"))
        assertTrue(isInvalidRemoteSessionResponse(404, "User does not exist"))
    }

    @Test
    fun `keeps cached session for transient failures`() {
        assertFalse(isInvalidRemoteSessionResponse(403, "Forbidden"))
        assertFalse(isInvalidRemoteSessionResponse(403, "Request blocked by proxy"))
        assertFalse(isInvalidRemoteSessionResponse(429, "Too many requests"))
        assertFalse(isInvalidRemoteSessionResponse(503, "Service unavailable"))
        assertFalse(isInvalidRemoteSessionResponse(null, "Unable to resolve host"))
    }
}
