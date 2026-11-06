package com.nuvio.tv.domain.model

sealed interface ProfileBackgroundSelection {
    data class Catalog(val id: String) : ProfileBackgroundSelection
    data class Custom(val url: String) : ProfileBackgroundSelection
    /** A TV-only background (Spec 44): bundled (44b) or from My photos (44c). */
    data class Local(val pick: ProfileLookPick) : ProfileBackgroundSelection
}

/** The synced background ([syncedBackground], the push's normalisation): the custom URL, else the catalog id. */
fun resolveProfileBackgroundSelection(profile: UserProfile?): ProfileBackgroundSelection? {
    if (profile == null) return null
    val background = profile.syncedBackground()
    background.url?.let { return ProfileBackgroundSelection.Custom(it) }
    return background.id?.let { ProfileBackgroundSelection.Catalog(it) }
}
