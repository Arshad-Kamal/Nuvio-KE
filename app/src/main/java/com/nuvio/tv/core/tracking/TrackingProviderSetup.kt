package com.nuvio.tv.core.tracking

import androidx.annotation.StringRes
import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.server.PasteKeyPageStrings
import kotlinx.coroutines.flow.StateFlow

/** One numbered step of the setup panel (K2): [text] formatted with [boldArgs], which are drawn bold. */
data class TrackingSetupStep(@StringRes val text: Int, val boldArgs: List<Int> = emptyList())

/** A tracker's own words for its setup (sandbox `spec-106-simkl` v2 K1-K5); the generic ones live with the panel. */
data class TrackingSetupText(
    /** K1 row line while nothing is set up ("Needs your own Simkl app id first"). */
    @StringRes val rowLine: Int,
    /** What is pasted ("Simkl app id"): K4's head, K5's row title, the remove confirm. */
    @StringRes val valueName: Int,
    val steps: List<TrackingSetupStep>,
    /** The QR's title before a value is saved (K2) and after (K4, K5: replace). */
    @StringRes val phoneTitle: Int,
    @StringRes val replaceTitle: Int,
    /** K4: what comes next once a value is saved. */
    @StringRes val savedBody: Int,
    /** The remove confirm's body (K5: "Every profile on this TV is signed out of Simkl."). */
    @StringRes val removeBody: Int,
    val phonePage: PasteKeyPageStrings
)

/**
 * A tracker that needs something pasted per TV before anyone can connect (Spec 106 D6-D10: bring your own Simkl app
 * id). The Trackers hub draws it generically: the row reads Set up until [savedHint] is set (K1), the setup panel shows
 * the steps and a phone page (K2/K4), the connected panel a row to replace or remove it (K5). A provider with nothing
 * to set up has no [TrackingProvider.setup]. While nothing is saved the provider's `isAuthenticated` stays false.
 */
interface TrackingProviderSetup {
    /** The saved value's last four characters, never the value; null = not set up on this TV. */
    val savedHint: StateFlow<String?>

    val text: TrackingSetupText

    /** The first of the phone page's ten ports (each page has its own range, 8160-8169 Simkl). */
    val phonePagePort: Int

    /**
     * A value pasted on the phone (K3), on the page's handler thread: checked with the service, then saved for this
     * TV. Any change of the saved value (a first one too) signs every profile out of the tracker first. [stillWanted] false (the
     * page closed meanwhile) saves nothing.
     */
    suspend fun submit(value: String, stillWanted: () -> Boolean): PasteCheck

    /** K5 Remove: signs every profile on this TV out of the tracker, then forgets the value. */
    suspend fun remove()
}
