package com.nuvio.tv.core.assessment

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier.ChangeSource
import com.nuvio.tv.core.network.StreamSpeedTester
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Whether a play holds for the network test before the player is built (Spec 33 D6, decided
 * 2026-10-18): a qualifying stream ([qualifiesForNetworkRule], checked by the caller) while the
 * per-film rule is active (Automatic network on and [ruleShape] = [inAutomaticShape]; with the
 * rule off the result would change nothing for this film), and its service has no table yet,
 * is due the re-test Settings' "Run again" queued, or was tested on another link (Wi-Fi /
 * Ethernet switch, or a table tested on an unknown link now on a known one). [transport] OTHER
 * (no network, or neither link) never re-tests by itself. A URL without a service key never holds.
 */
fun shouldHoldForNetworkTest(
    tables: ServiceNetworkTables,
    url: String,
    ruleShape: Boolean,
    transport: NetworkTransport
): Boolean {
    if (!tables.automatic || !ruleShape || serviceKeyOf(url) == null) return false
    return tables.tableKeyFor(url)?.let { tables.currentTable(it, transport) } == null
}

/**
 * Spec 59 D3: a held stream from request domain [requestKey] whose link leads to another service
 * [servingKey] (found by the test's probe) with a current table on [transport]: [withTest] with that
 * table, so [requestKey] gains the alias and nothing is measured again. Null when the service has no
 * current table, or the stream is served from its own domain (no redirect, or a probe that got no
 * answer and fell back to it): the sweep runs, as before D3.
 */
fun ServiceNetworkTables.withKnownService(
    requestKey: String,
    servingKey: String,
    transport: NetworkTransport
): ServiceNetworkTables? =
    if (requestKey == servingKey) null
    else currentTable(servingKey, transport)?.let { withTest(requestKey, servingKey, it) }

/**
 * [table] stored under the serving service [servingKey]; the request domain [requestKey] (often
 * the addon's) aliases to it, or loses a stale alias when the stream is served from its own
 * domain. [servingKey] never keeps an alias of its own (it now has its table). An addon that
 * proxies several services keeps the alias of the last one tested.
 *
 * A table due a re-test that no stream reaches any more goes: the one [requestKey] pointed at
 * before, and [requestKey]'s own once it aliases elsewhere, unless another alias still leads there.
 * This request domain now leads to a service whose table is current (measured now, or already,
 * Spec 59 D3: the user moved services), and a direct play from the old one would re-test anyway.
 * Kept, it would leave Settings' "Run again" pending for good.
 */
fun ServiceNetworkTables.withTest(requestKey: String, servingKey: String, table: ServiceTable): ServiceNetworkTables {
    val nextAliases = (aliases - servingKey - requestKey)
        .let { if (requestKey == servingKey) it else it + (requestKey to servingKey) }
    val unreached = listOfNotNull(aliases[requestKey], requestKey).filter { it != servingKey }
    val orphaned = unreached.filter { key ->
        key !in nextAliases.values && tables[key]?.let(::retestDue) == true
    }.toSet()
    return copy(tables = tables - orphaned + (servingKey to table), aliases = nextAliases)
}

/**
 * The settings a finished test writes (Spec 33 D6): the network fields [savedNetworkAfterTest]
 * changes, each only when it differs (null fields stay untouched); buffer values never change.
 * The saved values serve the films the per-film rule cannot judge. Null when nothing changes or
 * no row fits.
 */
fun networkTestPlan(table: ServiceTable, saved: PlayerSettings, limits: DeviceLimits): AssessmentApplyPlan? {
    val before = saved.toPlaybackConfig()
    val after = savedNetworkAfterTest(table, before, limits) ?: return null
    fun <T> changed(get: PlaybackConfig.() -> T): T? = after.get().takeIf { it != before.get() }
    return AssessmentApplyPlan(
        useParallelConnections = changed { parallel },
        parallelConnectionCount = changed { connections },
        parallelChunkSizeKb = changed { chunkKb },
        enableHttp2 = changed { http2 }
    ).takeIf { it != AssessmentApplyPlan() }
}

/** Rows a full test can run: the baseline plus the sweep's pass cap (the HTTP/2 A/B is inside it). */
const val NETWORK_TEST_STEPS = 1 + StreamSweepEngine.MAX_PARALLEL_PASSES

/** The step the hold shows for [step] (0 = still probing): 1..[NETWORK_TEST_STEPS]. */
fun networkTestDisplayStep(step: Int): Int = step.coerceIn(1, NETWORK_TEST_STEPS)

/** A pass's 8 MB warm-up plus its 8 s window, rounded (StreamSpeedTester). */
private const val SECONDS_PER_STEP = 8

