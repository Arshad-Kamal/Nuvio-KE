package com.nuvio.tv.ui.screens.settings.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.backup.BackupCodec
import com.nuvio.tv.core.backup.BackupExportResult
import com.nuvio.tv.core.backup.BackupExporter
import com.nuvio.tv.core.backup.BackupHeader
import com.nuvio.tv.core.backup.BackupKind
import com.nuvio.tv.core.backup.BackupLimits
import com.nuvio.tv.core.backup.BackupPhoneMode
import com.nuvio.tv.core.backup.BackupPhoneOpen
import com.nuvio.tv.core.backup.BackupPhoneStarter
import com.nuvio.tv.core.backup.BackupProfile
import com.nuvio.tv.core.backup.BackupReadResult
import com.nuvio.tv.core.backup.BackupSummary
import com.nuvio.tv.core.backup.ManualBackups
import com.nuvio.tv.core.backup.PendingUpload
import com.nuvio.tv.core.backup.PreRestoreCopies
import com.nuvio.tv.core.backup.ProfileMatching
import com.nuvio.tv.core.backup.RestoreRequest
import com.nuvio.tv.core.backup.RestoreSource
import com.nuvio.tv.core.backup.RestoreStageResult
import com.nuvio.tv.core.backup.RestoreStager
import com.nuvio.tv.core.backup.SavedBackup
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.ProfileDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** A backup the restore screen reads: one of this install's (b7) or the phone's upload (b6). */
internal sealed interface RestoreFile {
    data class Manual(val backup: SavedBackup) : RestoreFile
    data class Copy(val file: File) : RestoreFile
    data class Phone(val upload: PendingUpload) : RestoreFile
}

/** The file's own name: a b7 row's title when its header does not read. */
internal val RestoreFile.fileName: String
    get() = when (this) {
        is RestoreFile.Manual -> backup.name
        is RestoreFile.Copy -> file.name
        is RestoreFile.Phone -> upload.file.name
    }

/** A b7 row; [header] is null for a file whose first line does not read (picking it refuses it). */
internal data class RestoreListItem(val file: RestoreFile, val header: BackupHeader?, val sizeBytes: Long)

/** Why a backup was not made (b13, and the failures no mockup draws). */
internal sealed interface BackupProblem {
    /** One part of [profileName]'s data (its [store]) is over a section's limits (D41); null when not one store. */
    data class TooLarge(val profileName: String?, val store: String?) : BackupProblem
    data object Failed : BackupProblem
}

/** Why a restore did not stage (b12, and the failures no mockup draws). */
internal sealed interface RestoreProblem {
    data class Newer(val versionName: String) : RestoreProblem
    data class Older(val versionName: String) : RestoreProblem
    data object Damaged : RestoreProblem
    data object TooLarge : RestoreProblem
    data object NoSpace : RestoreProblem
    data object CopyFailed : RestoreProblem
    data object Failed : RestoreProblem
}

/** What the Backup pane shows over itself (one panel at a time, mockup b2-b13). */
internal sealed interface BackupPanel {
    data object None : BackupPanel

    /** b2: what goes in; [summary] is null while it loads (or when it cannot be read). */
    data class Choose(val toPhone: Boolean, val summary: BackupSummary?, val secrets: Boolean = false, val working: Boolean = false) : BackupPanel

    /** b3: the first entry, or [confirming] the second; [mismatch] after a second entry that differed. */
    data class SetPin(val toPhone: Boolean, val summary: BackupSummary?, val confirming: Boolean = false, val mismatch: Boolean = false, val working: Boolean = false) : BackupPanel

    /** b4. */
    data class Saved(val backup: SavedBackup, val summary: BackupSummary?, val sealed: Boolean) : BackupPanel

    /** b13 and the backup failures. */
    data class CantBackUp(val problem: BackupProblem) : BackupPanel

    /** b5: the page at [url] runs while this shows. */
    data class Phone(val mode: BackupPhoneMode, val url: String) : BackupPanel

    /** b7. */
    data class RestoreList(val items: List<RestoreListItem>) : BackupPanel

