package com.nuvio.tv.ui.screens.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.data.local.ContentFilterRead
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 122 D3 (117/S7c): on the primary's Content filters page "Preview filters of" is the first row,
 * focused on open; OK enters its pane on the picked choice, OK on a choice picks it at once, Back
 * returns to the row; Down from the row reaches My words.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ContentFilterPreviewPickerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val profiles = listOf(
        UserProfile(id = 1, name = "Arshad", avatarColorHex = "#C99A2E"),
        UserProfile(id = 2, name = "Zayn", avatarColorHex = "#E8743B")
    )
    private var reads by mutableStateOf<Map<Int, ContentFilterRead>>(
        mapOf(
            1 to ContentFilterRead.Stored(ContentFilterSettings()),
            2 to ContentFilterRead.Stored(ContentFilterSettings(enabledPacks = setOf("anime")))
        )
    )
    private val picks = mutableListOf<Int?>()
    // False: a pick whose write failed (the stored value, so the check, stays as it was).
    private var pickSaves = true

    @Before
    fun setUp() {
        rule.setContent {
            NuvioTheme {
                ContentFiltersPage(
                    crumbs = listOf("Profiles", "Arshad", "Content filters"),
                    profileName = "Arshad",
                    avatarColorHex = "#C99A2E",
                    avatarArt = null,
                    listed = listOf(ListedPack(FilterPack("anime"), "Anime", "Anime desc")),
                    settings = ContentFilterSettings(),
                    onTogglePack = {},
                    onEdit = {},
                    onCreatePack = { null },
                    tagSearch = null,
                    onDismiss = {},
                    preview = FilterPreviewInputs(profiles, reads, emptyMap()) { id ->
                        picks += id
                        if (!pickSaves) return@FilterPreviewInputs
                        val primary = (reads.getValue(1) as ContentFilterRead.Stored).settings
                        reads = reads + (1 to ContentFilterRead.Stored(primary.copy(previewProfileId = id)))
                    }
                )
            }
        }
        rule.waitForIdle()
        focused("Shows hidden titles on your Home, marked")
    }

    /** The focused node (a row or choice, its texts merged) shows [text]; a name can show on both. */
    private fun focused(text: String) {
        rule.onNode(isFocused()).assert(hasText(text))
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun okEntersThePaneOnThePickedChoiceAndOkPicksAnother() {
        press(Key.DirectionCenter)
        focused("Off")
        press(Key.DirectionDown)
        focused("Arshad (your own filters)")
        press(Key.DirectionDown)
        focused("Zayn")
        focused("1 on")
        press(Key.DirectionCenter)
        assertEquals(listOf<Int?>(2), picks)
        focused("Zayn")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        focused("Shows hidden titles on your Home, marked")
        // Entering again lands on the new pick.
        press(Key.DirectionCenter)
        focused("Zayn")
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        focused("Off")
        press(Key.DirectionCenter)
        assertEquals(listOf(2, null), picks)
    }

    @Test
    fun leftFromAChoiceReturnsToTheRowAndAFailedPickKeepsTheOldEntry() {
        pickSaves = false
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf<Int?>(2), picks)
        press(Key.DirectionLeft)
        focused("Shows hidden titles on your Home, marked")
        focused("Off")
        // Still Off: entry lands on it.
        press(Key.DirectionCenter)
        focused("Off")
    }

    /** 117 F10: the pane's list gets the pane's height, so every choice shows without a scroll. */
    @Test
    fun everyChoiceShowsInThePane() {
        press(Key.DirectionCenter)
        rule.onNodeWithText("Zayn").assertIsDisplayed()
    }

    @Test
    fun downFromThePickerReachesMyWords() {
        press(Key.DirectionDown)
        focused("Your own words and phrases")
    }
}
