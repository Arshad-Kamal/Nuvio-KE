package com.nuvio.tv.ui.screens.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.LocalLookLibrary
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import kotlin.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 117 F2 (Shield window 2026-11-20): a layer drawn over the real [ProfileEditor] (Content
 * filters page, Rating limit panel, My photo panel) takes focus on open although focus sits in the
 * editor, whose focus trap a requestFocus also runs; the D-pad then stays in the layer, and Back
 * returns to its row or tile.
 * `ContentFiltersPageFocusTest` hosts the page alone, so it could not see the editor's trap.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProfileEditorLayerFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val photo = MyPhoto("p1", MyPhotoKind.Avatar, Instant.fromEpochMilliseconds(0), File("/nonexistent/p1.jpg"))

    @Before
    fun setUp() {
        val library = LocalLookLibrary(listOf(photo))
        val draft = ProfileEditorDraft.edit(
            profile = UserProfile(id = 2, name = "Zayn", avatarColorHex = "#E8743B"),
            liveTvEnabled = true,
            ratingCap = null,
            look = null,
            sets = library.sets,
            contentFilters = ContentFilterSettings()
        )
        rule.setContent {
            NuvioTheme {
                ProfileEditor(
                    draft = draft,
                    avatarCatalog = emptyList(),
                    profileBackgroundCatalog = emptyList(),
                    catalogImageUrl = { null },
                    background = { _, _ -> },
                    liveTvEditable = true,
                    ratingLimitAccess = RatingLimitAccess.EDIT,
                    openProfiles = emptyList(),
                    contentFilterAccess = ContentFilterAccess.EDIT,
                    builtInPacks = listOf(FilterPack("anime")),
                    tagSearch = null,
                    filterPreview = null,
                    onDismiss = {},
                    onSubmit = {},
                    library = library,
                    photoUserNames = { emptyList() },
                    onRemovePhoto = {},
                    onAddFromPhone = {}
                )
            }
        }
        rule.waitForIdle()
        // Focus starts on the Name field (keyboard up), as on the TV.
        rule.onNode(isFocused()).assert(hasSetTextAction())
    }

    /**
     * From the Name field to the Access row [downs] below Live TV, then OK: the remote's path. Tab,
     * not Down, leaves the field: a test key has no D-pad source, so the field keeps Down (117 F3).
     */
    private fun openAccessRow(downs: Int) {
        press(Key.Tab)
        repeat(downs) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)
    }

    private fun focused(text: String) {
        rule.onNode(isFocused()).assert(hasText(text))
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
    fun contentFiltersPageTakesAndHoldsFocusOverTheEditor() {
        openAccessRow(downs = 2)
        focused("Your own words and phrases")
        press(Key.DirectionDown)
        focused("Your own name, words, genres and tags")
        // Right from Create a pack enters its pane, never the editor's avatar grid underneath.
        press(Key.DirectionRight)
        rule.onNodeWithTag(PACK_PANE_TAG).assert(hasAnyDescendant(isFocused()))
        back()
        focused("Your own name, words, genres and tags")
        back()
        focused("Content filters")
    }

    @Test
    fun ratingLimitPanelTakesFocusOverTheEditor() {
        openAccessRow(downs = 1)
        // The panel's No limit row (the editor's own row reads "Rating limit" too, so check it is not that one).
        focused("No limit")
        rule.onNode(isFocused()).assert(!hasText("Rating limit"))
    }

    /** The photo tile holds focus: the picker's note is the My photos one and Add from phone is not focused. */
    private fun photoTileFocused(): Boolean =
        rule.onAllNodesWithText(MY_PHOTOS_NOTE).fetchSemanticsNodes().isNotEmpty() &&
            rule.onAllNodesWithText(ADD_FROM_PHONE_SUB).fetchSemanticsNodes().isEmpty()

    @Test
    fun myPhotoPanelTakesFocusAndBackReturnsToTheHeldTile() {
        // Tab (D-pad Next) through the editor to the photo tile, then MENU on it.
        repeat(40) { if (!photoTileFocused()) press(Key.Tab) }
        assertTrue(photoTileFocused())
        // MENU opens the panel like a held OK (MarqueeLongPress; the semantics long click is the Card's own).
        press(Key.Menu)
        focused("Use for Zayn")
        back()
        assertTrue(photoTileFocused())
    }

    private companion object {
        const val MY_PHOTOS_NOTE = "My photos are shared by every profile on this TV and are not synced to your other devices."
        const val ADD_FROM_PHONE_SUB = "Shows a QR code"
    }
}
