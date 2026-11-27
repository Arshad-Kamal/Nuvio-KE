package com.nuvio.tv.data.simkl

/**
 * Signed-in Simkl calls for one profile: the quota gate, the token (refreshed once after a 401) and the quota headers.
 * A `401 user_token_required` means no token was attached, so it is not refreshed.
 */
class SimklSession(
    private val client: SimklClient,
    private val tokens: SimklTokens,
    val quota: SimklQuota
) {
    suspend fun call(profileId: Int, request: SimklRequest): SimklResponse {
        quota.checkAvailable(profileId)
        val token = tokens.accessToken(profileId) ?: throw SimklSignedOutException()
        return try {
            send(profileId, request, token)
        } catch (failure: SimklApiException) {
            if (failure.status != 401 || failure.error == "user_token_required") throw failure
            val fresh = tokens.refresh(profileId, token) ?: throw SimklSignedOutException()
            send(profileId, request, fresh)
        }
    }

    private suspend fun send(profileId: Int, request: SimklRequest, token: String): SimklResponse = client.execute(
        request,
        token,
        beforeSend = { quota.checkAvailable(profileId) },
        onAnswer = { response, failure ->
            response?.let { quota.record(profileId, it) }
            failure?.let { quota.recordFailure(profileId, it) }
        }
    )
}
