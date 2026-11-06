package com.nuvio.tv.core.assessment

import android.app.Activity
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier.ChangeSource
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * How this TV was last set up (Spec 33): Settings › This TV's summary row reads it, and the
 * first-launch welcome (D8) compares [fingerprint] against [DeviceFingerprint] of the box.
 * [Kind.KEPT] = an already-tuned install marked set up with nothing applied (decision Q3).
 */
@Serializable
data class DeviceSetupRecord(
    val fingerprint: String,
    val atMs: Long,
    val kind: Kind = Kind.FIRST_LAUNCH
) {
    @Serializable
    enum class Kind { FIRST_LAUNCH, AGAIN, KEPT }

    fun encode(): String = assessmentStoreJson.encodeToString(serializer(), this)

    companion object {
        /** Null or unparseable JSON reads as never set up. */
        fun decode(json: String?): DeviceSetupRecord? =
            json?.let { runCatching { assessmentStoreJson.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Runs the device setup (Spec 33): device facts -> [DeviceSetupFormula] (Shield overlay, fit
 * gate) -> [DeviceAssessmentApplier] with its undo snapshot, then records it. D5 calls it from
 * Settings › This TV "Set up this TV again"; D8's first launch ([firstLaunch]) reuses it.
 */
@UnstableApi
internal object DeviceSetupCoordinator {

    /** What the welcome card shows: the device facts and the settings after the setup (or as kept). */
    data class FirstLaunch(val device: DeviceAssessmentEngine.DeviceFacts, val settings: PlayerSettings)

    /**
     * The first-launch step (D8): null when this box is already recorded ([welcomeSetupFor]
     * NONE). Otherwise applies the setup (FIRST_LAUNCH) or, for an install tuned before the
     * setup existed, records KEPT and applies nothing; either way the record is written before
     * the card shows, so the card is offered once even if the app is closed on it. The writes run
     * NonCancellable: an apply cut before its record would read as KEEP on the next start.
     * [facts] = DeviceAssessmentEngine.gatherDeviceFacts (the caller reads them; tests pass any).
     */
    suspend fun firstLaunch(
        facts: DeviceAssessmentEngine.DeviceFacts,
        dataStore: PlayerSettingsDataStore,
        nowMs: Long = System.currentTimeMillis()
    ): FirstLaunch? {
        val settings = dataStore.playerSettings.first()
        val tuned = isAlreadyTuned(settings, hasUndoSnapshot = dataStore.assessmentRevertSnapshot.first() != null)
        val record = DeviceSetupRecord.decode(dataStore.deviceSetupRecordJson.first())
        val setup = welcomeSetupFor(record, facts.fingerprint, tuned)
        if (setup == WelcomeSetup.NONE) return null
        withContext(NonCancellable) {
            if (setup == WelcomeSetup.KEEP) {
                dataStore.setDeviceSetupRecordJson(
                    DeviceSetupRecord(facts.fingerprint, nowMs, DeviceSetupRecord.Kind.KEPT).encode()
                )
            } else {
                apply(facts, dataStore, DeviceSetupRecord.Kind.FIRST_LAUNCH, nowMs)
            }
        }
        return FirstLaunch(facts, dataStore.playerSettings.first())
    }

    /**
     * [activity] lets the display be read (frame-rate matching); null leaves AFR as it is.
     * [kind] is FIRST_LAUNCH or AGAIN (KEPT applies nothing: D8 records it without a setup).
     */
    suspend fun setUp(
        context: Context,
        activity: Activity?,
        dataStore: PlayerSettingsDataStore,
        kind: DeviceSetupRecord.Kind,
        nowMs: Long = System.currentTimeMillis()
    ): DeviceAssessmentApplier.ApplyOutcome {
        require(kind != DeviceSetupRecord.Kind.KEPT) { "KEPT records a setup that applied nothing" }
        return apply(DeviceAssessmentEngine.gatherDeviceFacts(context, activity), dataStore, kind, nowMs)
    }

    private suspend fun apply(
        facts: DeviceAssessmentEngine.DeviceFacts,
        dataStore: PlayerSettingsDataStore,
        kind: DeviceSetupRecord.Kind,
        nowMs: Long
    ): DeviceAssessmentApplier.ApplyOutcome {
        val setup = DeviceSetupFormula.setup(facts, dataStore.playerSettings.first())
        val outcome = DeviceAssessmentApplier.apply(
            dataStore = dataStore,
            plan = setup.plan,
            profile = setup.profile,
            safeLimitMb = facts.safeLimitMb,
            source = undoSourceOf(kind)
        )
        dataStore.setDeviceSetupRecordJson(DeviceSetupRecord(facts.fingerprint, nowMs, kind).encode())
        return outcome
    }

    /** The undo label a setup of [kind] leaves. */
    internal fun undoSourceOf(kind: DeviceSetupRecord.Kind): ChangeSource =
        if (kind == DeviceSetupRecord.Kind.FIRST_LAUNCH) ChangeSource.FIRST_LAUNCH else ChangeSource.SETUP_AGAIN
}
