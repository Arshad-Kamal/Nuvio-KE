package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingKeyAuth
import com.nuvio.tv.core.tracking.TrackingProviderId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Connect and disconnect with the user's own API key pasted from the phone (Spec 105 D6-D8, E2-E4): no client id, no
 * device code, in every build. One key per profile serves tracking and ratings (D7). Every call names the profile the
 * key panel was opened on, so a profile switch meanwhile never saves the key onto another profile. Answers: not shaped
 * like a key, or `401`/`403` on `/user` = [PasteCheck.Refused] (E4); any other failure, the daily `429` included (a
 * spent account cannot be checked until the reset), or a `200` naming no user = [PasteCheck.Unreachable]; the panel
 * closed before the save = [PasteCheck.Closed]. Nothing is kept unless the answer is [PasteCheck.Saved].
 * [checkClient] is its own [MdbListClient] (own lock), so a key check never queues behind a background sync.
 * [onKeyChange] drops the profile's synced data (it was the old key's account); on connect it runs before and after the
 * save, so a sync that started in between cannot keep the old account's data, and the save and both drops run to the
 * end together even if the caller is cancelled.
 */
class MdbListKeyAuth(
    private val checkClient: MdbListClient,
    private val session: MdbListSession,
    private val accounts: TrackingAccountStore,
    private val onKeyChange: suspend (profileId: Int) -> Unit = {}
) : TrackingKeyAuth {
    override suspend fun connect(profileId: Int, key: String, stillWanted: () -> Boolean): PasteCheck {
        val trimmed = key.trim()
        if (!KEY_SHAPE.matches(trimmed)) return PasteCheck.Refused
        // Not gated on the profile's pause: a new key may be another account with its own allowance.
        val response = try {
            checkClient.execute(MdbListRequest(MdbListHttpMethod.GET, USER_PATH), apiKey = { trimmed })
        } catch (failure: MdbListApiException) {
            return if (failure.status == 401 || failure.status == 403) PasteCheck.Refused else PasteCheck.Unreachable
        } catch (_: MdbListException) {
            return PasteCheck.Unreachable
        }
        val user = try {
            decodeMdbList(MdbListUserReply.serializer(), response.body)
        } catch (_: MdbListException) {
            return PasteCheck.Unreachable
        }
        // A 200 that names no user (an error envelope, another shape) proves nothing about the key: keep nothing.
        val name = user.username?.takeIf(String::isNotBlank) ?: return PasteCheck.Unreachable
        if (!stillWanted()) return PasteCheck.Closed
        withContext(NonCancellable) {
            onKeyChange(profileId)
            accounts.save(profileId, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = trimmed, accountName = name))
            onKeyChange(profileId)
            session.quota.clear(profileId)
            session.quota.record(profileId, response)
            session.quota.recordPlan(profileId, user.plan)
        }
        return PasteCheck.Saved
    }

    /** Forgets the key on this TV (ratings too, D7). MDBList has no per-app revoke for a key (E3's note). */
    override suspend fun disconnect(profileId: Int) {
        withContext(NonCancellable) {
            accounts.clear(profileId, TrackingProviderId.MDBLIST)
            onKeyChange(profileId)
            session.quota.clear(profileId)
        }
    }

    /**
     * A2's Plan line after a restart (the quota is in memory): one `/user` call when the profile has a working key and
     * its plan is not known yet. The plan is kept only if the key did not change during the call. Throws
     * [MdbListException] as any call does (signed out and paused send nothing).
     */
    suspend fun loadPlan(profileId: Int) {
        if (session.quota.state(profileId).plan != null) return
        val key = session.currentKey(profileId) ?: return
        val reply = session.call(profileId, MdbListRequest(MdbListHttpMethod.GET, USER_PATH))
        val plan = decodeMdbList(MdbListUserReply.serializer(), reply.body).plan
        if (session.currentKey(profileId) == key) session.quota.recordPlan(profileId, plan)
    }

    private companion object {
        const val USER_PATH = "/user"

        /** MDBList does not document the key format; this only keeps out what cannot be one. */
        val KEY_SHAPE = Regex("[A-Za-z0-9_-]{8,128}")
    }
}

@Serializable
private data class MdbListUserReply(
    @SerialName("username") val username: String? = null,
    @SerialName("plan") val plan: String? = null
)
