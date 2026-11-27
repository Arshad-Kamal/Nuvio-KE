package com.nuvio.tv.core.backup

import com.nuvio.tv.core.server.declaredBodyLength
import com.nuvio.tv.core.server.drainDeclaredBody
import com.nuvio.tv.core.server.INLINE_PAGE_CSP
import com.nuvio.tv.core.server.newSessionToken
import com.nuvio.tv.core.server.notFoundResponse
import com.nuvio.tv.core.server.pathAfterToken
import com.nuvio.tv.core.server.rejectUntrustedRequest
import com.nuvio.tv.core.server.startOnFirstAvailablePort
import com.nuvio.tv.core.server.UnprocessableEntity
import com.nuvio.tv.core.server.withTokenPathHeaders
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Which phone page a session serves (mockup b5, b6). */
enum class BackupPhoneMode { SAVE, RESTORE }

/** One manual backup on the Save page (D46: manual backups only); [title] and [detail] are the TV's text. */
data class PhoneBackupItem(val id: String, val title: String, val detail: String)

/** A manual backup opened for download; [stream] is closed by the server once sent. */
class PhoneBackupFile(val fileName: String, val stream: InputStream)

/** A checked backup from the phone, waiting for the TV's restore screen (b8). [file] is app-private. */
class PendingUpload(val id: String, val file: File, val header: BackupHeader)

/**
 * The LAN page behind "Save a backup to your phone" and "Restore from your phone" (Spec 101 S6, D1, D8,
 * D44). One instance is one session: its own 128-bit token (every route under `/r/<token>/`), one [mode],
 * and in RESTORE one pending slot, so at most one upload waits for the TV. Same guards as the other phone
 * pages (core/server: Host allow-list, same-origin POST, token in the path, no-store, no Referer).
 *
 * Routes: `GET /r/<t>/` the page ([pageHtml]); SAVE only, `GET /r/<t>/list` the manual backups ([backups],
 * JSON) and `GET /r/<t>/f/<id>` one of them as a download (an id from the current list, never a path);
 * RESTORE only, `POST /r/<t>/backup` a raw body of at most
 * [BackupLimits.PHONE_COMPRESSED_MAX_BYTES], streamed to a file in [uploadDir] (never held in memory), then
 * checked like any backup (BackupCodec.read) before [onPending]. POST order: guard, token, mode, restore
 * switch (R10), claim the slot (409 [BUSY]), Content-Length (411 / 413 [TOO_LARGE]), receive, check
 * (422 with the reason), publish. Answer bodies are fixed words; the page maps them to its own text.
 */
