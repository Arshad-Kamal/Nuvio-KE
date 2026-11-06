package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountErrorMessageResIdTest {

    @Test
    fun misconfiguredRedirectUrlWinsOverGenericInvalidTvLogin() {
        assertEquals(
            R.string.account_error_qr_login_misconfigured,
            accountErrorMessageResId("invalid tv login redirect base url")
        )
    }

    @Test
    fun invalidDeviceNonceWinsOverGenericTvLoginNonce() {
        assertEquals(
            R.string.account_error_qr_login_invalid_request,
            accountErrorMessageResId("tv login failed: invalid device nonce")
        )
    }

    @Test
    fun genericTvLoginBranchesStillMatch() {
        assertEquals(R.string.account_error_qr_login_expired, accountErrorMessageResId("tv login session expired"))
        assertEquals(R.string.account_error_invalid_qr_login, accountErrorMessageResId("invalid tv login code"))
        assertEquals(R.string.account_error_qr_login_other_device, accountErrorMessageResId("tv login nonce mismatch"))
    }

    @Test
    fun unknownMessageFallsBackToUnexpected() {
        assertEquals(R.string.account_error_unexpected, accountErrorMessageResId("something odd"))
    }
}
