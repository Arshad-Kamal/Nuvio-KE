package com.nuvio.tv.domain.model

/**
 * The TV-only profile look (Spec 44 D2, Design "Local look"): a bundled or My photos avatar and/or
 * background chosen on this TV, stored per profile and never synced. Each choice carries a snapshot
 * of the synced fields it overrides ([SyncedLookKey]); it applies only while that snapshot still
 * matches the profile, so a change made on another device (avatar, background, name, or a profile
 * deleted and recreated at the same index) wins over it.
 */
sealed interface ProfileLookPick {
    data class Bundled(val slug: String) : ProfileLookPick
    data class Photo(val id: String) : ProfileLookPick
}

/** One local choice and the synced [name] + [syncedKey] (avatar or background key) it was saved over. */
data class ProfileLookChoice(
    val pick: ProfileLookPick,
    val name: String?,
    val syncedKey: String?
)

/** A profile's stored look entry; either part may be absent. */
data class ProfileLookEntry(
    val avatar: ProfileLookChoice? = null,
    val background: ProfileLookChoice? = null
) {
    companion object {
        /** The entry to store after saving [saved]: the local picks over its synced key, or null (clear). */
        fun of(saved: UserProfile, avatar: ProfileLookPick?, background: ProfileLookPick?): ProfileLookEntry? {
            if (avatar == null && background == null) return null
            val key = syncedLookKey(saved)
            return ProfileLookEntry(
                avatar = avatar?.let { ProfileLookChoice(it, key.name, key.avatar) },
                background = background?.let { ProfileLookChoice(it, key.name, key.background) }
            )
        }
    }
}

/**
 * What a local pick can resolve against: the My photos ids (a removed photo resolves to the synced
 * value, D5) and the bundled avatar / background slugs (an unknown slug likewise).
 */
data class LocalLookSets(
    val photoIds: Set<String>,
    val bundledAvatars: Set<String>,
    val bundledBackgrounds: Set<String>
)

/** The synced fields a local choice is compared against, normalised (trim, blank -> null). */
data class SyncedLookKey(val name: String?, val avatar: String?, val background: String?)

/** The avatar fields profile sync pushes: the URL when non-blank, else the id (never both). */
data class SyncedAvatar(val id: String?, val url: String?)

/** The background fields profile sync pushes. */
data class SyncedBackground(val id: String?, val url: String?)

internal fun String?.normalizedLookField(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** One home for the avatar push mapping (`ProfileSyncService.pushToRemote`) and its look key. */
fun UserProfile.syncedAvatar(): SyncedAvatar {
    val url = avatarUrl.normalizedLookField()
    return SyncedAvatar(id = if (url == null) avatarId.normalizedLookField() else null, url = url)
}

/** One home for the background push mapping (`ProfileSyncService.pushToRemote`) and its look key. */
fun UserProfile.syncedBackground(): SyncedBackground =
    SyncedBackground(id = profileBackgroundId.normalizedLookField(), url = profileBackgroundUrl.normalizedLookField())

/** The normalised synced key of [profile]: name, avatar (URL else id), background (URL else id). */
fun syncedLookKey(profile: UserProfile): SyncedLookKey {
    val avatar = profile.syncedAvatar()
    val background = profile.syncedBackground()
    return SyncedLookKey(
        name = profile.name.normalizedLookField(),
        avatar = avatar.url ?: avatar.id,
        background = background.url ?: background.id
    )
}

private fun ProfileLookPick.isAvailable(photoIds: Set<String>, bundled: Set<String>): Boolean = when (this) {
    is ProfileLookPick.Bundled -> slug in bundled
    is ProfileLookPick.Photo -> id in photoIds
}

/** [choice]'s pick while its snapshot equals [name] + [syncedKey] and the pick is available, else null. */
private fun activePick(
    choice: ProfileLookChoice?,
    name: String?,
    syncedKey: String?,
    photoIds: Set<String>,
    bundled: Set<String>
): ProfileLookPick? {
    if (choice == null || choice.name != name || choice.syncedKey != syncedKey) return null
    return choice.pick.takeIf { it.isAvailable(photoIds, bundled) }
}

/** The entry's avatar pick when it still applies to [profile] and is available, else null (synced shows). */
fun ProfileLookEntry?.activeAvatar(profile: UserProfile, sets: LocalLookSets): ProfileLookPick? {
    val key = syncedLookKey(profile)
    return activePick(this?.avatar, key.name, key.avatar, sets.photoIds, sets.bundledAvatars)
}

/** The entry's background pick when it still applies to [profile] and is available, else null. */
fun ProfileLookEntry?.activeBackground(profile: UserProfile, sets: LocalLookSets): ProfileLookPick? {
    val key = syncedLookKey(profile)
    return activePick(this?.background, key.name, key.background, sets.photoIds, sets.bundledBackgrounds)
}

/**
 * The profiles whose look shows My photo [photoId] now (avatar or background choice that applies),
 * in [profiles] order: the hold panel's "Used by" (Spec 44 stage 7).
 */
fun photoUsers(
    photoId: String,
    profiles: List<UserProfile>,
    entries: Map<Int, ProfileLookEntry>,
    sets: LocalLookSets
): List<UserProfile> {
    val photo = ProfileLookPick.Photo(photoId)
    return profiles.filter { profile ->
        val entry = entries[profile.id]
        entry.activeAvatar(profile, sets) == photo || entry.activeBackground(profile, sets) == photo
    }
}

/** What a profile's avatar shows: its synced image URL, or a local pick. */
sealed interface LookAvatar {
    data class Synced(val url: String) : LookAvatar
    data class Local(val pick: ProfileLookPick) : LookAvatar
}

/**
 * The synced avatar's image ([syncedAvatar]): the profile's own URL, else the catalog image of its id
 * ([catalogImageUrl]; a catalog not loaded yet gives none, the initial shows).
 */
fun syncedAvatarImageUrl(profile: UserProfile, catalogImageUrl: (String) -> String?): String? {
    val avatar = profile.syncedAvatar()
    return avatar.url ?: avatar.id?.let(catalogImageUrl)
}

/** The one avatar resolver (Who's watching, the top bar, the editor): a local pick that applies wins. */
fun resolveLookAvatar(
    profile: UserProfile,
    entry: ProfileLookEntry?,
    sets: LocalLookSets,
    catalogImageUrl: (String) -> String?
): LookAvatar? =
    entry.activeAvatar(profile, sets)?.let(LookAvatar::Local)
        ?: syncedAvatarImageUrl(profile, catalogImageUrl)?.let(LookAvatar::Synced)

/** [resolveProfileBackgroundSelection] with the local look: a local background that applies wins. */
fun resolveLookBackground(
    profile: UserProfile?,
    entry: ProfileLookEntry?,
    sets: LocalLookSets
): ProfileBackgroundSelection? {
    if (profile == null) return null
    entry.activeBackground(profile, sets)?.let { return ProfileBackgroundSelection.Local(it) }
    return resolveProfileBackgroundSelection(profile)
}
