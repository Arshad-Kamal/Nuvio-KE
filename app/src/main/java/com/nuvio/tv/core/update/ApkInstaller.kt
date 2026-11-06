package com.nuvio.tv.core.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Hands a verified APK to Android's installer (Spec 52 P8): ACTION_VIEW through the app's
 * FileProvider with a per-intent read grant. The installer owns the install-unknown-apps prompt
 * (REQUEST_INSTALL_PACKAGES in the manifest) and the signature check: the same package installs
 * over this one only when signed with the same key.
 */
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    /** False when no installer answers the intent. */
    fun launch(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
