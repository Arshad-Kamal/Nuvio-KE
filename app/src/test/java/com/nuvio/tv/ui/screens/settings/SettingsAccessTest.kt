package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAccessTest {

    @Test
    fun `primary profile opens all top-bar routes`() {
        assertTrue(
            SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive = true, settingsAccessGranted = false).isEmpty()
        )
    }

    @Test
    fun `non-primary profile is blocked from the settings top-bar route only`() {
        assertEquals(
            setOf(Screen.Settings.route),
            SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive = false, settingsAccessGranted = false)
        )
    }

    @Test
    fun `non-primary is ejected from every settings-owned route`() {
        SettingsAccess.settingsOwnedRoutes.forEach { route ->
            assertTrue(
                "expected eject from $route",
                SettingsAccess.shouldEjectFromRoute(route, isPrimaryProfileActive = false, settingsAccessGranted = false)
            )
        }
    }

    // Pinned by name, not via settingsOwnedRoutes: the collection screens are reachable
    // only from AddonManager, so dropping them from the set must fail here.
    @Test
    fun `non-primary is ejected from the collection screens`() {
        listOf(Screen.Collections.route, Screen.CollectionEditor.route).forEach { route ->
            assertTrue(
                "expected eject from $route",
                SettingsAccess.shouldEjectFromRoute(route, isPrimaryProfileActive = false, settingsAccessGranted = false)
            )
        }
    }

    @Test
    fun `primary is never ejected`() {
        SettingsAccess.settingsOwnedRoutes.forEach { route ->
            assertFalse(
                "primary must keep $route",
                SettingsAccess.shouldEjectFromRoute(route, isPrimaryProfileActive = true, settingsAccessGranted = false)
            )
        }
    }

    @Test
    fun `null and non-settings routes never eject`() {
        assertFalse(
            SettingsAccess.shouldEjectFromRoute(null, isPrimaryProfileActive = false, settingsAccessGranted = false)
        )
        listOf(
            Screen.Home.route,
            Screen.Search.route,
            Screen.Library.route,
            Screen.Discover.route
        ).forEach { route ->
            assertFalse(
                "$route must stay reachable for non-primary",
                SettingsAccess.shouldEjectFromRoute(route, isPrimaryProfileActive = false, settingsAccessGranted = false)
            )
        }
    }

    // T5: the primary's grant lifts the whole lock.

    @Test
    fun `granted non-primary opens all top-bar routes`() {
        assertTrue(
            SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive = false, settingsAccessGranted = true).isEmpty()
        )
    }

    @Test
    fun `granted non-primary is never ejected from settings-owned routes`() {
        SettingsAccess.settingsOwnedRoutes.forEach { route ->
            assertFalse(
                "granted non-primary must keep $route",
                SettingsAccess.shouldEjectFromRoute(route, isPrimaryProfileActive = false, settingsAccessGranted = true)
            )
        }
    }

    @Test
    fun `the grant does not matter for the primary`() {
        assertEquals(
            SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive = true, settingsAccessGranted = true),
            SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive = true, settingsAccessGranted = false)
        )
        SettingsAccess.settingsOwnedRoutes.forEach { route ->
            assertFalse(
                "primary must keep $route regardless of the grant",
                SettingsAccess.shouldEjectFromRoute(route, isPrimaryProfileActive = true, settingsAccessGranted = true)
            )
        }
    }

    @Test
    fun `Home's Open addons is blocked exactly when AddonManager would eject`() {
        for (primary in listOf(true, false)) {
            for (granted in listOf(true, false)) {
                val blocked = SettingsAccess.blockedTopBarRoutes(primary, granted)
                assertEquals(
                    "primary=$primary granted=$granted",
                    SettingsAccess.shouldEjectFromRoute(Screen.AddonManager.route, primary, granted),
                    SettingsAccess.isBlockedFromTopBar(Screen.AddonManager.route, blocked)
                )
            }
        }
        assertFalse(SettingsAccess.isBlockedFromTopBar(Screen.Library.route, setOf(Screen.Settings.route)))
    }
}
