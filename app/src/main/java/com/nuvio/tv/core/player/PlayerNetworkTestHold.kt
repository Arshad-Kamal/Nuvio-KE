package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.assessment.NETWORK_TEST_STEPS
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceNetworkTestRunner
import com.nuvio.tv.core.assessment.inAutomaticShape
import com.nuvio.tv.core.assessment.qualifiesForNetworkRule
import com.nuvio.tv.core.assessment.serviceKeyOf
import com.nuvio.tv.core.assessment.shouldHoldForNetworkTest
import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Spec 33 D6 (stage t-hold): holds this start for the per-service network test when it is due
 * ([shouldHoldForNetworkTest]). Runs in initializePlayer after the engine is resolved and before
 * any LoadControl or player is built, so the sweep's buffers never sit beside a player; a stream
 * that would start beside a live player (the reuse path) never holds. Back
 * (PlayerEvent.OnSkipNetworkTest) cancels the test and the film plays on the current settings;
 * the next qualifying film holds again. A stream holds at most once per controller (re-inits of
 * the same URL on failover or rebuild do not repeat it), whatever the outcome; nor does another URL
 * of a service already held this session (Spec 57 D4: a failover after a skip held again).
 *
 * Returns the test's result when it stored a table, or only an alias to a service measured on this
 * link already (Spec 59 D3, [ServiceNetworkTestRunner.Result.measured] false): the caller re-reads
 * the settings (the test may have written them), re-runs the per-play rule and, when the test
 * measured, shows the result (Spec 33 D7). Null when no test ran, it was skipped or failed, or it
 * measured nothing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun PlayerRuntimeController.holdForNetworkTestIfDue(
    saved: PlayerSettings,
    url: String,
    headers: Map<String, String>
): ServiceNetworkTestRunner.Result? {
    if (url == networkTestHeldUrl || _exoPlayer != null) return null
    if (!qualifiesForNetworkRule(url, currentVideoSize, isTorrentStream)) return null
    val requestKey = serviceKeyOf(url) ?: return null
    if (requestKey in networkTestHeldServices) return null
    val tables = ServiceNetworkTables.decode(playerSettingsDataStore.serviceNetworkTablesJson.first())
    val transport = NetworkTransportReader.current(context)
    if (!shouldHoldForNetworkTest(tables, url, inAutomaticShape(saved), transport)) return null
    networkTestHeldUrl = url
    networkTestHeldServices += requestKey

    fun show(hold: NetworkTestHold) {
        _uiState.update { it.copy(networkTestHold = hold) }
        // The screen writes the user-facing lines from networkTestHold; this feeds the trace.
        setLoadingStatus(
            phase = "network_test",
            message = null,
            progress = hold.step.toFloat() / NETWORK_TEST_STEPS,
            detail = "service=${hold.service} step=${hold.step}"
        )
    }
    show(NetworkTestHold(service = tables.aliases[requestKey] ?: requestKey))
    Log.i(PlayerRuntimeController.TAG, "NETWORK_TEST: hold service=$requestKey transport=$transport")

    val result = try {
        runSkippable(register = { networkTestJob = it }) {
            ServiceNetworkTestRunner.run(
                context = context,
                dataStore = playerSettingsDataStore,
                url = url,
                headers = headers,
                transport = transport,
                onService = { service -> _uiState.value.networkTestHold?.let { show(it.copy(service = service)) } },
                onStep = { step -> _uiState.value.networkTestHold?.let { show(it.copy(step = step)) } },
                // The sweep is done (the writes finish and this film uses them), or a known service's
                // alias is stored: Back no longer skips.
                onWriting = { networkTestJob = null }
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A test failure never blocks the film: it plays on the current settings.
        Log.w(PlayerRuntimeController.TAG, "NETWORK_TEST: failed ${e.javaClass.simpleName}")
        null
    } finally {
        // A superseding init of another stream may hold by now; leave its hold alone.
        if (networkTestHeldUrl == url) {
            networkTestJob = null
            _uiState.update { it.copy(networkTestHold = null) }
        }
    }
    Log.i(
        PlayerRuntimeController.TAG,
        if (result == null) "NETWORK_TEST: no table (skipped, failed or nothing measured)"
        else if (!result.measured) "NETWORK_TEST: known service=${result.serviceKey} alias=$requestKey (no sweep)"
        else {
            "NETWORK_TEST: done service=${result.serviceKey} rows=${result.table.rows.size} " +
                "best=${"%.1f".format(result.table.rows.maxOf { it.mbps })} h2=${result.table.http2} " +
                "settingsWritten=${result.settingsWritten}"
        }
    )
    return result
}

/**
 * Spec 33 D7: the test's notice has been shown. A method, not a PlayerEvent: every event cancels
 * the pause overlay (Spec 25 P-6), and the notice ends on its own timer.
 */
internal fun PlayerRuntimeController.consumeNetworkTestNotice() {
    _uiState.update { it.copy(networkTestNoticePending = false) }
}

/**
 * Spec 33 D7, at each initializePlayer before the hold: the test's notice and pause chip belong to
 * the stream it ran on ([heldUrl]); a re-init of that stream (failover, rebuild) keeps them, any
 * other [url] clears them.
 */
internal fun PlayerUiState.withNetworkTestResultFor(url: String, heldUrl: String?): PlayerUiState =
    if (url == heldUrl) this else copy(networkTestResult = null, networkTestNoticePending = false)

/**
 * Runs [block] as a child that can be cancelled on its own through the job handed to [register]
 * (Back during the hold): that returns null and the caller carries on. Cancelling the caller
 * itself propagates (and cancels [block]); a failure of [block] is rethrown.
 */
internal suspend fun <T> runSkippable(register: (Job) -> Unit, block: suspend () -> T): T? = coroutineScope {
    val child = async { block() }
    register(child)
    try {
        child.await()
    } catch (e: CancellationException) {
        // The caller itself was cancelled: propagate. Else only the child was (skipped).
        ensureActive()
        null
    }
}