class BackupPhoneServer internal constructor(
    private val token: String,
    val mode: BackupPhoneMode,
    private val pageHtml: () -> String,
    private val backups: () -> List<PhoneBackupItem>,
    /** Opens the manual backup a list row named by its id; null when it is gone. Never builds the list rows. */
    private val openBackup: (String) -> PhoneBackupFile?,
    private val uploadDir: File,
    private val installedVersionCode: Int,
    private val restoreEnabled: Boolean,
    private val onPending: (BackupPhoneServer, PendingUpload) -> Unit,
    hostname: String?,
    port: Int,
) : NanoHTTPD(hostname, port) {

    private sealed interface Slot {
        data object Free : Slot
        data object Receiving : Slot
        class Waiting(val upload: PendingUpload) : Slot
    }

    private val slot = AtomicReference<Slot>(Slot.Free)

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    /**
     * Frees the slot held by pending [id] (restored, or turned down on the TV) and deletes its file, so the
     * phone can send another (BackupPhoneSession.release). A restore that stages it closes the session
     * instead, after staging has read the file.
     */
    fun release(id: String) {
        val current = slot.get()
        if (current is Slot.Waiting && current.upload.id == id && slot.compareAndSet(current, Slot.Free)) {
            current.upload.file.delete()
        }
    }

    /** Stops serving the page; an upload already waiting stays until [release] or [stop] (the 10 minutes end mid-restore). */
    fun stopListening() {
        super.stop()
    }

    /** Stops the page and deletes anything received: a session leaves no file behind. */
    override fun stop() {
        super.stop()
        slot.set(Slot.Free)
        // This session's files only: a request still receiving finds the slot moved and deletes its own.
        sweepUploads(uploadDir, "$UPLOAD_PREFIX$token-")
    }

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            ?: return if (session.method == Method.POST) session.earlyAnswer(Response.Status.NOT_FOUND, NOT_FOUND) else notFoundResponse()
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.GET && path == "list" && mode == BackupPhoneMode.SAVE -> list()
            session.method == Method.GET && path.startsWith(FILE_PREFIX) && mode == BackupPhoneMode.SAVE ->
                download(path.removePrefix(FILE_PREFIX))
            session.method == Method.POST && path == "backup" && mode == BackupPhoneMode.RESTORE && restoreEnabled -> receive(session)
            session.method == Method.POST -> session.earlyAnswer(Response.Status.NOT_FOUND, NOT_FOUND)
            else -> notFoundResponse()
        }
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", INLINE_PAGE_CSP)
        }

    private fun list(): Response {
        val items = backups().map { item ->
            buildJsonObject {
                put("id", item.id)
                put("title", item.title)
                put("detail", item.detail)
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", JsonArray(items).toString())
    }

    private fun download(id: String): Response {
        val file = openBackup(id) ?: return notFoundResponse()
        // Chunked: the folder's recorded size may differ from the bytes the stream yields.
        return newChunkedResponse(Response.Status.OK, ManualBackups.MIME, file.stream).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${safeFileName(file.fileName)}\"")
        }
    }

    private fun receive(session: IHTTPSession): Response {
        if (!slot.compareAndSet(Slot.Free, Slot.Receiving)) return session.earlyAnswer(Response.Status.CONFLICT, BUSY)
        var published = false
        val id = UUID.randomUUID().toString()
        val file = File(uploadDir, "$UPLOAD_PREFIX$token-$id${ManualBackups.EXTENSION}")
        try {
            val length = session.declaredBodyLength() ?: return plain(Response.Status.LENGTH_REQUIRED, BAD_REQUEST)
            if (length > BackupLimits.PHONE_COMPRESSED_MAX_BYTES) {
                // Over the cap, left unread: the answer closes the connection (Connection: close).
                return plain(Response.Status.PAYLOAD_TOO_LARGE, TOO_LARGE)
            }
            if (!uploadDir.isDirectory && !uploadDir.mkdirs()) return plain(Response.Status.INTERNAL_ERROR, FAILED)
            if (!receiveTo(session, length, file)) return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
            val read = file.inputStream().use {
                BackupCodec.read(it, installedVersionCode, compressedLimit = BackupLimits.PHONE_COMPRESSED_MAX_BYTES)
            }
            if (read !is BackupReadResult.Ok) return plain(UnprocessableEntity, refusal(read))
            val upload = PendingUpload(id, file, read.header)
            // stop() may have freed the slot meanwhile: then this upload belongs to no session.
            if (!slot.compareAndSet(Slot.Receiving, Slot.Waiting(upload))) return plain(Response.Status.NOT_FOUND, NOT_FOUND)
            published = true
            onPending(this, upload)
            return plain(Response.Status.OK, SENT)
        } catch (_: IOException) {
            return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        } finally {
            if (!published) {
                file.delete()
                slot.compareAndSet(Slot.Receiving, Slot.Free)
            }
        }
    }

    /** Copies exactly [length] body bytes to [file]; false when the stream ends early. */
    private fun receiveTo(session: IHTTPSession, length: Long, file: File): Boolean {
        val input = session.inputStream
        val buffer = ByteArray(BUFFER_BYTES)
        var left = length
        FileOutputStream(file).use { out ->
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) return false
                out.write(buffer, 0, read)
                left -= read
            }
        }
        return true
    }

    /** An answer before the body is read (404, 409), after draining a body within the cap. */
    private fun IHTTPSession.earlyAnswer(status: Response.Status, body: String): Response {
        drainDeclaredBody(BackupLimits.PHONE_COMPRESSED_MAX_BYTES.toInt())
        return plain(status, body)
    }

    private fun plain(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        /** D44: 8180-8189 (8160-8169 Spec 106, 8170-8179 Spec 132). */
        const val DEFAULT_PORT = 8180
        const val PORT_ATTEMPTS = 10

        /** Answer bodies; the page maps each to its own text (b6). */
        const val SENT = "sent"
        const val BUSY = "busy"
        const val NOT_A_BACKUP = "not_a_backup"
        const val NEEDS_NEWER_APP = "needs_newer_app"
        const val TOO_OLD = "too_old"
        const val TOO_LARGE = "too_large"
        const val DAMAGED = "damaged"
        const val FAILED = "failed"
        private const val BAD_REQUEST = "bad request"
        private const val NOT_FOUND = "Not found"

        /** The reason a file was refused, as the page shows it (b6: not a backup, newer, over 20 MB). */
        internal fun refusal(read: BackupReadResult): String = when (read) {
            is BackupReadResult.NeedsNewerApp -> NEEDS_NEWER_APP
            is BackupReadResult.TooOld -> TOO_OLD
            is BackupReadResult.Invalid -> when (read.reason) {
                BackupReadResult.Reason.NOT_A_BACKUP -> NOT_A_BACKUP
                BackupReadResult.Reason.TOO_LARGE -> TOO_LARGE
                BackupReadResult.Reason.DAMAGED -> DAMAGED
            }
            is BackupReadResult.Ok -> SENT
        }

        private const val PATH_PREFIX = "/r/"
        private const val FILE_PREFIX = "f/"

        /** A header-safe file name: the folder's own names are plain, but nothing quoted or controlling gets through. */
        internal fun safeFileName(name: String): String =
            name.filter { it in ' '..'~' && it != '"' && it != '\\' && it != '/' }.ifBlank { "Nuvio KE backup" + ManualBackups.EXTENSION }
        private const val UPLOAD_PREFIX = "upload-"
        private const val BUFFER_BYTES = 64 * 1024

        private fun sweepUploads(dir: File, prefix: String) {
            dir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        }

        fun startOnAvailablePort(
            mode: BackupPhoneMode,
            pageHtml: () -> String,
            backups: () -> List<PhoneBackupItem>,
            openBackup: (String) -> PhoneBackupFile?,
            uploadDir: File,
            installedVersionCode: Int,
            onPending: (BackupPhoneServer, PendingUpload) -> Unit,
            restoreEnabled: Boolean = BackupFeatures.RESTORE_ENABLED,
            startPort: Int = DEFAULT_PORT,
        ): BackupPhoneServer? {
            // Uploads a killed process left behind: a new session starts with none.
            sweepUploads(uploadDir, UPLOAD_PREFIX)
            return startOnFirstAvailablePort(startPort, PORT_ATTEMPTS) { port ->
                BackupPhoneServer(newSessionToken(), mode, pageHtml, backups, openBackup, uploadDir, installedVersionCode, restoreEnabled, onPending, null, port)
            }
        }
    }
}
