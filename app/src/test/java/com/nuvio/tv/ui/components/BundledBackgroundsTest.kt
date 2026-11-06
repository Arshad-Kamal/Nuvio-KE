package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.LookAvatar
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.resolveLookAvatar
import com.nuvio.tv.domain.model.resolveLookBackground
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 44b: the bundled background set is pinned and resolves through [BundledLookSets]. */
class BundledBackgroundsTest {

    private val sara = UserProfile(id = 2, name = "Sara", avatarColorHex = "#C2185B", avatarId = "kira", profileBackgroundId = "dunes")
    private val catalog = mapOf("kira" to "https://img/kira.png")

    private fun withBackground(slug: String): ProfileLookEntry =
        checkNotNull(ProfileLookEntry.of(sara, null, ProfileLookPick.Bundled(slug)))

    @Test
    fun `the stored slugs never change`() {
        assertEquals(
            listOf(
                "arch_ember", "loop_magenta", "sweep_teal", "halo_gold",
                "wave_cobalt", "spiral_violet", "cascade_emerald", "infinity_ruby"
            ),
            BundledBackgrounds.all.map { it.slug }
        )
        assertEquals(BundledBackgrounds.all.size, BundledBackgrounds.bySlug.size)
        assertEquals(BundledBackgrounds.all.size, BundledBackgrounds.all.map { it.drawable }.toSet().size)
    }

    @Test
    fun `every shipped background resolves as a local background`() {
        BundledBackgrounds.all.forEach { background ->
            assertEquals(
                ProfileBackgroundSelection.Local(ProfileLookPick.Bundled(background.slug)),
                resolveLookBackground(sara, withBackground(background.slug), BundledLookSets)
            )
        }
    }

    @Test
    fun `an unknown or creature slug resolves to the synced background`() {
        val synced = ProfileBackgroundSelection.Catalog("dunes")
        assertEquals(synced, resolveLookBackground(sara, withBackground("ember"), BundledLookSets))
        assertEquals(synced, resolveLookBackground(sara, withBackground("fox"), BundledLookSets))
        // A background slug is not an avatar either.
        val asAvatar = checkNotNull(ProfileLookEntry.of(sara, ProfileLookPick.Bundled("arch_ember"), null))
        assertEquals(
            LookAvatar.Synced("https://img/kira.png"),
            resolveLookAvatar(sara, asAvatar, BundledLookSets, catalog::get)
        )
    }
}