/** Below this many seconds left the hold says "less than a minute". */
private const val UNDER_A_MINUTE_S = 45

/**
 * Whole minutes left for the hold's ETA at [step] (the row now running), as if every step runs;
 * 0 = under a minute. The sweep often stops early (errs long); a rate-limited source's retry
 * pauses (up to 90 s in all) are not counted (errs short).
 */
fun networkTestMinutesLeft(step: Int): Int {
    val seconds = (NETWORK_TEST_STEPS - networkTestDisplayStep(step) + 1) * SECONDS_PER_STEP
    return if (seconds < UNDER_A_MINUTE_S) 0 else (seconds / 60.0).roundToInt().coerceAtLeast(1)
}

/**
 * Runs the per-service network test on a stream (Spec 33 D6): finds the serving service (the
 * content probe's post-redirect URL), runs the full sweep with no film target (every rung, verdict
 * FASTEST_NO_BITRATE), then with the rule active writes [networkTestPlan] through the applier
 * (undo label "The network test") and stores the table under that service with an alias from the
 * request domain. The table goes last, as the commit marker: a failed settings write leaves no
 * table, so the next qualifying film tests again. Cancelling it (Back) stops the sweep; once the
 * sweep is done the writes finish regardless.
 */
@UnstableApi
internal object ServiceNetworkTestRunner {

    /**
     * [settingsWritten] = the applier ran; after a sweep the table is stored either way. [measured]
     * false = the serving service had a current table: no sweep, only the alias was stored (Spec 59
     * D3); [table] is that stored table.
     */
    data class Result(
        val serviceKey: String,
        val table: ServiceTable,
        val settingsWritten: Boolean,
        val measured: Boolean
    )

    /**
     * [onService] gets the serving service key once the probe answers; [onStep] the 1-based row
     * the sweep starts running; [onWriting] fires once the sweep is done, before the writes (Back
     * no longer skips anything after it), or once a known service's alias is stored. Null when the
     * URL has no service key or the sweep measured nothing. A serving service measured on this link
     * already skips the sweep and stores the alias only ([withKnownService]).
     */
    suspend fun run(
        context: Context,
        dataStore: PlayerSettingsDataStore,
        url: String,
        headers: Map<String, String>,
        transport: NetworkTransport,
        onService: (String) -> Unit,
        onStep: (Int) -> Unit,
        onWriting: () -> Unit,
        nowMs: () -> Long = System::currentTimeMillis
    ): Result? {
        val requestKey = serviceKeyOf(url) ?: return null
        val servingKey = serviceKeyOf(StreamSpeedTester.getStreamContentLength(url, headers).finalUrl) ?: requestKey
        onService(servingKey)
        // Spec 59 D3, decided inside the atomic update (the read before it only spares a write ahead
        // of most sweeps): nothing applied there, the sweep runs.
        val stored = ServiceNetworkTables.decode(dataStore.serviceNetworkTablesJson.first())
        if (stored.withKnownService(requestKey, servingKey, transport) != null) {
            var known: ServiceTable? = null
            withContext(NonCancellable) {
                dataStore.updateServiceNetworkTablesJson { json ->
                    val next = ServiceNetworkTables.decode(json).withKnownService(requestKey, servingKey, transport)
                    known = next?.tables?.get(servingKey)
                    next?.encode() ?: json
                }
            }
            known?.let { table ->
                onWriting()
                return Result(servingKey, table, settingsWritten = false, measured = false)
            }
        }
        var step = 0
        val outcome = StreamSweepEngine.run(
            context = context,
            streamUrl = url,
            headers = headers,
            estimatedBitrate = null,
            onState = {},
            onPassAdded = {},
            onPassResult = { _, _, _ -> },
            onRunStarted = { onStep(++step) }
        )
        val table = tableFromSweep(outcome, transport, nowMs()) ?: return null
        onWriting()
        return withContext(NonCancellable) {
            val automatic = ServiceNetworkTables.decode(dataStore.serviceNetworkTablesJson.first()).automatic
            val saved = dataStore.playerSettings.first()
            val limits = playbackDeviceLimits(context)
            val plan = if (automatic && inAutomaticShape(saved)) networkTestPlan(table, saved, limits) else null
            plan?.let {
                DeviceAssessmentApplier.apply(dataStore, it, profile = null, safeLimitMb = limits.safeMb, source = ChangeSource.NETWORK_TEST)
            }
            dataStore.updateServiceNetworkTablesJson { json ->
                ServiceNetworkTables.decode(json).withTest(requestKey, servingKey, table).encode()
            }
            Result(servingKey, table, settingsWritten = plan != null, measured = true)
        }
    }
}