    /** b8 (signed out) or b9 (signed in). [profiles]: the ticked backup profile ids. */
    data class Checklist(
        val file: RestoreFile,
        val header: BackupHeader,
        val matching: ProfileMatching,
        val tvNames: Map<Int, String>,
        val signedOut: Boolean,
        val profiles: Set<Int>,
        val device: Boolean,
        val secrets: Boolean,
        val working: Boolean = false,
    ) : BackupPanel {
        /** A sealed manual backup, or a safety copy (its keys are unsealed, R7). */
        val hasSecrets: Boolean get() = header.sealed || header.kind == BackupKind.PRE_RESTORE_COPY
    }

    /** b10. */
    data class EnterPin(val checklist: Checklist, val wrong: Boolean = false, val working: Boolean = false) : BackupPanel

    /** b12 and the restore failures; [header] names the file when it was read; [file] the refused one. */
    data class CantRestore(val problem: RestoreProblem, val header: BackupHeader?, val file: RestoreFile? = null) : BackupPanel
}

/** The pane's own values (b1). */
internal data class BackupPaneState(
    /** The newest manual backup's time, null when there is none. */
    val lastBackupAtMs: Long? = null,
    /** Manual backups plus safety copies on this TV. */
    val filesOnTv: Int = 0,
)

/** One-shot messages the screen shows as a toast. */
internal enum class BackupMessage { NoNetwork, NoPort }

/**
 * Settings › Backup (Spec 101 S7): the pane's values, the one panel over it, and the phone-page session.
 * A confirmed restore runs to its restart even if the screen goes away (the stager's copy and marker are
 * not undone by leaving), so it offers no cancel once staging starts.
 */
