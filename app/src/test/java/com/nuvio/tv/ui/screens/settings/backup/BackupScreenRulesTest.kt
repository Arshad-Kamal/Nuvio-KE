package com.nuvio.tv.ui.screens.settings.backup

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.backup.BACKUP_FORMAT_ID
import com.nuvio.tv.core.backup.BACKUP_FORMAT_VERSION
import com.nuvio.tv.core.backup.BackupHeader
import com.nuvio.tv.core.backup.BackupKind
import com.nuvio.tv.core.backup.BackupProfile
import com.nuvio.tv.core.backup.BackupReadResult
import com.nuvio.tv.core.backup.MIN_BACKUP_VERSION_CODE
import com.nuvio.tv.core.backup.ProfileMatching
import com.nuvio.tv.core.backup.SavedBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 101 S7: the restore screen's rules that are not layout (b7 -> b12, b8's Restore button). */
class BackupScreenRulesTest {

    private fun header(code: Int = BuildConfig.VERSION_CODE, format: Int = BACKUP_FORMAT_VERSION, kind: BackupKind = BackupKind.MANUAL, sealed: Boolean = false) =
        BackupHeader(BACKUP_FORMAT_ID, format, code, "v$code", 0, kind, listOf(BackupProfile(1, "A", false)), sealed)

    @Test
    fun `a header newer or older than this build opens b12 instead of b8`() {
        assertNull(BackupViewModel.versionProblem(header()))
        assertNull(BackupViewModel.versionProblem(header(code = MIN_BACKUP_VERSION_CODE)))
        assertEquals(RestoreProblem.Newer("v${BuildConfig.VERSION_CODE + 1}"), BackupViewModel.versionProblem(header(code = BuildConfig.VERSION_CODE + 1)))
        assertEquals(RestoreProblem.Newer("v${BuildConfig.VERSION_CODE}"), BackupViewModel.versionProblem(header(format = BACKUP_FORMAT_VERSION + 1)))
        assertEquals(RestoreProblem.Older("v${MIN_BACKUP_VERSION_CODE - 1}"), BackupViewModel.versionProblem(header(code = MIN_BACKUP_VERSION_CODE - 1)))
    }

    @Test
    fun `the stager's refusals map to b12 or the damaged and too-large lines`() {
        assertEquals(RestoreProblem.Newer("2"), BackupViewModel.refusal(BackupReadResult.NeedsNewerApp("2", 9)))
        assertEquals(RestoreProblem.Older("0"), BackupViewModel.refusal(BackupReadResult.TooOld("0", 1)))
        assertEquals(RestoreProblem.TooLarge, BackupViewModel.refusal(BackupReadResult.Invalid(BackupReadResult.Reason.TOO_LARGE, "")))
        assertEquals(RestoreProblem.Damaged, BackupViewModel.refusal(BackupReadResult.Invalid(BackupReadResult.Reason.DAMAGED, "")))
        assertEquals(RestoreProblem.Damaged, BackupViewModel.refusal(BackupReadResult.Invalid(BackupReadResult.Reason.NOT_A_BACKUP, "")))
    }

    @Test
    fun `Restore and restart needs something ticked, and passwords count only when the file has them`() {
        val file = RestoreFile.Manual(SavedBackup("id", "n", 1, 1))
        val matching = ProfileMatching(emptyList(), emptyList())
        val none = BackupPanel.Checklist(file, header(), matching, emptyMap(), signedOut = true, profiles = emptySet(), device = false, secrets = true)
        assertFalse(none.canRestore())
        assertTrue(none.copy(header = header(sealed = true)).canRestore())
        assertTrue(none.copy(header = header(kind = BackupKind.PRE_RESTORE_COPY)).canRestore())
        assertTrue(none.copy(device = true).canRestore())
        assertTrue(none.copy(profiles = setOf(1)).canRestore())
    }
}
