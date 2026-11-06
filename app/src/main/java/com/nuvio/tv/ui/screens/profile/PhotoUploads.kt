package com.nuvio.tv.ui.screens.profile

import android.content.Context
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.PendingPhoto
import com.nuvio.tv.core.server.PhotoUploadServer
import com.nuvio.tv.core.server.PhotoUploadWebPage
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoAddResult
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.data.local.MyPhotosStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How OK on Add from phone went. */
internal enum class PhotoUploadOpen { Opened, Full, NoNetwork, NoPort }

/** What the confirm's OK did: [message] on the QR card's status line, or a toast once the card has closed (not Added). */
internal enum class PhotoUploadNotice(@StringRes val message: Int) {
    Added(R.string.photo_upload_added),
    Full(R.string.profile_my_photos_full),
    Failed(R.string.photo_upload_failed)
}

/**
 * The QR card (open while [url] is set: the upload page is running) and the photo waiting for
 * the TV's OK ([pending]), which outlives the card's 10-minute lifetime.
 */
internal data class PhotoUploadState(
    val url: String? = null,
    val pending: PendingPhoto? = null,
    val notice: PhotoUploadNotice? = null
)

internal sealed interface PhotoUploadStart {
    class Started(val server: PhotoUploadServer, val url: String) : PhotoUploadStart
    data object NoNetwork : PhotoUploadStart
    data object NoPort : PhotoUploadStart
}

/** Starts an upload session on this TV's LAN address ([PhotoUploadServer], the phone page). */
class PhotoUploadStarter @Inject constructor(@ApplicationContext private val context: Context) {
    internal fun start(
        isGalleryFull: () -> Boolean,
        onPending: (PhotoUploadServer, PendingPhoto) -> Unit
    ): PhotoUploadStart {
        val ip = DeviceIpAddress.get(context) ?: return PhotoUploadStart.NoNetwork
        val server = PhotoUploadServer.startOnAvailablePort(
            pageHtml = { PhotoUploadWebPage.html(context) },
            isGalleryFull = isGalleryFull,
            onPending = onPending
        ) ?: return PhotoUploadStart.NoPort
        return PhotoUploadStart.Started(server, server.url(ip))
    }
}

/**
 * Add from phone (Spec 44 D4, Design "Upload server"): one session at a time. [open] starts a new
 * session (a new server: fresh token, own pending slot); [close] (Back, editor exit, ON_STOP)
 * stops it and drops the pending photo; the [lifetime] (m1) stops it and closes the card but keeps
 * a pending photo already shown. A photo from any session but the current one is dropped (m2).
 * [confirm] / [discard] act only on the pending id they name, so a stale OK cannot add a newer
 * photo; only [confirm] writes, through [addPhoto]. The server's handler threads call in, so the
 * session and state changes share one lock.
 */
internal class PhotoUploadController(
    private val scope: CoroutineScope,
    private val start: (isGalleryFull: () -> Boolean, onPending: (PhotoUploadServer, PendingPhoto) -> Unit) -> PhotoUploadStart,
    private val photos: StateFlow<List<MyPhoto>?>,
    private val addPhoto: suspend (MyPhotoKind, ByteArray) -> MyPhotoAddResult,
    private val lifetime: Duration = LIFETIME
) {
    // Guards server, generation and state changes: onPending arrives on the server's handler threads.
    private val lock = Any()
    private var server: PhotoUploadServer? = null
    // Bumped by open() and close(), not by the lifetime: a confirm's outcome that lands after the user
    // closed or reopened the card is dropped instead of showing on a later card.
    private var generation = 0
    // Main thread only (open, close and the lifetime coroutine on the caller's scope).
    private var lifetimeJob: Job? = null
    private val _state = MutableStateFlow(PhotoUploadState())
    val state: StateFlow<PhotoUploadState> = _state.asStateFlow()

    /** D5: at the cap (or before the gallery has loaded) nothing more may come in. */
    private fun isGalleryFull(): Boolean = (photos.value?.size ?: MyPhotosStore.CAP) >= MyPhotosStore.CAP

    fun open(): PhotoUploadOpen {
        if (isGalleryFull()) return PhotoUploadOpen.Full
        close()
        return when (val started = start(::isGalleryFull, ::onPending)) {
            PhotoUploadStart.NoNetwork -> PhotoUploadOpen.NoNetwork
            PhotoUploadStart.NoPort -> PhotoUploadOpen.NoPort
            is PhotoUploadStart.Started -> {
                synchronized(lock) {
                    server = started.server
                    generation++
                    _state.value = PhotoUploadState(url = started.url)
                }
                lifetimeJob = scope.launch {
                    delay(lifetime)
                    expire(started.server)
                }
                PhotoUploadOpen.Opened
            }
        }
    }

    /** Stops the page and drops any pending photo; safe to call when nothing is open. */
    fun close() {
        lifetimeJob?.cancel()
        lifetimeJob = null
        val stopping = synchronized(lock) {
            val current = server
            server = null
            generation++
            _state.value = PhotoUploadState()
            current
        }
        stopping?.stop()
    }

    private fun expire(expiring: PhotoUploadServer) {
        synchronized(lock) {
            if (server !== expiring) return
            server = null
            // A status line the card showed does not become a toast now that the card has gone.
            _state.update { it.copy(url = null, notice = null) }
        }
        expiring.stop()
    }

    private fun onPending(from: PhotoUploadServer, photo: PendingPhoto) {
        synchronized(lock) {
            if (server !== from) return
            _state.update { it.copy(pending = photo, notice = null) }
        }
    }

    /** OK on the confirm for pending [id]: the photo goes into My photos; then the phone may send the next. */
    fun confirm(id: String) {
        val photo: PendingPhoto
        val from: PhotoUploadServer?
        val confirmedIn: Int
        synchronized(lock) {
            photo = _state.value.pending?.takeIf { it.id == id } ?: return
            from = server
            confirmedIn = generation
            _state.update { it.copy(pending = null) }
        }
        scope.launch {
            val notice = when (addPhoto(photo.kind, photo.jpeg)) {
                is MyPhotoAddResult.Added -> PhotoUploadNotice.Added
                MyPhotoAddResult.Full -> PhotoUploadNotice.Full
                MyPhotoAddResult.Failed -> PhotoUploadNotice.Failed
            }
            // Released only after the write: until then a second photo cannot overtake this one.
            from?.release(photo.id)
            synchronized(lock) {
                if (generation == confirmedIn) _state.update { it.copy(notice = notice) }
            }
        }
    }

    /** Discard (or Back) on the confirm for pending [id]: nothing is written. */
    fun discard(id: String) {
        val from: PhotoUploadServer?
        synchronized(lock) {
            if (_state.value.pending?.id != id) return
            from = server
            _state.update { it.copy(pending = null) }
        }
        from?.release(id)
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }

    companion object {
        /** m1: an absolute lifetime, not idle-based (probes cannot keep the page open). */
        val LIFETIME: Duration = 10.minutes
    }
}
