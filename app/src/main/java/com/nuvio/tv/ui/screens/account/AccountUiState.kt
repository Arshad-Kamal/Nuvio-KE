package com.nuvio.tv.ui.screens.account

import android.graphics.Bitmap
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile

data class ProfileSyncStats(
    val profileId: Int,
    val profileName: String,
    val avatarColorHex: String,
    /** The local profile's avatar image, when this TV has the profile. */
    val avatarUrl: String? = null,
    val addons: Int = 0,
    val library: Int = 0,
    val watchProgress: Int = 0,
    val watchedItems: Int = 0
) {
    /** This TV's copy of the profile wins for name and avatar (the server's may lag a rename). */
    fun withLocal(local: UserProfile): ProfileSyncStats =
        copy(profileName = local.name, avatarColorHex = local.avatarColorHex, avatarUrl = local.avatarUrl)
}

data class AccountUiState(
    val authState: AuthState = AuthState.Loading,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Server counts per profile (the Account pane's Synced data table); null until read. */
    val syncOverview: List<ProfileSyncStats>? = null,
    val isSyncOverviewLoading: Boolean = false,
    val qrLogin: QrLogin = QrLogin.Idle,
    /** QR of the current session's web URL; set with Showing, cleared with Idle/Starting. */
    val qrLoginBitmap: Bitmap? = null
)
