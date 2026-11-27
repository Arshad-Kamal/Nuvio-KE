package com.nuvio.tv.data.simkl

import com.nuvio.tv.R
import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.server.PasteKeyPageStrings
import com.nuvio.tv.core.tracking.TrackingProviderSetup
import com.nuvio.tv.core.tracking.TrackingSetupStep
import com.nuvio.tv.core.tracking.TrackingSetupText
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * This TV's Simkl app id (Spec 106 D8/D9): one plain-text file in app storage, shared by every profile, never synced,
 * kept across same-signature updates. Written to a temp file, then renamed over, so a crash leaves the old id or the
 * new one. An unreadable or malformed file reads as no id (the setup asks again). [appId] is null until the first
 * read; [get] waits for it.
 */
class SimklAppIdStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher,
    scope: CoroutineScope
) {
    private val _appId = MutableStateFlow<String?>(null)
    val appId: StateFlow<String?> = _appId.asStateFlow()
    private val writeLock = Mutex()
    private val loaded: Deferred<Unit> = scope.async(ioDispatcher) { _appId.value = read() }

    suspend fun get(): String? {
        loaded.await()
        return _appId.value
    }

    /** Saves [id] for this TV; null forgets it. Throws [IOException] when the file cannot be written (nothing changed). */
    suspend fun save(id: String?) = writeLock.withLock {
        loaded.await()
        withContext(ioDispatcher) {
            if (id == null) {
                if (file.exists() && !file.delete()) throw IOException("Simkl app id not removed")
            } else {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "${file.name}.tmp")
                FileOutputStream(temp).use { out ->
                    out.write(id.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!temp.renameTo(file)) {
                    temp.delete()
                    throw IOException("Simkl app id not saved")
                }
            }
        }
        _appId.value = id
    }

    private fun read(): String? = try {
        if (file.isFile) cleanSimklAppId(file.readText()) else null
    } catch (_: IOException) {
        null
    }
}

/**
 * A pasted id as Simkl's API takes it: whitespace dropped, then letters, digits, `-` and `_` only (it goes into a URL
 * query and a JSON body), 8 to 128 long. Null = not an id.
 */
internal fun cleanSimklAppId(raw: String): String? = raw.filterNot(Char::isWhitespace).takeIf(SIMKL_APP_ID::matches)

/**
 * Asks Simkl for a device sign-in code with [id] (K3): no account needed and not counted against any allowance. 200 =
 * Simkl knows the app (the code is dropped); a 4xx (`401 invalid_client` for an unknown id) = refused; anything else
 * (no network, 429, 5xx) = unreachable, nothing decided.
 */
internal suspend fun checkSimklAppId(client: SimklClient, id: String): PasteCheck {
    return try {
        client.execute(simklDeviceCodeRequest(id), null, id)
        PasteCheck.Saved
    } catch (failure: SimklApiException) {
        if (failure.status in 400..499 && failure.status != 429) PasteCheck.Refused else PasteCheck.Unreachable
    } catch (_: SimklException) {
        PasteCheck.Unreachable
    }
}

/**
 * Simkl's per-TV setup (K1-K5). Submits and removes run one at a time. Any change of the saved id (a first id,
 * Replace, Remove) signs every profile on this TV out first, while the old id is still the one requests use: their
 * grants belong to the old app ([signOut] revokes, when there is an id to revoke with, and resets that profile's
 * sources). Once Simkl said yes, the sign-outs and the save finish
 * even if the caller is cancelled.
 */
class SimklAppIdSetup(
    private val store: SimklAppIdStore,
    private val client: SimklClient,
    private val profileIds: () -> Collection<Int>,
    private val signOut: suspend (profileId: Int) -> Unit,
    scope: CoroutineScope
) : TrackingProviderSetup {
    private val lock = Mutex()

    override val savedHint: StateFlow<String?> = store.appId
        .map { it?.takeLast(HINT_LENGTH) }
        .stateIn(scope, SharingStarted.Eagerly, store.appId.value?.takeLast(HINT_LENGTH))

    override val text: TrackingSetupText = SIMKL_SETUP_TEXT

    override val phonePagePort: Int = PHONE_PAGE_PORT

    override suspend fun submit(value: String, stillWanted: () -> Boolean): PasteCheck = lock.withLock {
        val id = cleanSimklAppId(value) ?: return@withLock PasteCheck.Refused
        if (!stillWanted()) return@withLock PasteCheck.Closed
        val check = checkSimklAppId(client, id)
        if (check != PasteCheck.Saved) return@withLock check
        if (!stillWanted()) return@withLock PasteCheck.Closed
        withContext(NonCancellable) {
            // Any change, a first id included: an account stored without this id (an older build's, or a sign-in that
            // landed during a removal) belongs to another app's grant.
            if (store.get() != id) signOutEveryone()
            store.save(id)
        }
        PasteCheck.Saved
    }

    override suspend fun remove() = lock.withLock {
        withContext(NonCancellable) {
            signOutEveryone()
            store.save(null)
        }
    }

    private suspend fun signOutEveryone() {
        profileIds().forEach { profileId -> signOut(profileId) }
    }

    private companion object {
        const val HINT_LENGTH = 4
        const val PHONE_PAGE_PORT = 8160
    }
}

private val SIMKL_APP_ID = Regex("[A-Za-z0-9_-]{8,128}")

private val SIMKL_SETUP_TEXT = TrackingSetupText(
    rowLine = R.string.simkl_setup_row_line,
    valueName = R.string.simkl_setup_value_name,
    steps = listOf(
        TrackingSetupStep(R.string.simkl_setup_step_create, listOf(R.string.simkl_setup_developer_page, R.string.simkl_setup_app_type)),
        TrackingSetupStep(R.string.simkl_setup_step_copy, listOf(R.string.simkl_setup_client_id)),
        TrackingSetupStep(R.string.simkl_setup_step_scan)
    ),
    phoneTitle = R.string.simkl_setup_phone_title,
    replaceTitle = R.string.simkl_setup_replace_title,
    savedBody = R.string.simkl_setup_saved_body,
    removeBody = R.string.simkl_setup_remove_body,
    phonePage = PasteKeyPageStrings(
        title = R.string.simkl_setup_value_name,
        intro = R.string.simkl_setup_web_intro,
        placeholder = R.string.simkl_setup_web_placeholder,
        hint = R.string.simkl_setup_web_hint,
        hintBold = R.string.simkl_setup_app_type,
        savedBody = R.string.simkl_setup_web_saved_body,
        rejectedTitle = R.string.simkl_setup_web_rejected_title,
        rejectedBody = R.string.simkl_setup_web_rejected_body,
        unreachable = R.string.simkl_setup_web_unreachable,
        maxLength = 128
    )
)
