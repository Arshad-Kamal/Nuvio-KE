package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingDeviceAuth
import com.nuvio.tv.core.tracking.TrackingDeviceCode
import com.nuvio.tv.core.tracking.TrackingDevicePoll
import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A profile's Simkl tokens (AUTH V2, Spec 106 S1). The access token lasts 7 days; it is refreshed a day before it
 * runs out, or after a 401. Simkl's refresh token is non-rotating and a refresh kills the previous access token at
 * once, so refreshes run one at a time per profile and a caller holding an already-replaced token just takes the new
 * one. A refused refresh (`invalid_grant`: the user removed the app on simkl.com, or 180 idle days) marks the account
 * [TrackingAccount.revoked]: the hub turns amber and sources fall back to Nuvio (Spec 104 C3).
 */
class SimklTokens(
    private val client: SimklClient,
    private val configuration: SimklApiConfiguration,
    private val accounts: TrackingAccountStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val locksGuard = Mutex()
    private val locks = mutableMapOf<Int, Mutex>()

    /** A token to send for [profileId], refreshed first when it is close to running out; null = signed out. */
    suspend fun accessToken(profileId: Int): String? {
        val account = usable(accounts.get(profileId, TrackingProviderId.SIMKL)) ?: return null
        val expiresAt = account.expiresAtEpochMs ?: return account.accessToken
        if (expiresAt - nowEpochMs() > REFRESH_AHEAD_MS) return account.accessToken
        return try {
            refresh(profileId, account.accessToken)
        } catch (failure: SimklException) {
            // Simkl unreachable: a token that has not run out yet still works; an expired one would only 401.
            if (nowEpochMs() < expiresAt) account.accessToken else throw failure
        }
    }

    /**
     * Replaces [staleAccessToken]; returns the token to use now, or null when the grant is gone (now marked revoked).
     * Throws [SimklException] when Simkl could not be reached (the account is left as it was).
     */
    suspend fun refresh(profileId: Int, staleAccessToken: String): String? = lockFor(profileId).withLock {
        val current = usable(accounts.get(profileId, TrackingProviderId.SIMKL)) ?: return@withLock null
        if (current.accessToken != staleAccessToken) return@withLock current.accessToken
        val refreshToken = current.refreshToken ?: run {
            markRevoked(profileId, current)
            return@withLock null
        }
        val clientId = configuration.requireClientId()
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("client_id", clientId)
            put("refresh_token", refreshToken)
        }.toString()
        val tokens = try {
            parseTokens(client.execute(SimklRequest(SimklHttpMethod.POST, "/oauth2/token", jsonBody = body), null, clientId).body)
        } catch (failure: SimklApiException) {
            if (failure.status == 400 && failure.error == "invalid_grant") {
                markRevoked(profileId, current)
                // A sign-in that landed during the call is kept by markRevoked: use it.
                return@withLock usable(accounts.get(profileId, TrackingProviderId.SIMKL))?.accessToken
            }
            throw failure
        }
        var saved: String? = null
        accounts.update(profileId, TrackingProviderId.SIMKL) { stored ->
            stored?.takeIf { it.refreshToken == refreshToken && !it.revoked }
                ?.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken ?: refreshToken,
                    expiresAtEpochMs = tokens.expiresAtEpochMs(nowEpochMs())
                )
                ?.also { saved = it.accessToken }
        }
        // Not written: a sign-in or sign-out landed during the call. Use whatever the profile holds now.
        saved ?: usable(accounts.get(profileId, TrackingProviderId.SIMKL))?.accessToken
    }

    private suspend fun markRevoked(profileId: Int, seen: TrackingAccount) {
        accounts.update(profileId, TrackingProviderId.SIMKL) { stored ->
            stored?.takeIf { it.accessToken == seen.accessToken }?.copy(revoked = true)
        }
    }

    private suspend fun lockFor(profileId: Int): Mutex = locksGuard.withLock { locks.getOrPut(profileId) { Mutex() } }

    private companion object {
        const val REFRESH_AHEAD_MS = 24L * 60 * 60 * 1_000
    }
}

private fun usable(account: TrackingAccount?): TrackingAccount? = account?.takeIf { !it.revoked && it.accessToken.isNotBlank() }

/**
 * Simkl's AUTH V2 device sign-in (RFC 8628) behind Spec 104's sign-in screen. A decline on the phone writes nothing
 * at Simkl, so a poll never answers Denied: the screen's own expiry (15 min) ends an unanswered sign-in.
 * [onGrantChange] runs once an approval is saved and after a disconnect: it drops the profile's synced Simkl data,
 * which belonged to the previous grant (maybe another Simkl account). After the save, so a sync that read the old
 * account before it cannot save under the new generation.
 */
