package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 44a: the look key, the snapshot rule and the resolver's precedence. */
class ProfileLookTest {

    private val sets = LocalLookSets(
        photoIds = setOf("p1"),
        bundledAvatars = setOf("fox", "owl"),
        bundledBackgrounds = setOf("ember")
    )
    private val catalog = mapOf("kira" to "https://img/kira.png")
    private val sara = UserProfile(
        id = 2,
        name = "Sara",
        avatarColorHex = "#C2185B",
        avatarId = "kira",
        profileBackgroundId = "dunes"
    )

    private fun entry(
        profile: UserProfile,
        avatar: ProfileLookPick? = null,
        background: ProfileLookPick? = null
    ): ProfileLookEntry = checkNotNull(ProfileLookEntry.of(profile, avatar, background))

    @Test
    fun `a local pick that applies wins over the synced avatar and background`() {
        val look = entry(sara, ProfileLookPick.Bundled("fox"), ProfileLookPick.Bundled("ember"))
        assertEquals(LookAvatar.Local(ProfileLookPick.Bundled("fox")), resolveLookAvatar(sara, look, sets, catalog::get))
        assertEquals(
            ProfileBackgroundSelection.Local(ProfileLookPick.Bundled("ember")),
            resolveLookBackground(sara, look, sets)
        )
    }

    @Test
    fun `without an entry the synced values show, as built`() {
        assertEquals(LookAvatar.Synced("https://img/kira.png"), resolveLookAvatar(sara, null, sets, catalog::get))
        assertEquals(ProfileBackgroundSelection.Catalog("dunes"), resolveLookBackground(sara, null, sets))
        val own = sara.copy(avatarUrl = "https://img/own.png", profileBackgroundUrl = "https://img/bg.png")
        assertEquals(LookAvatar.Synced("https://img/own.png"), resolveLookAvatar(own, null, sets, catalog::get))
        assertEquals(ProfileBackgroundSelection.Custom("https://img/bg.png"), resolveLookBackground(own, null, sets))
        assertNull(resolveLookAvatar(sara.copy(avatarId = "unknown"), null, sets, catalog::get))
        assertNull(resolveLookBackground(null, null, sets))
    }

    @Test
    fun `a change from another device shows the synced value`() {
        val look = entry(sara, ProfileLookPick.Photo("p1"), ProfileLookPick.Bundled("ember"))
        // Another device changed the avatar: the avatar choice yields, the background one stays.
        val newAvatar = sara.copy(avatarId = "thom")
        assertEquals(null, resolveLookAvatar(newAvatar, look, sets, catalog::get))
        assertEquals(ProfileBackgroundSelection.Local(ProfileLookPick.Bundled("ember")), resolveLookBackground(newAvatar, look, sets))
        // Another device changed the background: the background choice yields.
        val newBackground = sara.copy(profileBackgroundUrl = "https://img/web-bg.png")
        assertEquals(ProfileBackgroundSelection.Custom("https://img/web-bg.png"), resolveLookBackground(newBackground, look, sets))
        assertEquals(LookAvatar.Local(ProfileLookPick.Photo("p1")), resolveLookAvatar(newBackground, look, sets, catalog::get))
        // A rename on another device drops both (the tradeoff the Design accepts).
        val renamed = sara.copy(name = "Sarah")
        assertEquals(LookAvatar.Synced("https://img/kira.png"), resolveLookAvatar(renamed, look, sets, catalog::get))
        assertEquals(ProfileBackgroundSelection.Catalog("dunes"), resolveLookBackground(renamed, look, sets))
    }

    @Test
    fun `a profile deleted and recreated at the same index elsewhere does not inherit the look`() {
        val look = entry(sara, ProfileLookPick.Bundled("fox"))
        val recreated = UserProfile(id = 2, name = "Guest", avatarColorHex = "#1E88E5")
        assertNull(resolveLookAvatar(recreated, look, sets, catalog::get))
    }

    @Test
    fun `an unknown slug or a removed photo resolves to the synced value`() {
        val synced = LookAvatar.Synced("https://img/kira.png")
        assertEquals(synced, resolveLookAvatar(sara, entry(sara, ProfileLookPick.Bundled("dodo")), sets, catalog::get))
        assertEquals(synced, resolveLookAvatar(sara, entry(sara, ProfileLookPick.Photo("gone")), sets, catalog::get))
        // A bundled avatar slug is not a bundled background.
        assertEquals(
            ProfileBackgroundSelection.Catalog("dunes"),
            resolveLookBackground(sara, entry(sara, background = ProfileLookPick.Bundled("fox")), sets)
        )
    }

    @Test
    fun `the key normalises a pulled blank against a saved null`() {
        val saved = sara.copy(name = "Sara ", avatarUrl = null, profileBackgroundId = null, profileBackgroundUrl = null)
        val pulled = sara.copy(name = "Sara", avatarUrl = "", profileBackgroundId = "  ", profileBackgroundUrl = "")
        assertEquals(syncedLookKey(saved), syncedLookKey(pulled))
        val look = entry(saved, ProfileLookPick.Bundled("fox"))
        assertEquals(LookAvatar.Local(ProfileLookPick.Bundled("fox")), resolveLookAvatar(pulled, look, sets, catalog::get))
    }

    @Test
    fun `an id plus a url keys on the url, as the push sends it`() {
        val saved = sara.copy(avatarId = "kira", avatarUrl = "https://img/own.png")
        val pulled = sara.copy(avatarId = null, avatarUrl = "https://img/own.png")
        assertEquals(SyncedAvatar(id = null, url = "https://img/own.png"), saved.syncedAvatar())
        assertEquals(syncedLookKey(saved), syncedLookKey(pulled))
        assertEquals(SyncedAvatar(id = "kira", url = null), sara.copy(avatarUrl = " ").syncedAvatar())
        assertEquals(SyncedBackground(id = "dunes", url = null), sara.copy(profileBackgroundUrl = "").syncedBackground())
    }

    @Test
    fun `a photo is used by the profiles whose applying look shows it`() {
        val omar = UserProfile(id = 3, name = "Omar", avatarColorHex = "#5E35B1", avatarId = "kira")
        val zayn = UserProfile(id = 4, name = "Zayn", avatarColorHex = "#43A047")
        val entries = mapOf(
            sara.id to entry(sara, background = ProfileLookPick.Photo("p1")),
            omar.id to entry(omar, avatar = ProfileLookPick.Photo("p1")),
            // Saved over an older name: inert, so not a user.
            zayn.id to entry(zayn.copy(name = "Z"), avatar = ProfileLookPick.Photo("p1"))
        )
        assertEquals(listOf(sara, omar), photoUsers("p1", listOf(sara, omar, zayn), entries, sets))
        assertEquals(emptyList<UserProfile>(), photoUsers("p2", listOf(sara, omar, zayn), entries, sets))
        // Removed from My photos: nobody shows it.
        assertEquals(emptyList<UserProfile>(), photoUsers("p1", listOf(sara, omar), entries, sets.copy(photoIds = emptySet())))
    }

    @Test
    fun `an entry holds only the local parts and clears when there are none`() {
        assertNull(ProfileLookEntry.of(sara, null, null))
        val look = entry(sara, avatar = ProfileLookPick.Bundled("owl"))
        assertEquals(ProfileLookChoice(ProfileLookPick.Bundled("owl"), "Sara", "kira"), look.avatar)
        assertNull(look.background)
    }
}
