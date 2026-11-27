package com.nuvio.tv.core.backup

import com.nuvio.tv.core.backup.SkippedProfile.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileMatchingTest {

    private fun p(id: Int, name: String, addons: Boolean = false) = BackupProfile(id, name, addons)

    @Test
    fun `primary takes primary whatever the names, the rest match by name ignoring case and extra spaces`() {
        val result = ProfileMatching.of(
            backup = listOf(p(1, "Arshad"), p(2, "  Big   SIS "), p(3, "Kids")),
            tv = listOf(p(1, "Living room"), p(4, "kids"), p(2, "big sis")),
        )
        assertEquals(listOf(ProfileMatch(1, 1, false), ProfileMatch(2, 2, false), ProfileMatch(3, 4, false)), result.matches)
        assertEquals(emptyList<SkippedProfile>(), result.skipped)
    }

    @Test
    fun `a name not on this TV is skipped and listed`() {
        val result = ProfileMatching.of(listOf(p(1, "A"), p(2, "Sara")), listOf(p(1, "A"), p(2, "Kids")))
        assertEquals(listOf(ProfileMatch(1, 1, false)), result.matches)
        assertEquals(listOf(SkippedProfile(2, "Sara", Reason.NOT_ON_TV)), result.skipped)
    }

    @Test
    fun `an empty name or one that occurs twice on either side matches nothing`() {
        val dupBackup = ProfileMatching.of(listOf(p(1, "A"), p(2, "Kids"), p(3, "KIDS"), p(4, "  ")), listOf(p(1, "A"), p(2, "Kids"), p(3, "")))
        assertEquals(listOf(ProfileMatch(1, 1, false)), dupBackup.matches)
        assertEquals(listOf(2, 3, 4), dupBackup.skipped.map { it.backupId })
        assertEquals(setOf(Reason.NAME_NOT_UNIQUE), dupBackup.skipped.map { it.reason }.toSet())

        val dupTv = ProfileMatching.of(listOf(p(1, "A"), p(2, "Kids")), listOf(p(1, "A"), p(2, "kids"), p(5, "Kids ")))
        assertEquals(listOf(SkippedProfile(2, "Kids", Reason.NAME_NOT_UNIQUE)), dupTv.skipped)
    }

    @Test
    fun `the TV primary is never a name match, and its name does not make others ambiguous`() {
        val result = ProfileMatching.of(listOf(p(1, "A"), p(2, "Kids")), listOf(p(1, "Kids"), p(3, "Kids")))
        assertEquals(listOf(ProfileMatch(1, 1, false), ProfileMatch(2, 3, false)), result.matches)
    }

    @Test
    fun `a pair whose addon sharing differs is flagged`() {
        val result = ProfileMatching.of(listOf(p(1, "A"), p(2, "Kids", addons = true)), listOf(p(1, "A"), p(2, "Kids", addons = false)))
        assertEquals(listOf(ProfileMatch(1, 1, false), ProfileMatch(2, 2, true)), result.matches)
    }
}
