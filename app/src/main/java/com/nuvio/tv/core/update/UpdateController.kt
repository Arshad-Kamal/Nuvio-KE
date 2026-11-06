package com.nuvio.tv.core.update

import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where and for what the check looks (Spec 52 P6); [supported] false = no check, no Updates UI. */
data class UpdateFeed(
    val supported: Boolean,
    val owner: String,
    val repo: String,
    val installedCode: Int,
    val supportedAbis: List<String>
) {
    companion object {
        fun fromBuild(): UpdateFeed = UpdateFeed(
            supported = updateCheckSupported(
                builtInTmdbKey = BuildConfig.TMDB_API_KEY,
                debugBuild = BuildConfig.IS_DEBUG_BUILD,
                applicationId = BuildConfig.APPLICATION_ID
            ),
            owner = BuildConfig.UPDATE_OWNER,
            repo = BuildConfig.UPDATE_REPO,
            installedCode = BuildConfig.VERSION_CODE,
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )
    }
}

/** Download and install (P8) as the update panel shows it. */
sealed interface DownloadState {
    data object Idle : DownloadState

    /**
     * From the press to the installer handoff: the fresh check first ([totalBytes] 0), then the
     * download ([bytes] moves once per whole percent).
     */
    data class Running(val bytes: Long = 0L, val totalBytes: Long = 0L) : DownloadState

    /** "Download failed. Try again." The fresh check or the download failed, or no installer answered. */
    data object Failed : DownloadState
}

/**
 * What About, the rail dot and the top-bar pill show (S8). [lastCheckFailed] = the latest check
 * this run, automatic or not, got no usable answer (no popup either way); [available] = the known
 * release while it is newer than this build, so installing it clears it.
 */
data class UpdateState(
    val supported: Boolean = false,
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val lastCheckFailed: Boolean = false,
    val lastOkCheckMs: Long = 0L,
    val available: KnownRelease? = null,
    val download: DownloadState = DownloadState.Idle
)

/**
 * The NEW badge, About's rail dot and the top-bar pill (Spec 52 D2): a newer release is known and
 * Check automatically is on; turning the toggle off hides all three, the About row stays.
 */
val UpdateState.noticeShown: Boolean
    get() = supported && autoCheck && available != null

/**
 * The update check (Spec 52 P6/P7): the public repo's latest release, at most once a day at app
 * start while Check automatically is on, or on demand. One check at a time; a second request
 * joins the running one. Runs on its own scope, so leaving About never cuts a check short.
 *
 * The controller holds the record in memory: it reads the store once and keeps what each write
 * returns, under one lock, so [state] changes in the order the writes land and no DataStore flow
 * is collected on this scope (a read error reads as defaults, never a crash).
 *
 * Download and install (P8) is one action from the press to the installer handoff, one at a time;
 * it runs on the same scope, so only [cancelDownload] (Cancel, leaving the panel) stops it.
 */
