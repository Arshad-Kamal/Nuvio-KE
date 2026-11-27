package com.nuvio.tv.ui.screens.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 117 S7a/S7b: the Content filters page's D-pad and Back contract (P9, M1 stages 2, 3): My
 * words focused on open; on a pack OK flips it, Right enters only a pane that overflows, Back from the
 * pane returns to its pack; My words' pane edits; Back from the list closes the page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ContentFiltersPageFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var settings by mutableStateOf(ContentFilterSettings())
    private var dismissed = 0

    // "Anime" sorts first (A-Z); "Long" lists enough words to overflow the pane.
    private val listed = listOf(
        ListedPack(FilterPack("anime", words = listOf(PackWord("anime"))), "Anime", "Anime desc"),
        ListedPack(FilterPack("long", words = (1..300).map { PackWord("word$it") }), "Long", "Long desc")
    )

    @Before
    fun setUp() {
        rule.setContent {
            NuvioTheme {
                ContentFiltersPage(
                    crumbs = listOf("Profiles", "Zayn", "Content filters"),
                    profileName = "Zayn",
                    avatarColorHex = "#E8743B",
                    avatarArt = null,
                    listed = listed,
                    settings = settings,
                    onTogglePack = { id ->
                        val on = settings.enabledPacks
                        settings = settings.copy(enabledPacks = if (id in on) on - id else on + id)
                    },
                    onEdit = { change -> settings = change(settings) },
                    onCreatePack = { null },
                    tagSearch = null,
                    onDismiss = { dismissed++ }
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Your own words and phrases").assertIsFocused()
    }

    /** From My words (focused on open) past Create a pack to the first built-in pack. */
    private fun toFirstPack() {
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        rule.onNodeWithText("Anime desc").assertIsFocused()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun okFlipsTheFocusedPack() {
        toFirstPack()
        press(Key.DirectionCenter)
        assertEquals(setOf("anime"), settings.enabledPacks)
        press(Key.DirectionCenter)
        assertEquals(emptySet<String>(), settings.enabledPacks)
    }

    @Test
    fun rightStaysOnAPackWhosePaneFits() {
        toFirstPack()
        press(Key.DirectionRight)
        rule.onNodeWithText("Anime desc").assertIsFocused()
    }

    @Test
    fun rightEntersAnOverflowingPaneAndBackOrLeftReturnToItsPack() {
        toFirstPack()
        press(Key.DirectionDown)
        rule.onNodeWithText("Long desc").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(PACK_PANE_TAG).assertIsFocused()
        rule.onNodeWithText("word1").assertIsDisplayed()
        // Down scrolls the pane (well past its first words) and keeps focus in it.
        repeat(6) { press(Key.DirectionDown) }
        rule.onNodeWithTag(PACK_PANE_TAG).assertIsFocused()
        rule.onNodeWithText("word1").assertIsNotDisplayed()
        back()
        rule.onNodeWithText("Long desc").assertIsFocused()
        assertEquals(0, dismissed)
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        rule.onNodeWithText("Long desc").assertIsFocused()
        assertEquals(0, dismissed)
    }

    @Test
    fun okOnMyWordsEntersItsPaneAWordChipRemovesItAndBackReturns() {
        settings = settings.copy(myWords = listOf("wwe", "dragon ball", "bluey"))
        rule.waitForIdle()
        press(Key.DirectionRight)
        rule.onNodeWithText("wwe").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("dragon ball", "bluey"), settings.myWords)
        // Focus takes the chip now in the removed one's place.
        rule.onNodeWithText("dragon ball").assertIsFocused()
        back()
        rule.onNodeWithText("dragon ball, bluey").assertIsFocused()
        assertEquals(0, dismissed)
        press(Key.DirectionCenter)
        rule.onNodeWithText("+ Add a word").assertIsFocused()
    }

    @Test
    fun backOnTheListClosesThePage() {
        back()
        assertEquals(1, dismissed)
    }
}
