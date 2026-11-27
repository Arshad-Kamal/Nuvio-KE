package com.nuvio.tv.core.backup

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.withAppLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Starts a phone-page session on this TV's LAN address (Spec 101 S6): the Save page lists this install's
 * manual backups (D46), the Restore page receives into `backup_restore/phone/`. [session] makes the
 * controller the backup screen (S7) holds.
 */
class BackupPhoneStarter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manualBackups: ManualBackups,
) {
    fun session(scope: CoroutineScope): BackupPhoneSession = BackupPhoneSession(scope, ::start)

    private fun start(mode: BackupPhoneMode, onPending: (BackupPhoneServer, PendingUpload) -> Unit): BackupPhoneStart {
        val ip = DeviceIpAddress.get(context) ?: return BackupPhoneStart.NoNetwork
        val server = BackupPhoneServer.startOnAvailablePort(
            mode = mode,
            pageHtml = { BackupPhoneWebPage.html(context, mode, Build.MODEL) },
            backups = ::items,
            openBackup = ::open,
            uploadDir = RestoreDirs(context.filesDir).phoneUploads,
            installedVersionCode = BuildConfig.VERSION_CODE,
            onPending = onPending,
        ) ?: return BackupPhoneStart.NoPort
        return BackupPhoneStart.Started(server, server.url(ip))
    }

    // Runs on the server's handler threads, never the main thread.
    private fun items(): List<PhoneBackupItem> = runBlocking {
        val localized = context.withAppLocale()
        val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, localized.resources.configuration.locales[0])
        manualBackups.list().map { backup ->
            val header = manualBackups.open(backup)?.use(BackupCodec::readHeader)
            val parts = listOfNotNull(
                localized.getString(R.string.web_backup_item_manual),
                header?.let { localized.resources.getQuantityString(R.plurals.web_backup_item_profiles, it.profiles.size, it.profiles.size) },
                Formatter.formatShortFileSize(localized, backup.sizeBytes),
            )
            PhoneBackupItem(
                id = backup.order.toString(),
                title = header?.let { dates.format(Date(it.createdAtMs)) } ?: backup.name,
                detail = parts.joinToString(" · "),
            )
        }
    }

    private fun open(id: String): PhoneBackupFile? = runBlocking {
        val backup = manualBackups.list().firstOrNull { it.order.toString() == id } ?: return@runBlocking null
        manualBackups.open(backup)?.let { PhoneBackupFile(backup.name, it) }
    }
}