@Singleton
class UpdateController internal constructor(
    private val api: GitHubReleaseApi,
    private val store: UpdateStore,
    private val feed: UpdateFeed,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val downloader: ApkDownloader,
    private val install: (File) -> Boolean
) {
    @Inject constructor(
        api: GitHubReleaseApi,
        store: UpdateStore,
        downloader: ApkDownloader,
        installer: ApkInstaller
    ) : this(
        api, store, UpdateFeed.fromBuild(), CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis,
        downloader, installer::launch
    )

    private data class Snapshot(
        val loaded: Boolean = false,
        val record: UpdateRecord = UpdateRecord(),
        val checking: Boolean = false,
        val failed: Boolean = false,
        val download: DownloadState = DownloadState.Idle
    )

    private val snapshot = MutableStateFlow(Snapshot())
    private val writes = Mutex()
    private val lock = Any()
    private var running: Deferred<CheckOutcome>? = null

    /** Held by each download, so a press right after a cancel waits for the cancelled action's deletes. */
    private val files = Mutex()
    private var downloadJob: Job? = null

    /** App start: a file a past run left (handed to the installer, or cut by a kill) goes; downloads wait for it. */
    private val cleanup: Job? = if (feed.supported) scope.launch { downloader.clear() } else null

    val state: StateFlow<UpdateState> =
        if (!feed.supported) {
            MutableStateFlow(UpdateState())
        } else {
            snapshot.map(::toState).stateIn(scope, SharingStarted.Eagerly, toState(Snapshot()))
        }

    /** [UpdateState.noticeShown] alone (the top-bar pill, About's rail dot): progress never reaches them. */
    val notice: StateFlow<Boolean> = state.map { it.noticeShown }.stateIn(scope, SharingStarted.Eagerly, state.value.noticeShown)

    init {
        if (feed.supported) {
            scope.launch {
                val record = store.read()
                snapshot.update { it.copy(loaded = true, record = record) }
            }
        }
    }

    /** About's Check for updates. */
    fun checkNow() {
        if (feed.supported) scope.launch { awaitCheck() }
    }

    /**
     * App start (MainActivity): checks when Check automatically is on and the last attempt is a
     * day old. Returns the launched job (tests join it), null on a build without the check.
     */
    fun autoCheckIfDue(): Job? {
        if (!feed.supported) return null
        return scope.launch {
            val record = loadedRecord()
            if (record.autoCheck && autoCheckDue(nowMs(), record.lastAttemptMs)) awaitCheck()
        }
    }

    /** False when the store could not be written (the toggle keeps its value). */
    suspend fun setAutoCheck(enabled: Boolean): Boolean {
        if (!feed.supported) return false
        loadedRecord()
        return try {
            write { setAutoCheck(enabled) }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Runs a check, or joins the one running; null on a build without the check. A caller that
     * needs this answer's release (S7: a fresh URL and digest before a download) reads it from the
     * returned [CheckOutcome.Answered], not from [state].
     */
    suspend fun awaitCheck(): CheckOutcome? {
        if (!feed.supported) return null
        val check = synchronized(lock) {
            running?.takeIf { it.isActive } ?: scope.async { check() }.also { running = it }
        }
        return check.await()
    }

    /**
     * Download and install: a fresh check first (P8: the current URL and digest; a pulled release
     * ends as up to date or failed, never a dead link), then the download, then the installer.
     * A press while one runs gets the running one. Returns the action's job (tests join it), null
     * on a build without the check.
     */
    fun downloadAndInstall(): Job? {
        if (!feed.supported) return null
        return synchronized(lock) {
            downloadJob?.takeIf { it.isActive }
                ?: scope.launch {
                    cleanup?.join()
                    files.withLock { runDownload() }
                }.also { downloadJob = it }
        }
    }

    /**
     * Cancel, or the update panel closing (Not now, Back, leaving About, and the activity's ON_STOP:
     * from the background Android drops the installer launch without an error): stops the action,
     * and what it wrote is deleted. A failed state clears too (also one a Try again left before it
     * started running), so the panel opens fresh next time. After the installer handoff the action
     * has ended: nothing is cancelled and the handed-over file stays.
     */
    fun cancelDownload() {
        synchronized(lock) { downloadJob }?.cancel()
        snapshot.update { if (it.download == DownloadState.Failed) it.copy(download = DownloadState.Idle) else it }
    }

    private suspend fun runDownload() {
        var end: DownloadState = DownloadState.Failed
        snapshot.update { it.copy(download = DownloadState.Running()) }
        try {
            end = downloadAndHandOver()
        } catch (e: CancellationException) {
            // The rename and the archive check do not stop for a cancel, so a verified file can be left.
            downloader.clear()
            end = DownloadState.Idle
            throw e
        } catch (e: Exception) {
            // Nothing may escape to the scope (no handler: an app crash), e.g. FileProvider's IllegalArgumentException.
            Log.w(UPDATE_DOWNLOAD_TAG, "download action failed: ${e.javaClass.simpleName}")
        } finally {
            snapshot.update { it.copy(download = end) }
        }
    }

    private suspend fun downloadAndHandOver(): DownloadState {
        val offer = when (val outcome = awaitCheck()) {
            is CheckOutcome.Answered -> outcome.offer?.takeIf { it.isNewer() } ?: return DownloadState.Idle
            CheckOutcome.NoRelease -> return DownloadState.Idle
            CheckOutcome.Failed, CheckOutcome.Unreached, null -> return DownloadState.Failed
        }
        val total = offer.sizeBytes
        snapshot.update { it.copy(download = DownloadState.Running(0L, total)) }
        // Published once per whole percent: a read is a few KiB, so per read would be thousands of states.
        var shownPercent = -1L
        val result = downloader.download(offer) { bytes ->
            val percent = bytes * 100 / total
            if (percent != shownPercent) {
                shownPercent = percent
                snapshot.update { it.copy(download = DownloadState.Running(bytes, total)) }
            }
        }
        return when (result) {
            is DownloadResult.Verified -> if (install(result.apk)) DownloadState.Idle else DownloadState.Failed
            DownloadResult.Rejected -> {
                forget(offer)
                DownloadState.Failed
            }
            DownloadResult.Failed -> DownloadState.Failed
        }
    }

    /** P7: an offer whose file can never install is dropped until a check learns it again. */
    private suspend fun forget(release: KnownRelease) {
        try {
            write { forgetRelease(release) }
        } catch (e: IOException) {
            Log.w(UPDATE_DOWNLOAD_TAG, "release not dropped: ${e.javaClass.simpleName}")
        }
    }

    private fun KnownRelease.isNewer(): Boolean = versionCode > feed.installedCode

    private suspend fun check(): CheckOutcome {
        loadedRecord()
        snapshot.update { it.copy(checking = true) }
        var outcome: CheckOutcome = CheckOutcome.Unreached
        try {
            outcome = fetchAndRecord()
        } finally {
            snapshot.update { it.copy(checking = false, failed = outcome !is CheckOutcome.Answered) }
        }
        Log.i(TAG, "check: ${outcome.javaClass.simpleName}")
        return outcome
    }

    /** The answer, recorded; a write that fails makes it [CheckOutcome.Unreached] (nothing kept). */
    private suspend fun fetchAndRecord(): CheckOutcome {
        val outcome = fetch()
        if (outcome == CheckOutcome.Unreached) return outcome
        return try {
            write { record(outcome, nowMs()) }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check not recorded: ${e.javaClass.simpleName}")
            CheckOutcome.Unreached
        }
    }

    private suspend fun fetch(): CheckOutcome = try {
        val response = api.latestRelease(feed.owner, feed.repo)
        checkOutcome(response.code(), response.body(), feed.supportedAbis)
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        CheckOutcome.Unreached
    } catch (e: Exception) {
        // An answer that did not decode (SerializationException) or a converter failure.
        Log.w(TAG, "check answer unusable: ${e.javaClass.simpleName}")
        CheckOutcome.Failed
    }

    private suspend fun loadedRecord(): UpdateRecord = snapshot.first { it.loaded }.record

    /** One store write and its record into memory, in the order the writes land. */
    private suspend fun write(block: suspend UpdateStore.() -> UpdateRecord) {
        writes.withLock {
            val record = store.block()
            snapshot.update { it.copy(record = record) }
        }
    }

    private fun toState(snapshot: Snapshot) = UpdateState(
        supported = true,
        autoCheck = snapshot.record.autoCheck,
        checking = snapshot.checking,
        lastCheckFailed = snapshot.failed,
        lastOkCheckMs = snapshot.record.lastOkCheckMs,
        available = snapshot.record.knownRelease?.takeIf { it.isNewer() },
        download = snapshot.download
    )

    private companion object {
        const val TAG = "UpdateCheck"
    }
}
