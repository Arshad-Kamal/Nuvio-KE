package com.nuvio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    // No in-app reader since the plugin runtime was removed (DRDYK A2.1). Kept ONLY so the
    // Supabase profile push/pull payload stays byte-compatible for other devices on the
    // account; do not drop it in a dead-field sweep.
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null
) {
    val isPrimary: Boolean get() = id == 1
}
