package com.nuvio.tv.core.auth

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** DRDYK B5/K1: the one sync retry helper never turns cancellation into a refresh or a retry. */
class JwtRefreshRetryTest {
    private val authManager = mockk<AuthManager>()

    @Test
    fun `cancellation propagates without a refresh or a second attempt`() = runTest {
        val cancellation = CancellationException("scope cancelled")
        var attempts = 0

        try {
            authManager.withJwtRefreshRetry<Unit> {
                attempts++
                throw cancellation
            }
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        assertEquals(1, attempts)
        coVerify(exactly = 0) { authManager.refreshSessionIfJwtExpired(any()) }
    }

    @Test
    fun `an expired JWT that refreshes runs the block once more`() = runTest {
        coEvery { authManager.refreshSessionIfJwtExpired(any()) } returns true
        var attempts = 0

        val result = authManager.withJwtRefreshRetry {
            if (++attempts == 1) throw IllegalStateException("JWT expired")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `a failure that does not refresh rethrows the original error after one attempt`() = runTest {
        coEvery { authManager.refreshSessionIfJwtExpired(any()) } returns false
        val failure = IllegalStateException("network down")
        var attempts = 0

        try {
            authManager.withJwtRefreshRetry<Unit> {
                attempts++
                throw failure
            }
            fail("failure was swallowed")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }

        assertEquals(1, attempts)
    }
}
