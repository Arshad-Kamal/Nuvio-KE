package com.nuvio.tv.core.report

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spec 116 D3.6: runs `logcat -d` for this app's own lines (an app reads only its own UID) and returns
 * the raw dump; the 24 h cut is [LogcatParser]'s, the scrub [ReportScrub]'s. Blocking: run off the
 * main thread. Empty on any failure or timeout: a report never fails for want of its log.
 */
object LogcatReader {
    private const val TAG = "ReportLogcat"
    private const val TIMEOUT_S = 10L
    private val COMMAND = listOf("logcat", "-d", "-v", "threadtime", "-t", "3000", "-b", "main,system,crash")

    fun read(): String {
        val process = try {
            ProcessBuilder(COMMAND).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.w(TAG, "logcat start failed: ${e.javaClass.simpleName}")
            return ""
        }
        // `-d` exits after the dump, so the read ends at EOF. The timeout is on the reader thread:
        // Process.waitFor(timeout) is API 26 and minSdk is 24.
        var text: String? = null
        val reader = thread(name = "report-logcat") {
            text = try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                null
            }
        }
        val finished = try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
            }
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S))
            // isAlive() returning false orders the reader's write of `text` before the read below.
            !reader.isAlive
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "logcat read interrupted")
            null
        } finally {
            process.destroy()
        }
        if (finished == false) Log.w(TAG, "logcat timed out")
        return if (finished == true) text.orEmpty() else ""
    }
}