@HiltViewModel
internal class BackupViewModel @Inject constructor(
    private val manualBackups: ManualBackups,
    private val exporter: BackupExporter,
    private val stager: RestoreStager,
    private val copies: PreRestoreCopies,
    private val profileDataStore: ProfileDataStore,
    private val restarter: AppRestarter,
    phoneStarter: BackupPhoneStarter,
) : ViewModel() {

    private val _pane = MutableStateFlow(BackupPaneState())
    val pane: StateFlow<BackupPaneState> = _pane.asStateFlow()

    private val _panel = MutableStateFlow<BackupPanel>(BackupPanel.None)
    val panel: StateFlow<BackupPanel> = _panel.asStateFlow()

    private val _message = MutableStateFlow<BackupMessage?>(null)
    val message: StateFlow<BackupMessage?> = _message.asStateFlow()

    private val phone = phoneStarter.session(viewModelScope)
    private var firstPin: String? = null
    private var work: Job? = null

    init {
        refreshPane()
        viewModelScope.launch {
            phone.state.collect { state ->
                val shown = _panel.value
                val pending = state.pending
                when {
                    // An upload passed the server's checks (b6): the restore screen takes over (b8).
                    pending != null && shown is BackupPanel.Phone && shown.mode == BackupPhoneMode.RESTORE -> openChecklist(RestoreFile.Phone(pending))
                    // The 10 minutes ran out: the code no longer works.
                    state.url == null && shown is BackupPanel.Phone -> _panel.value = BackupPanel.None
                }
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    // ---- Back up (b2-b5, b13) ----

    fun openBackUp(toPhone: Boolean) {
        work?.cancel()
        _panel.value = BackupPanel.Choose(toPhone, summary = null)
        work = viewModelScope.launch {
            val summary = exporter.summary()
            _panel.update { if (it is BackupPanel.Choose) it.copy(summary = summary) else it }
        }
    }

    fun setSecrets(on: Boolean) {
        _panel.update { if (it is BackupPanel.Choose && !it.working) it.copy(secrets = on) else it }
    }

    /** b2's Next: the PIN first when passwords and keys go in (b3), else the backup itself. */
    fun chooseNext() {
        val choose = _panel.value as? BackupPanel.Choose ?: return
        if (choose.working) return
        if (choose.secrets) {
            firstPin = null
            _panel.value = BackupPanel.SetPin(choose.toPhone, choose.summary)
        } else {
            backUp(choose.toPhone, choose.summary, pin = null)
        }
    }

    /** b3's Next with a 6-12 digit [pin]: the first entry, then the second must match it. */
    fun pinEntered(pin: String) {
        val setPin = _panel.value as? BackupPanel.SetPin ?: return
        if (setPin.working) return
        val first = firstPin
        when {
            first == null -> {
                firstPin = pin
                _panel.value = setPin.copy(confirming = true, mismatch = false)
            }
            first == pin -> backUp(setPin.toPhone, setPin.summary, pin)
            else -> {
                firstPin = null
                _panel.value = setPin.copy(confirming = false, mismatch = true)
            }
        }
    }

    /** b3's Skip passwords: backs up without them. */
    fun skipPin() {
        val setPin = _panel.value as? BackupPanel.SetPin ?: return
        if (setPin.working) return
        backUp(setPin.toPhone, setPin.summary, pin = null)
    }

    private fun backUp(toPhone: Boolean, summary: BackupSummary?, pin: String?) {
        firstPin = null
        _panel.update {
            when (it) {
                is BackupPanel.Choose -> it.copy(working = true)
                is BackupPanel.SetPin -> it.copy(working = true)
                else -> it
            }
        }
        work?.cancel()
        work = viewModelScope.launch {
            val result = manualBackups.backUpNow(pin)
            refreshPane()
            // Next may have been pressed before b2's counts loaded.
            val counts = summary ?: exporter.summary()
            when (result) {
                is BackupExportResult.Saved ->
                    if (toPhone) showPhone(BackupPhoneMode.SAVE) else _panel.value = BackupPanel.Saved(result.backup, counts, sealed = pin != null)
                is BackupExportResult.TooLarge -> _panel.value = BackupPanel.CantBackUp(
                    BackupProblem.TooLarge(
                        profileName = result.profile?.let { id -> counts?.profiles?.firstOrNull { it.id == id }?.name },
                        store = result.store,
                    ),
                )
                is BackupExportResult.Failed, is BackupExportResult.Written -> _panel.value = BackupPanel.CantBackUp(BackupProblem.Failed)
            }
        }
    }

    /** b4's Save to phone too, and Restore from your phone (b5). */
    fun openPhone(mode: BackupPhoneMode) {
        work?.cancel()
        showPhone(mode)
    }

    /** Opens the page; also Save a backup to your phone once its fresh backup is saved (from inside that job). */
    private fun showPhone(mode: BackupPhoneMode) {
        _panel.value = when (phone.open(mode)) {
            BackupPhoneOpen.Opened -> phone.state.value.url?.let { BackupPanel.Phone(mode, it) } ?: BackupPanel.None
            BackupPhoneOpen.NoNetwork -> {
                _message.value = BackupMessage.NoNetwork
                BackupPanel.None
            }
            BackupPhoneOpen.NoPort -> {
                _message.value = BackupMessage.NoPort
                BackupPanel.None
            }
        }
    }

    // ---- Restore (b7-b12) ----

    fun openRestoreList() {
        work?.cancel()
        work = viewModelScope.launch {
            val items = restoreItems()
            // Another row's panel opened meanwhile: it wins.
            if (_panel.value == BackupPanel.None) _panel.value = BackupPanel.RestoreList(items)
        }
    }

    /** A b7 row: refused at once when its header says it cannot restore (b12), else what to restore (b8, b9). */
    fun pick(item: RestoreListItem) {
        val header = item.header ?: run {
            _panel.value = BackupPanel.CantRestore(RestoreProblem.Damaged, header = null, item.file)
            return
        }
        versionProblem(header)?.let {
            _panel.value = BackupPanel.CantRestore(it, header, item.file)
            return
        }
        openChecklist(item.file, header)
    }

    private fun openChecklist(file: RestoreFile, known: BackupHeader? = null) {
        work?.cancel()
        work = viewModelScope.launch {
            val header = known ?: (file as RestoreFile.Phone).upload.header
            val tv = tvProfiles()
            val matching = ProfileMatching.of(header.profiles, tv)
            val checklist = BackupPanel.Checklist(
                file = file,
                header = header,
                matching = matching,
                tvNames = tv.associate { it.id to it.name },
                signedOut = stager.isSignedOut(),
                profiles = matching.matches.mapTo(HashSet()) { it.backupId },
                // D5: This TV settings start unticked.
                device = false,
                secrets = false,
            )
            _panel.value = checklist.copy(secrets = checklist.hasSecrets)
        }
    }

    fun toggleProfile(backupId: Int) = updateChecklist { it.copy(profiles = if (backupId in it.profiles) it.profiles - backupId else it.profiles + backupId) }

    fun toggleDevice() = updateChecklist { it.copy(device = !it.device) }

    fun toggleSecrets() = updateChecklist { it.copy(secrets = !it.secrets) }

    private fun updateChecklist(change: (BackupPanel.Checklist) -> BackupPanel.Checklist) {
        _panel.update { if (it is BackupPanel.Checklist && !it.working) change(it) else it }
    }

    /** b8/b9's Restore and restart: the PIN first when sealed passwords and keys are ticked (b10). */
    fun confirmRestore() {
        val checklist = _panel.value as? BackupPanel.Checklist ?: return
        if (checklist.working || !checklist.canRestore()) return
        if (checklist.secrets && checklist.header.sealed) {
            _panel.value = BackupPanel.EnterPin(checklist)
        } else {
            stage(checklist, pin = null)
        }
    }

    /** b10's Restore and restart with [pin]. */
    fun restoreWithPin(pin: String) {
        val enter = _panel.value as? BackupPanel.EnterPin ?: return
        if (enter.working) return
        stage(enter.checklist, pin)
    }

    /** b10's Skip passwords: the other ticked parts restore. */
    fun skipRestorePin() {
        val enter = _panel.value as? BackupPanel.EnterPin ?: return
        if (enter.working) return
        val without = enter.checklist.copy(secrets = false)
        // Only passwords were ticked: nothing else to restore, so back to the checklist.
        if (without.canRestore()) stage(without, pin = null) else _panel.value = without
    }

    private fun stage(checklist: BackupPanel.Checklist, pin: String?) {
        val shown = _panel.value
        _panel.value = when (shown) {
            is BackupPanel.EnterPin -> shown.copy(working = true, wrong = false)
            else -> checklist.copy(working = true)
        }
        work?.cancel()
        // Past this point there is no cancel (S3 deferred item): leaving the screen must not stop a restore
        // whose copy and marker are made, or it would apply unannounced at the next start. The whole job,
        // restart included, is non-cancellable: a cancelled withContext would throw on its way out.
        // Not kept in [work], whose cancel() would reach this job through its own handle.
        work = null
        viewModelScope.launch(NonCancellable) {
            val result = stager.stage(
                source(checklist.file),
                RestoreRequest(checklist.profiles, checklist.device, checklist.secrets, pin),
            )
            val refused = when (result) {
                is RestoreStageResult.Staged -> {
                    phone.close()
                    // False only without a launch intent (the manifest declares one): the staging then
                    // applies at the next start, within R6's 10 minutes.
                    restarter.restart()
                    _panel.value = BackupPanel.None
                    return@launch
                }
                RestoreStageResult.WrongPin -> {
                    _panel.value = BackupPanel.EnterPin(checklist, wrong = true)
                    return@launch
                }
                is RestoreStageResult.Refused -> refusal(result.read)
                RestoreStageResult.NoSpace -> RestoreProblem.NoSpace
                is RestoreStageResult.CopyFailed -> RestoreProblem.CopyFailed
                is RestoreStageResult.Failed, RestoreStageResult.Disabled -> RestoreProblem.Failed
            }
            _panel.value = BackupPanel.CantRestore(refused, checklist.header, checklist.file)
        }
    }

    /**
     * Back, Cancel, Done or OK on any panel. Nothing closes while a backup or restore runs. A phone upload's
     * restore screen turned down frees the phone to send another (back to b5 while its code still works).
     */
    fun dismiss() {
        when (val shown = _panel.value) {
            is BackupPanel.Choose -> if (shown.working) return
            is BackupPanel.SetPin -> if (shown.working) return
            is BackupPanel.EnterPin -> if (shown.working) return
            is BackupPanel.Checklist -> if (shown.working) return
            else -> Unit
        }
        work?.cancel()
        firstPin = null
        val shown = _panel.value
        val upload = when (shown) {
            is BackupPanel.Checklist -> (shown.file as? RestoreFile.Phone)?.upload
            is BackupPanel.EnterPin -> (shown.checklist.file as? RestoreFile.Phone)?.upload
            is BackupPanel.CantRestore -> (shown.file as? RestoreFile.Phone)?.upload
            else -> null
        }
        when {
            upload != null -> {
                phone.release(upload.id)
                val url = phone.state.value.url
                _panel.value = if (url != null) BackupPanel.Phone(BackupPhoneMode.RESTORE, url) else BackupPanel.None
            }
            shown is BackupPanel.Phone -> {
                phone.close()
                _panel.value = BackupPanel.None
            }
            else -> _panel.value = BackupPanel.None
        }
    }

    override fun onCleared() {
        phone.close()
    }

    // ---- helpers ----

    private fun refreshPane() {
        viewModelScope.launch {
            val manual = manualBackups.list()
            val newest = manual.firstOrNull()?.let { backup -> manualBackups.open(backup)?.use { readHeader(it) } }
            _pane.value = BackupPaneState(
                lastBackupAtMs = newest?.createdAtMs,
                filesOnTv = manual.size + copies.list().size,
            )
        }
    }

    private suspend fun restoreItems(): List<RestoreListItem> {
        val manual = manualBackups.list().map { backup ->
            val header = manualBackups.open(backup)?.use { readHeader(it) }
            RestoreListItem(RestoreFile.Manual(backup), header, backup.sizeBytes)
        }
        val copyItems = copies.list().map { file ->
            withContext(Dispatchers.IO) {
                val header = try {
                    file.inputStream().use { BackupCodec.readHeader(it, trustedCopy = copies.isCopy(file)) }
                } catch (_: IOException) {
                    null
                }
                RestoreListItem(RestoreFile.Copy(file), header, file.length())
            }
        }
        // b7: newest first across both kinds; an unreadable file sorts last.
        return (manual + copyItems).sortedByDescending { it.header?.createdAtMs ?: Long.MIN_VALUE }
    }

    private suspend fun readHeader(input: java.io.InputStream): BackupHeader? =
        withContext(Dispatchers.IO) { BackupCodec.readHeader(input) }

    private suspend fun tvProfiles(): List<BackupProfile> =
        profileDataStore.profilesList.first().map { BackupProfile(it.id, it.name, it.usesPrimaryAddons) }

    private fun source(file: RestoreFile): RestoreSource = when (file) {
        is RestoreFile.Manual -> manualBackups.restoreSource(file.backup)
        is RestoreFile.Copy -> RestoreSource.Copy(file.file)
        is RestoreFile.Phone -> RestoreSource.Stream(BackupLimits.PHONE_COMPRESSED_MAX_BYTES) { file.upload.file.inputStream() }
    }

    internal companion object {
        /**
         * R2 from the header alone, so b12 opens instead of b8; the stager reads the whole file again. A
         * newer format whose header no longer reads lists as unreadable (Damaged): this install wrote it, so
         * only a downgrade could make one.
         */
        fun versionProblem(header: BackupHeader): RestoreProblem? =
            BackupCodec.versionVerdict(header.formatVersion, header.appVersionCode, header.appVersionName, BuildConfig.VERSION_CODE)
                ?.let(::refusal)

        fun refusal(read: BackupReadResult): RestoreProblem = when (read) {
            is BackupReadResult.NeedsNewerApp -> RestoreProblem.Newer(read.backupVersionName)
            is BackupReadResult.TooOld -> RestoreProblem.Older(read.backupVersionName)
            is BackupReadResult.Invalid ->
                if (read.reason == BackupReadResult.Reason.TOO_LARGE) RestoreProblem.TooLarge else RestoreProblem.Damaged
            is BackupReadResult.Ok -> RestoreProblem.Failed
        }
    }
}

/** Something to restore: a matched profile, This TV settings, or passwords and keys. */
internal fun BackupPanel.Checklist.canRestore(): Boolean = profiles.isNotEmpty() || device || (secrets && hasSecrets)
