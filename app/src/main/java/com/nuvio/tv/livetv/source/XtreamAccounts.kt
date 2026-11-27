package com.nuvio.tv.livetv.source

import android.content.Context
import com.nuvio.tv.livetv.data.JsonFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An Xtream login (Spec 109 S1): [server] is the normalised base URL ([XtreamAdapter.serverBase],
 * no credentials), the same string as its [LiveSource.url]. [toString] hides the username and
 * password, so a login printed by mistake leaks neither (KT-SEC-02).
 */
@Serializable
data class XtreamLogin(val server: String, val username: String, val password: String) {
    override fun toString(): String = "XtreamLogin(server=$server)"
}

@Serializable
internal data class XtreamAccountList(val version: Int = 1, val logins: Map<String, XtreamLogin> = emptyMap())

/**
 * The Xtream logins by source id (`filesDir/livetv/accounts.json`, Spec 109 Q3): plain app-private
 * JSON, kept out of backups (`BackupCatalog.excluded`; the app sets `allowBackup="false"`), apart
 * from `sources.json` so a source record never carries a credential. Every mutation writes first
 * and keeps the new list after, as [SourceStore]. Blocking file work runs on [io].
 */
@Singleton
class XtreamAccountStore internal constructor(dir: File, private val io: CoroutineDispatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val file = JsonFile(File(dir, "accounts.json"), XtreamAccountList.serializer())
    private val mutex = Mutex()
    private var logins: Map<String, XtreamLogin>? = null

    suspend fun get(id: String): XtreamLogin? = withContext(io) { mutex.withLock { loaded()[id] } }

    /** Every stored login (the report scrub's known values, Spec 116 D6). */
    suspend fun all(): List<XtreamLogin> = withContext(io) { mutex.withLock { loaded().values.toList() } }

    /**
     * Drops the logins of every id not in [ids] (a record removed by a restore, or an add whose
     * rollback could not drop its login); writes only when one goes. Throws IOException when the
     * write fails.
     */
    suspend fun retainOnly(ids: Set<String>) = withContext(io) {
        mutex.withLock {
            val current = loaded()
            val kept = current.filterKeys { it in ids }
            if (kept.size != current.size) write(kept)
        }
    }

    /** Stores [login] for source [id]; throws IOException when the write fails. */
    suspend fun put(id: String, login: XtreamLogin) = withContext(io) {
        mutex.withLock { write(loaded() + (id to login)) }
    }

    /** Drops source [id]'s login; throws IOException when the write fails. */
    suspend fun remove(id: String) = withContext(io) {
        mutex.withLock {
            val current = loaded()
            if (id in current) write(current - id)
        }
    }

    private fun write(updated: Map<String, XtreamLogin>) {
        file.write(XtreamAccountList(logins = updated))
        logins = updated
    }

    private fun loaded(): Map<String, XtreamLogin> =
        logins ?: (file.read()?.logins.orEmpty()).also { logins = it }
}
