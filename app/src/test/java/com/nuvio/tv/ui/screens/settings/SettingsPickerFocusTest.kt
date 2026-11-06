package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPickerFocusTest {

    private val modes = listOf("manual", "first", "regex", "quality").map { SettingsPickerOption(it, it) }

    @Test
    fun `a picker opens on the selected option`() {
        assertEquals(3, settingsPickerInitialIndex(modes, "quality"))
        assertEquals(0, settingsPickerInitialIndex(modes, "manual"))
    }

    @Test
    fun `a picker whose value is not listed opens on the first option`() {
        assertEquals(0, settingsPickerInitialIndex(modes, "gone"))
        assertEquals(0, settingsPickerInitialIndex(emptyList<SettingsPickerOption<String>>(), "gone"))
    }

    @Test
    fun `a null value matches a None option`() {
        val languages = listOf(SettingsPickerOption<String?>(null, "None"), SettingsPickerOption<String?>("en", "English"))
        assertEquals(0, settingsPickerInitialIndex(languages, null))
        assertEquals(1, settingsPickerInitialIndex(languages, "en"))
    }

    @Test
    fun `a checklist opens on its first ticked option, after the all row`() {
        assertEquals(3, settingsChecklistInitialIndex(modes, setOf("regex", "quality"), hasAllRow = true))
        assertEquals(2, settingsChecklistInitialIndex(modes, setOf("quality", "regex"), hasAllRow = false))
    }

    @Test
    fun `a checklist with nothing ticked opens on row 0`() {
        assertEquals(0, settingsChecklistInitialIndex(modes, emptySet(), hasAllRow = true))
        assertEquals(0, settingsChecklistInitialIndex(modes, setOf("gone"), hasAllRow = false))
    }
}
