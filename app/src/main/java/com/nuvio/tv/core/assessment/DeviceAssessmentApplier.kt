package com.nuvio.tv.core.assessment

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.core.player.MemoryBudget
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Stage 2 apply path. Deliberate design choices:
 *
 *  - Writes go through the EXISTING public setters, never raw preference
 *    edits: each setter already carries its own coercions and side effects,
 *    so a sequential apply is behaviourally identical to a user flipping
 *    the same switches by hand, in a known-good order.
 *  - Order is load-bearing: setNuvioPerformanceModeEnabled(true) rewrites
 *    the whole buffer/parallel set as a preset, so it goes FIRST and all
 *    buffer/parallel values are written after it. Min buffer is written
 *    before max because setBufferMaxBufferMs clamps to >= the stored min;
 *    when apply lowers max below a min it isn't otherwise touching, the
 *    min is lowered with it (counted as a write).
 *  - Before the first write, the full touchable field set is captured from
 *    the live PlayerSettings and persisted device-wide as a JSON snapshot
 *    stamped with its time and [ChangeSource] (the Undo row's label);
 *    revert restores every captured field and clears the snapshot. A second
 *    apply overwrites the snapshot: revert always undoes the MOST RECENT
 *    apply. The snapshot/revert FIELD SET lives in ONE manifest
 *    ([SNAPSHOT_FIELDS]) that both directions walk — G6 2026-08-30,
 *    previously two hand-maintained 32-line lists that had already drifted
 *    once (the guarded late-added keys); apply() itself stays hand-ordered
 *    because its order and inter-field rules are load-bearing.
 *  - Only plan fields (null = untouched) and the selected assessment intent
 *    are ever written; every field is a device key (Spec 33 D1), so an apply on
 *    one profile applies to all. VERIFY-tier rows never reach the plan by construction.
 */
object DeviceAssessmentApplier {

    data class ApplyOutcome(val writtenCount: Int)

    /**
     * What wrote the change the undo slot holds (Spec 33 D5: the Undo row names it). Stored as
     * [key] in the snapshot's "source"; snapshots from before D5 carry none.
     */
    enum class ChangeSource(val key: String) {
        FIRST_LAUNCH("first_launch"),
        SETUP_AGAIN("setup_again"),
        NETWORK_TEST("network_test"),
        DEVICE_ASSESSMENT("device_assessment"),
        SHIELD_PRESET("shield_preset");

        companion object {
            fun fromKey(key: String?): ChangeSource? = entries.firstOrNull { it.key == key }
        }
    }

    /** The stored undo slot: what wrote it and when; either may be unknown (older snapshots). */
    data class UndoSlot(val source: ChangeSource?, val atMs: Long?)

    /** Null when there is no snapshot or it does not parse (revert would clear it, restoring nothing). */
    fun undoSlotOf(json: String?): UndoSlot? {
        val snap = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return UndoSlot(
            source = ChangeSource.fromKey(snap.optString(SOURCE_KEY).takeIf { it.isNotEmpty() }),
            atMs = snap.optLong(TIMESTAMP_KEY, 0L).takeIf { it > 0L }
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun apply(
        dataStore: PlayerSettingsDataStore,
        plan: AssessmentApplyPlan,
        profile: IntentProfileOption?,
        safeLimitMb: Int,
        source: ChangeSource
    ): ApplyOutcome {
        val before = dataStore.playerSettings.first()
        dataStore.setAssessmentRevertSnapshot(snapshotJson(before, source))

        var written = 0
        suspend fun step(block: suspend () -> Unit) {
            block()
            written += 1
        }

        // 1. Performance mode first: enabling it rewrites the buffer set.
        plan.nuvioPerformanceModeEnabled?.let { step { dataStore.setNuvioPerformanceModeEnabled(it) } }
        // 2. Masters and budget mode.
        plan.bufferEngineEnabled?.let { step { dataStore.setBufferEngineEnabled(it) } }
        plan.parallelNetworkEnabled?.let { step { dataStore.setParallelNetworkEnabled(it) } }
        plan.bufferBudgetManaged?.let { step { dataStore.setBufferBudgetManaged(it) } }
        // 3. Slider range before the value that needs it.
        plan.allowLargeTargetBuffer?.let { step { dataStore.setAllowLargeTargetBuffer(it) } }
        plan.targetBufferSizeMb?.let { step { dataStore.setBufferTargetSizeMb(it) } }
        // 4. Profile trio (min BEFORE max), then the max cap.
        profile?.let {
            step { dataStore.setBufferMinBufferMs(it.minBufferMs) }
            step { dataStore.setBufferForPlaybackMs(it.initialBufferMs) }
            step { dataStore.setBufferForPlaybackAfterRebufferMs(it.rebufferMs) }
        }
        plan.maxBufferMs?.let { newMax ->
            // Max setter clamps to >= stored min; honour the intent by lowering
            // the min with it. Compare against the min as stored NOW, not the
            // pre-apply snapshot: step 1 may have rewritten it (enabling
            // performance mode presets a 200 s min above any <= 120 s max).
            if (profile == null &&
                dataStore.playerSettings.first().bufferSettings.minBufferMs > newMax
            ) {
                step { dataStore.setBufferMinBufferMs(newMax) }
            }
            step { dataStore.setBufferMaxBufferMs(newMax) }
        }
        // 4b. Back buffer (part of the buffer set; the setter's cap widens
        // under native memory, so write after perf mode).
        plan.backBufferDurationMs?.let { step { dataStore.setBufferBackBufferDurationMs(it) } }
        plan.retainBackBufferFromKeyframe?.let {
            step { dataStore.setBufferRetainBackBufferFromKeyframe(it) }
        }
        // 5. Parallel pipe.
        plan.useParallelConnections?.let { step { dataStore.setUseParallelConnections(it) } }
        plan.parallelConnectionCount?.let { step { dataStore.setParallelConnectionCount(it) } }
        plan.parallelChunkSizeKb?.let { step { dataStore.setParallelChunkSizeKb(it) } }
        plan.enableHttp2?.let { step { dataStore.setEnableHttp2(it) } }
        // 6. Display, DV, audio route.
        plan.autoSwitchInternalPlayerOnError?.let {
            step { dataStore.setAutoSwitchInternalPlayerOnError(it) }
        }
        plan.frameRateMatchingMode?.let { step { dataStore.setFrameRateMatchingMode(it) } }
        plan.resolutionMatchingEnabled?.let { step { dataStore.setResolutionMatchingEnabled(it) } }
        plan.dv7HandlingMode?.let { step { dataStore.setDv7HandlingMode(it) } }
        plan.forceOpticalPassthrough?.let { step { dataStore.setForceOpticalPassthrough(it) } }
        plan.deniedCodecHandling?.let { step { dataStore.setDeniedCodecHandling(it) } }
        // Per-format passthrough switches; MAT last so it never outlives TrueHD.
        plan.allowAc3Passthrough?.let { step { dataStore.setAllowAc3Passthrough(it) } }
        plan.allowEac3Passthrough?.let { step { dataStore.setAllowEac3Passthrough(it) } }
        plan.allowTrueHdPassthrough?.let { step { dataStore.setAllowTrueHdPassthrough(it) } }
        plan.allowDtsPassthrough?.let { step { dataStore.setAllowDtsPassthrough(it) } }
        plan.allowDtsHdPassthrough?.let { step { dataStore.setAllowDtsHdPassthrough(it) } }
        plan.matPassthroughEnabled?.let { step { dataStore.setMatPassthroughEnabled(it) } }

        // Pre-commit safe-limit re-check (design: "recommend to safe"). Read
        // the settled state back and confirm the native footprint - target
        // buffer plus parallel overhead - fits the safe budget; clamp the
        // target down to the largest fitting step if not. Reading BACK rather
        // than trusting the plan is deliberate: enabling performance mode
        // presets target = safeLimitMb minus its own 4c/16 overhead (largest
        // 25 MB step), and the plan only
        // rewrites a field when its recommendation differs from the PRE-apply
        // value, so a recommendation equal to the pre-preset value can leave
        // the preset's over-budget target in place. The MIN_BUFFER_MB floor is
        // preserved: on a device where overhead alone already exceeds safe (the
        // engine's deliberate constrained-device fallback) the clamp settles on
        // MIN_BUFFER_MB and does not fight it. No-op on the normal path, where
        // the engine already sized the target to safe minus overhead.
        val after = dataStore.playerSettings.first()
        val afterOverheadMb = if (after.useParallelConnections) {
            MemoryBudget.parallelOverheadMb(
                after.parallelConnectionCount,
                MemoryBudget.chunkMbOf(after.parallelChunkSizeKb)
            )
        } else {
            0
        }
        val maxSafeTargetMb = MemoryBudget.maxSafeTargetMb(safeLimitMb, afterOverheadMb)
        if (after.bufferSettings.targetBufferSizeMb > maxSafeTargetMb) {
            step { dataStore.setBufferTargetSizeMb(maxSafeTargetMb) }
        }

        return ApplyOutcome(writtenCount = written)
    }

    /** Restores every captured field; returns false when no snapshot exists. */
    suspend fun revert(dataStore: PlayerSettingsDataStore): Boolean {
        val json = dataStore.assessmentRevertSnapshot.first() ?: return false
        val snap = runCatching { JSONObject(json) }.getOrNull() ?: run {
            dataStore.setAssessmentRevertSnapshot(null)
            return false
        }

        // Manifest order keeps minBufferMs ahead of maxBufferMs, so the
        // mutual clamps in the buffer setters settle on the captured values
        // (the snapshot is internally consistent: min <= max). The
        // forPlayback setters clamp against absolute constants only, so their
        // position relative to maxBufferMs (which the old hand-list ordered
        // differently) is behaviour-neutral — revisit if they ever cross-clamp.
        // Every key is guarded uniformly: persisted snapshots written before a
        // field was added simply skip it instead of aborting the whole restore.
        SNAPSHOT_FIELDS.forEach { it.restore(dataStore, snap) }

        dataStore.setAssessmentRevertSnapshot(null)
        return true
    }

    /** Typed shapes the manifest needs; JSON keeps native types. */
    private sealed interface SnapshotField {
        val key: String
        fun put(settings: PlayerSettings, json: JSONObject)
        suspend fun restore(dataStore: PlayerSettingsDataStore, json: JSONObject)
    }

    private class BoolField(
        override val key: String,
        val get: PlayerSettings.() -> Boolean,
        val set: suspend PlayerSettingsDataStore.(Boolean) -> Unit
    ) : SnapshotField {
        override fun put(settings: PlayerSettings, json: JSONObject) {
            json.put(key, settings.get())
        }

        override suspend fun restore(dataStore: PlayerSettingsDataStore, json: JSONObject) {
            if (json.has(key)) dataStore.set(json.getBoolean(key))
        }
    }

    private class IntField(
        override val key: String,
        val get: PlayerSettings.() -> Int,
        val set: suspend PlayerSettingsDataStore.(Int) -> Unit
    ) : SnapshotField {
        override fun put(settings: PlayerSettings, json: JSONObject) {
            json.put(key, settings.get())
        }

        override suspend fun restore(dataStore: PlayerSettingsDataStore, json: JSONObject) {
            if (json.has(key)) dataStore.set(json.getInt(key))
        }
    }

    /** Enum persisted as name; restore via valueOf. */
    private class EnumField<T : Enum<T>>(
        override val key: String,
        val get: PlayerSettings.() -> T,
        val set: suspend PlayerSettingsDataStore.(T) -> Unit,
        val valueOf: (String) -> T
    ) : SnapshotField {
        override fun put(settings: PlayerSettings, json: JSONObject) {
            json.put(key, settings.get().name)
        }

        override suspend fun restore(dataStore: PlayerSettingsDataStore, json: JSONObject) {
            if (json.has(key)) dataStore.set(valueOf(json.getString(key)))
        }
    }

    /** DeniedCodecHandling stores under legacy-tolerant names (fromStoredString). */
    private class DeniedCodecField(
        override val key: String,
        val get: PlayerSettings.() -> DeniedCodecHandling,
        val set: suspend PlayerSettingsDataStore.(DeniedCodecHandling) -> Unit
    ) : SnapshotField {
        override fun put(settings: PlayerSettings, json: JSONObject) {
            json.put(key, settings.get().name)
        }

        override suspend fun restore(dataStore: PlayerSettingsDataStore, json: JSONObject) {
            if (json.has(key)) {
                dataStore.set(DeniedCodecHandling.fromStoredString(json.getString(key)))
            }
        }
    }

    /**
     * ONE home for the snapshot/revert field set, in the load-bearing order
     * (perf mode first; minBufferMs before maxBufferMs). A NEW
     * AssessmentApplyPlan field MUST gain an entry here or it will apply
     * without being revert-able — DeviceAssessmentApplierTest pins the key
     * set and the plan-coverage invariant. dv5ToDv81Enabled and
     * stripHdr10PlusSei have no plan field (advisory-only rows) but stay here:
     * the key set is the persisted snapshot format, and restoring them is
     * harmless.
     */
    private val SNAPSHOT_FIELDS: List<SnapshotField> = listOf(
        BoolField("nuvioPerformanceModeEnabled", { nuvioPerformanceModeEnabled }, { setNuvioPerformanceModeEnabled(it) }),
        BoolField("bufferEngineEnabled", { bufferEngineEnabled }, { setBufferEngineEnabled(it) }),
        BoolField("parallelNetworkEnabled", { parallelNetworkEnabled }, { setParallelNetworkEnabled(it) }),
        BoolField("bufferBudgetManaged", { bufferBudgetManaged }, { setBufferBudgetManaged(it) }),
        BoolField("allowLargeTargetBuffer", { allowLargeTargetBuffer }, { setAllowLargeTargetBuffer(it) }),
        IntField("targetBufferSizeMb", { bufferSettings.targetBufferSizeMb }, { setBufferTargetSizeMb(it) }),
        IntField("minBufferMs", { bufferSettings.minBufferMs }, { setBufferMinBufferMs(it) }),
        IntField("maxBufferMs", { bufferSettings.maxBufferMs }, { setBufferMaxBufferMs(it) }),
        IntField("backBufferDurationMs", { bufferSettings.backBufferDurationMs }, { setBufferBackBufferDurationMs(it) }),
        BoolField("retainBackBufferFromKeyframe", { bufferSettings.retainBackBufferFromKeyframe }, { setBufferRetainBackBufferFromKeyframe(it) }),
        IntField("bufferForPlaybackMs", { bufferSettings.bufferForPlaybackMs }, { setBufferForPlaybackMs(it) }),
        IntField("bufferForPlaybackAfterRebufferMs", { bufferSettings.bufferForPlaybackAfterRebufferMs }, { setBufferForPlaybackAfterRebufferMs(it) }),
        BoolField("useParallelConnections", { useParallelConnections }, { setUseParallelConnections(it) }),
        IntField("parallelConnectionCount", { parallelConnectionCount }, { setParallelConnectionCount(it) }),
        IntField("parallelChunkSizeKb", { parallelChunkSizeKb }, { setParallelChunkSizeKb(it) }),
        BoolField("enableHttp2", { enableHttp2 }, { setEnableHttp2(it) }),
        EnumField("frameRateMatchingMode", { frameRateMatchingMode }, { setFrameRateMatchingMode(it) }, FrameRateMatchingMode::valueOf),
        BoolField("resolutionMatchingEnabled", { resolutionMatchingEnabled }, { setResolutionMatchingEnabled(it) }),
        EnumField("dv7HandlingMode", { dv7HandlingMode }, { setDv7HandlingMode(it) }, Dv7HandlingMode::valueOf),
        BoolField("dv5ToDv81Enabled", { dv5ToDv81Enabled }, { setDv5ToDv81Enabled(it) }),
        BoolField("stripHdr10PlusSei", { stripHdr10PlusSei }, { setStripHdr10PlusSei(it) }),
        BoolField("forceOpticalPassthrough", { forceOpticalPassthrough }, { setForceOpticalPassthrough(it) }),
        DeniedCodecField("deniedCodecHandling", { deniedCodecHandling }, { setDeniedCodecHandling(it) }),
        BoolField("allowAc3Passthrough", { allowAc3Passthrough }, { setAllowAc3Passthrough(it) }),
        BoolField("allowEac3Passthrough", { allowEac3Passthrough }, { setAllowEac3Passthrough(it) }),
        BoolField("allowTrueHdPassthrough", { allowTrueHdPassthrough }, { setAllowTrueHdPassthrough(it) }),
        BoolField("allowDtsPassthrough", { allowDtsPassthrough }, { setAllowDtsPassthrough(it) }),
        BoolField("allowDtsHdPassthrough", { allowDtsHdPassthrough }, { setAllowDtsHdPassthrough(it) }),
        BoolField("matPassthroughEnabled", { matPassthroughEnabled }, { setMatPassthroughEnabled(it) }),
        BoolField("autoSwitchInternalPlayerOnError", { autoSwitchInternalPlayerOnError }, { setAutoSwitchInternalPlayerOnError(it) })
    )

    /** Keys of [SNAPSHOT_FIELDS], in order — test-facing view of the manifest. */
    internal val SNAPSHOT_KEYS: List<String> get() = SNAPSHOT_FIELDS.map { it.key }

    private const val TIMESTAMP_KEY = "timestampMs"
    private const val SOURCE_KEY = "source"

    /** Restore walks [SNAPSHOT_FIELDS] only, so [TIMESTAMP_KEY] and [SOURCE_KEY] never restore. */
    internal fun snapshotJson(
        s: PlayerSettings,
        source: ChangeSource,
        nowMs: Long = System.currentTimeMillis()
    ): String = JSONObject().apply {
        put(TIMESTAMP_KEY, nowMs)
        put(SOURCE_KEY, source.key)
        SNAPSHOT_FIELDS.forEach { it.put(s, this) }
    }.toString()
}
