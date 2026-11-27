package com.nuvio.tv.core.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The last report this process sent: its code and when (epoch ms). */
data class SentReport(val code: String, val sentAtMs: Long)

/**
 * Spec 116 D8: the Help row reads "Last report KE-7F3QM2 · sent 21:46" until the app process ends.
 * Memory only: nothing about a report is stored on the TV.
 */
object LastReport {
    private val _sent = MutableStateFlow<SentReport?>(null)
    val sent: StateFlow<SentReport?> = _sent.asStateFlow()

    fun record(report: SentReport) {
        _sent.value = report
    }
}
