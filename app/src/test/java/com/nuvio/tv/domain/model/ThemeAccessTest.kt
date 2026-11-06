package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeAccessTest {
    @Test
    fun everyThemeIsSelectableWhiteFirst() {
        assertEquals(AppTheme.WHITE, selectableAppThemes.first())
        assertEquals(AppTheme.entries.toSet(), selectableAppThemes.toSet())
        assertEquals(AppTheme.entries.size, selectableAppThemes.size)
    }

    @Test
    fun storedThemeIsKeptAndNoneReadsWhite() {
        assertEquals(AppTheme.GOLD, resolveAppTheme(AppTheme.GOLD))
        assertEquals(AppTheme.OCEAN, resolveAppTheme(AppTheme.OCEAN))
        assertEquals(AppTheme.WHITE, resolveAppTheme(null))
    }
}
