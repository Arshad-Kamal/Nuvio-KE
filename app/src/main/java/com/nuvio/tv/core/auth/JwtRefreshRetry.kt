package com.nuvio.tv.core.auth

import kotlinx.coroutines.CancellationException

/** Runs [block]; if it fails with an expired JWT that refreshes successfully, runs it once more (cancellation always propagates). */
internal suspend fun <T> AuthManager.withJwtRefreshRetry(block: suspend () -> T): T {
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!refreshSessionIfJwtExpired(error)) throw error
        block()
    }
}
