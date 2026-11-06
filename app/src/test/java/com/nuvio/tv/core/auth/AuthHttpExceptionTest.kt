package com.nuvio.tv.core.auth

import com.nuvio.tv.R
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.ui.screens.account.accountErrorMessageResId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-02 (CWE-532): the logged message carries no response body; classification still sees it. */
class AuthHttpExceptionTest {

    private val body =
        """{"error":"invalid_grant","error_description":"Invalid login credentials","refresh_token":"SECRET-RT"}"""
    private val error = AuthHttpException("/auth/v1/token?grant_type=password", 400, body)

    @Test
    fun `the logged message and diagnostic summary leave the body out`() {
        val wrapped = IllegalStateException("sign in failed", error)

        assertEquals("Auth request failed endpoint=/auth/v1/token?grant_type=password status=400", error.message)
        assertFalse(wrapped.diagnosticSummary().contains("SECRET-RT"))
        assertFalse(wrapped.diagnosticSummary().contains("Invalid login credentials"))
    }

    @Test
    fun `marker matching still reads the body`() {
        val wrapped = IllegalStateException("refresh failed", error)

        assertTrue(isInvalidAuthSessionMessage(wrapped.causeMessages()))
        assertEquals(
            R.string.account_error_invalid_credentials,
            accountErrorMessageResId(error.markerText().orEmpty().lowercase())
        )
    }

    @Test
    fun `a jwt expired body is still recognised`() {
        val expired = AuthHttpException("/rest/v1/rpc/x", 401, """{"message":"JWT expired"}""")

        assertTrue(IllegalStateException("call failed", expired).isJwtExpiredAuthError())
    }
}
