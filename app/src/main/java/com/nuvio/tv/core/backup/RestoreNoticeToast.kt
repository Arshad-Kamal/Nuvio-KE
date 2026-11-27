package com.nuvio.tv.core.backup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.server.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Spec 101 D47 (Arshad, escalation 1f1f2d0d): the restore's result as a long system toast at the first
 * start after it, posted from NuvioApplication once the startup applier has run. A system toast because
 * the app's own toast lives in screens another spec is changing; it needs no screen.
 */
object RestoreNoticeToast {

    fun showIfAny(app: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val notice = takeRestoreNotice(app.filesDir) ?: return@launch
            val text = notice.message(app.withAppLocale())
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        }
    }

    internal fun RestoreNotice.message(context: Context): String = when (this) {
        is RestoreNotice.Done -> buildString {
            append(context.getString(R.string.restore_notice_done))
            if (skippedProfiles.isNotEmpty()) {
                append(' ').append(context.getString(R.string.restore_notice_skipped, skippedProfiles.joinToString(", ")))
            }
            if (photosSkipped > 0) {
                append(' ').append(context.resources.getQuantityString(R.plurals.restore_notice_photos_skipped, photosSkipped, photosSkipped))
            }
        }
        RestoreNotice.StoppedPartWay -> context.getString(R.string.restore_notice_stopped)
        RestoreNotice.Discarded -> context.getString(R.string.restore_notice_discarded)
        RestoreNotice.AccountChanged -> context.getString(R.string.restore_notice_account)
    }
}
