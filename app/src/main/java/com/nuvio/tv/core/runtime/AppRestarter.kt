package com.nuvio.tv.core.runtime

import android.content.Context
import android.content.Intent
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class AppRestarter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * Ends this process and opens the app again in a fresh one; false (nothing done) when the app has no
     * launch intent. Call on the main thread while the app is on screen.
     *
     * The launch is started now, while the app still has a visible window, so Android 10+ allows it; the
     * system then starts a new process for it once this one is gone. An alarm-sent PendingIntent fired after
     * the process died is a blocked background start on Android 10+ (Spec 101 F5: the Shield sat on the
     * launcher). Called on the main thread, the new activity cannot attach to this process before it exits:
     * its start waits for this activity to pause, which needs this main thread, so nothing may run between
     * startActivity and killProcess.
     */
    fun restart(): Boolean {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
            ?: return false
        context.startActivity(Intent.makeRestartActivityTask(component))
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
