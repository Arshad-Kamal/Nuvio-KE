package com.nuvio.tv.core.report

import android.content.Context
import java.io.File

/** Spec 116 D4/D4b: where this TV keeps records for a report, and for how long. */
object ReportStorage {
    /** Crash files, playback errors and exit records older than this never reach a report. */
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** `filesDir/report`: app-private; `allowBackup="false"` keeps it off backups. */
    fun dir(context: Context): File = File(context.filesDir, "report")
}