class SimklDeviceAuth(
    private val client: SimklClient,
    private val configuration: SimklApiConfiguration,
    private val accounts: TrackingAccountStore,
    private val session: SimklSession,
    private val onGrantChange: suspend (profileId: Int) -> Unit = {},
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : TrackingDeviceAuth {

    override suspend fun start(profileId: Int): TrackingDeviceCode {
        val clientId = configuration.requireClientId()
        val reply = client.execute(simklDeviceCodeRequest(clientId), null, clientId)
        val code = decode(SimklDeviceCodeReply.serializer(), reply.body)
        return TrackingDeviceCode(
            deviceCode = code.deviceCode,
            userCode = code.userCode,
            verificationUri = code.verificationUri,
            verificationUriComplete = code.verificationUriComplete,
            expiresAtEpochMs = nowEpochMs() + code.expiresIn.coerceAtLeast(1) * 1_000L,
            intervalSeconds = code.interval.coerceAtLeast(1)
        )
    }

    override suspend fun poll(profileId: Int, code: TrackingDeviceCode): TrackingDevicePoll {
        val clientId = configuration.requireClientId()
        val body = buildJsonObject {
            put("grant_type", DEVICE_GRANT)
            put("client_id", clientId)
            put("device_code", code.deviceCode)
        }.toString()
        val tokens = try {
            parseTokens(client.execute(SimklRequest(SimklHttpMethod.POST, "/oauth2/token", jsonBody = body), null, clientId).body)
        } catch (failure: SimklApiException) {
            return when (failure.status to failure.error) {
                400 to "authorization_pending" -> TrackingDevicePoll.Pending
                400 to "slow_down" -> TrackingDevicePoll.SlowDown
                400 to "expired_token" -> TrackingDevicePoll.Expired
                else -> throw failure
            }
        }
        // A new grant, maybe another Simkl account: the old account's quota state (a pause) and data are not its.
        session.quota.clear(profileId)
        // Together: a sign-in closed mid-way must not leave the new account over the old account's data. Cleared before
        // too, so a process killed between the two cannot either; after, so a sync that read the old account cannot.
        withContext(NonCancellable) {
            onGrantChange(profileId)
            accounts.save(
                profileId,
                TrackingProviderId.SIMKL,
                TrackingAccount(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresAtEpochMs = tokens.expiresAtEpochMs(nowEpochMs())
                )
            )
            onGrantChange(profileId)
        }
        fillAccountName(profileId)
        return TrackingDevicePoll.Approved
    }

    /**
     * Revokes the grant at Simkl (best effort, 10 s; revoking either token ends the whole grant, and Simkl always
     * answers 200), then forgets it on this TV whatever happened.
     */
    override suspend fun disconnect(profileId: Int) {
        try {
            val account = accounts.get(profileId, TrackingProviderId.SIMKL)
            val token = account?.refreshToken ?: account?.accessToken ?: return
            // No app id on this TV any more: the grant cannot be revoked from here (simkl.com can).
            val clientId = configuration.clientIdOrNull() ?: return
            val body = buildJsonObject {
                put("client_id", clientId)
                put("token", token)
            }.toString()
            try {
                withTimeoutOrNull(REVOKE_TIMEOUT_MS) {
                    client.execute(SimklRequest(SimklHttpMethod.POST, "/oauth2/revoke", jsonBody = body), null, clientId)
                }
            } catch (_: SimklException) {
                // The grant stays on simkl.com (the user can remove it there); this TV forgets it below.
            }
        } finally {
            // Even when the caller is cancelled mid-revoke: Disconnect must never leave the account behind.
            withContext(NonCancellable) {
                accounts.clear(profileId, TrackingProviderId.SIMKL)
                session.quota.clear(profileId)
                onGrantChange(profileId)
            }
        }
    }

    /** The hub shows the Simkl name (`/users/settings`); a failure leaves it blank until a later sign-in. */
    private suspend fun fillAccountName(profileId: Int) {
        val name = try {
            decode(SimklUserSettingsReply.serializer(), session.call(profileId, SimklRequest(SimklHttpMethod.GET, "/users/settings")).body)
                .user?.name?.takeIf(String::isNotBlank)
        } catch (_: SimklException) {
            null
        } ?: return
        accounts.update(profileId, TrackingProviderId.SIMKL) { stored -> stored?.copy(accountName = name) }
    }

    private companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val REVOKE_TIMEOUT_MS = 10_000L
    }
}

/** What every sign-in asks for: `media:read` alone would forbid writes. */
private const val SIMKL_SCOPE = "media:read media:write"

/** A device sign-in code for [clientId]: the sign-in's first call, and the app id check ([checkSimklAppId]). */
internal fun simklDeviceCodeRequest(clientId: String): SimklRequest = SimklRequest(
    SimklHttpMethod.POST,
    "/oauth2/device",
    jsonBody = buildJsonObject {
        put("client_id", clientId)
        put("scope", SIMKL_SCOPE)
    }.toString()
)

internal data class SimklTokenSet(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long?) {
    fun expiresAtEpochMs(nowEpochMs: Long): Long? = expiresInSeconds?.takeIf { it > 0 }?.let { nowEpochMs + it * 1_000L }

    override fun toString(): String = "SimklTokenSet(expiresInSeconds=$expiresInSeconds)"
}

internal fun parseTokens(body: String): SimklTokenSet {
    val reply = decode(SimklTokenReply.serializer(), body)
    val access = reply.accessToken?.takeIf(String::isNotBlank) ?: throw SimklException("Simkl token reply without a token")
    return SimklTokenSet(access, reply.refreshToken?.takeIf(String::isNotBlank), reply.expiresIn)
}

/** Decodes a Simkl reply; a wrong shape is a [SimklException] whose message never carries the body (KT-JSON-04). */
internal fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, body: String): T = try {
    simklJson.decodeFromString(serializer, body)
} catch (_: SerializationException) {
    throw SimklException("Simkl reply has an unexpected shape")
} catch (_: IllegalArgumentException) {
    throw SimklException("Simkl reply has an unexpected shape")
}

@Serializable
internal data class SimklTokenReply(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
) {
    override fun toString(): String = "SimklTokenReply(expiresIn=$expiresIn)"
}

@Serializable
internal data class SimklDeviceCodeReply(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 900,
    @SerialName("interval") val interval: Int = 5
) {
    override fun toString(): String = "SimklDeviceCodeReply(userCode=$userCode)"
}

@Serializable
internal data class SimklUserSettingsReply(val user: SimklUser? = null)

@Serializable
internal data class SimklUser(val name: String? = null)
