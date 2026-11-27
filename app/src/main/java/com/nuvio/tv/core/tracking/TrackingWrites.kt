package com.nuvio.tv.core.tracking

/** Write contracts each tracker implements. Ported from upstream NuvioTV (`core/tracking/TrackingWrites.kt`). */
enum class TrackingScrobbleAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop")
}

enum class TrackingSeekScrobblePolicy {
    NONE,
    STOP_AND_RESTART
}

/** A tracker's own list a title can sit in (Simkl's statuses), in Simkl's order: the Library shelves them so (Spec 106 G1). */
enum class TrackingListStatus(val wireValue: String) {
    WATCHING("watching"),
    PLAN_TO_WATCH("plantowatch"),
    ON_HOLD("hold"),
    COMPLETED("completed"),
    DROPPED("dropped");

    companion object {
        fun fromWireValue(value: String?): TrackingListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

data class TrackingHistoryItem(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long? = null
)

data class TrackingScrobbleEvent(
    val media: TrackingMediaReference,
    val progressPercent: Double,
    /** Trackers that already get this play from elsewhere, such as a media server that scrobbles itself. */
    val skippedProviders: Set<TrackingProviderId> = emptySet(),
    val durationSeconds: Long? = null
)

data class TrackingMutationResult(
    val attemptedCount: Int,
    val notFoundCount: Int = 0,
    val resolutions: List<TrackingMutationResolution> = emptyList()
) {
    val resolvedListStatuses: List<TrackingListStatus>
        get() = resolutions.mapNotNull(TrackingMutationResolution::listStatus)

    val isComplete: Boolean
        get() = notFoundCount == 0
}

data class TrackingMutationResolution(
    val listStatus: TrackingListStatus? = null,
    val mediaKind: TrackingMediaKind? = null,
    val providerSubtype: String? = null
)

interface TrackingListWriter {
    val providerId: TrackingProviderId

    suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult

    suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult
}

/** Marks and unmarks; [TrackingProgressRouter] calls it only while the tracker is connected, never for a whole series. */
interface TrackingHistoryWriter {
    val providerId: TrackingProviderId

    suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult

    suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult
}

interface TrackingScrobbler {
    val providerId: TrackingProviderId
    val seekScrobblePolicy: TrackingSeekScrobblePolicy
        get() = TrackingSeekScrobblePolicy.NONE

    /** A pause saves nothing on this tracker, so leaving while paused has to send a stop. */
    val stopsWhenLeftPaused: Boolean
        get() = false

    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent)
}
