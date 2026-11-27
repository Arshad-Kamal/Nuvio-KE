package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.report.LastReport
import com.nuvio.tv.core.report.ReportBuilder
import com.nuvio.tv.core.report.ReportFile
import com.nuvio.tv.core.report.ReportUploader
import com.nuvio.tv.core.report.SentReport
import com.nuvio.tv.core.report.UploadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Spec 116 B1-E1: where the Send a report panel is. */
sealed interface ReportState {
    data object Closed : ReportState

    /** The panel is open and the report is being put together. */
    data object Building : ReportState

    /** The report could not be put together; only Cancel. */
    data object BuildFailed : ReportState

    /** [file] built; [failure] is the last send's result when it failed (E1), null before any send. */
    data class Ready(val file: ReportFile, val failure: UploadResult? = null) : ReportState {
        /** Send or Try again: never after a refusal, which the same bytes would get again. */
        val canSend: Boolean get() = failure != UploadResult.Refused
    }

    /** C1: [sent] of [file]'s bytes handed to the socket. [attempt] tells one send's progress from a later one's. */
    data class Sending(val file: ReportFile, val sent: Long, val attempt: Int) : ReportState

    /** D1: the code screen. */
    data class Sent(val code: String) : ReportState
}

/** One upload of a report's bytes; progress may arrive on any thread. */
fun interface ReportSender {
    suspend fun send(bytes: ByteArray, onProgress: (sent: Long, total: Long) -> Unit): UploadResult
}

/**
 * Spec 116 P5: the report panel's state. [open] builds the report once (D2), and Send and Try
 * again upload exactly those bytes; [close] (Cancel, Back, Done, leaving Settings) stops a build or
 * an upload and drops the report: nothing about it stays on the TV but [LastReport]'s code (D8).
 * Each open gets its own sender ([newSender]: one uploader per panel).
 */
@HiltViewModel
class ReportViewModel internal constructor(
    private val build: suspend () -> ReportFile,
    private val newSender: () -> ReportSender,
    private val clock: () -> Long,
    private val lastReport: (SentReport) -> Unit,
) : ViewModel() {

    @Inject constructor(@ApplicationContext context: Context, builder: ReportBuilder) : this(
        build = builder::build,
        newSender = {
            val uploader = ReportUploader(BuildConfig.REPORT_URL, isOnline = { hasValidatedNetwork(context) })
            ReportSender { bytes, onProgress -> uploader.upload(bytes, onProgress) }
        },
        clock = System::currentTimeMillis,
        lastReport = LastReport::record,
    )

    private val _state = MutableStateFlow<ReportState>(ReportState.Closed)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private var job: Job? = null
    private var sender: ReportSender? = null
    private var attempts = 0

    fun open() {
        if (_state.value != ReportState.Closed) return
        _state.value = ReportState.Building
        sender = newSender()
        job = viewModelScope.launch {
            val next = try {
                ReportState.Ready(build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The message may quote a stored value; log the type only.
                Log.w(TAG, "report build failed: ${e.javaClass.simpleName}")
                ReportState.BuildFailed
            }
            if (_state.value == ReportState.Building) _state.value = next
        }
    }

    /** Send (B1) or Try again (E1): the same bytes. */
    fun send() {
        val ready = _state.value as? ReportState.Ready ?: return
        if (!ready.canSend) return
        val send = sender ?: return
        val attempt = ++attempts
        val file = ready.file
        _state.value = ReportState.Sending(file, sent = 0L, attempt = attempt)
        job = viewModelScope.launch {
            val result = send.send(file.bytes) { sent, _ ->
                _state.update { if (it is ReportState.Sending && it.attempt == attempt) it.copy(sent = sent) else it }
            }
            val current = _state.value
            if (current !is ReportState.Sending || current.attempt != attempt) return@launch
            _state.value = when (result) {
                is UploadResult.Sent -> {
                    lastReport(SentReport(result.code, clock()))
                    ReportState.Sent(result.code)
                }
                else -> ReportState.Ready(file, failure = result)
            }
        }
    }

    /** C1 Cancel: stops the upload; the panel stays with the same report. */
    fun cancelSend() {
        val sending = _state.value as? ReportState.Sending ?: return
        job?.cancel()
        _state.value = ReportState.Ready(sending.file)
    }

    fun close() {
        job?.cancel()
        job = null
        sender = null
        _state.value = ReportState.Closed
    }

    override fun onCleared() {
        close()
    }

    private companion object {
        const val TAG = "ReportPanel"
    }
}

/** D2: a build without a report address (`REPORT_URL` empty, every build from the public source) has no Help row. */
internal fun reportRowVisible(reportUrl: String = BuildConfig.REPORT_URL): Boolean = reportUrl.isNotEmpty()

/** The active network has passed Android's internet check; false when it cannot be read. */
private fun hasValidatedNetwork(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)
}
