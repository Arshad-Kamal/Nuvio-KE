package com.nuvio.tv.core.sync

/**
 * Per-profile deletion-protection watermark (L9-02/L9-10, hybrid ruling 2026-10-05 after
 * red-team 2026-10-05-145737, fix-review 2026-10-05-155702).
 *
 * Snapshot merges delete a local entry that is missing remotely only when its timestamp is
 * <= the profile's persisted watermark, so the watermark may only cover entries that reached
 * the server. Stamps are the time taken BEFORE the pushed entries were read (an entry written
 * while a push runs stays above it) and are persisted only after the push succeeded.
 *
 * - A full push stamps its pre-read time ([pushFullSnapshot]).
 * - An incremental push (single entry or batch) stamps its start time ([pushIncremental]) so
 *   deletions made on another device stick at the next snapshot, but only while the profile
 *   is confirmed ([PushWatermarkGate]): an entry whose push failed or was skipped may be
 *   missing remotely, so the watermark stays below it until a later confirmation.
 *
 * Confirmation = every local entry is known to be on the server: a successful full push, or
 * a snapshot merge that preserved nothing ([confirmIfNothingPreserved]). The gate is in
 * memory and every profile starts UNCONFIRMED in a process, so a stamp never covers an entry
 * the previous process left unsynced. Marks and confirmations are sequence numbers, so
 * overlapping full pushes cannot erase a mark issued after they read local data.
 * Residual: an entry whose own push is still pending (watched-items debounce) or overlaps an
 * incremental push that stamps first, and then fails, sits under that stamp.
 */
internal class PushWatermarkGate {
    private val issued = HashMap<Int, Long>()
    private val confirmed = HashMap<Int, Long>()

    /** A push for [profileId] failed or was skipped: its entries may be missing remotely. */
    @Synchronized
    fun markUnconfirmed(profileId: Int) {
        issued[profileId] = issuedSeq(profileId) + 1
    }

    /** Captured BEFORE local data is read; passed to [confirm] once that read is known synced. */
    @Synchronized
    fun issuedSeq(profileId: Int): Long = issued[profileId] ?: INITIAL_SEQ

    @Synchronized
    fun isConfirmed(profileId: Int): Boolean = (confirmed[profileId] ?: 0L) >= issuedSeq(profileId)

    /** Marks issued up to [seenSeq] are resolved; later marks stay. */
    @Synchronized
    fun confirm(profileId: Int, seenSeq: Long) {
        confirmed[profileId] = maxOf(confirmed[profileId] ?: 0L, seenSeq)
    }

    private companion object {
        // Every profile starts with one implicit mark per process: unconfirmed until proven.
        const val INITIAL_SEQ = 1L
    }
}

/**
 * A thrown push failure (the services' RPC calls throw) marks the gate like a failed Result.
 * Cancellation included: the request may or may not have reached the server.
 */
private inline fun markOnThrow(
    profileId: Int,
    gate: PushWatermarkGate,
    block: () -> Result<Unit>
): Result<Unit> = try {
    block()
} catch (e: Exception) {
    gate.markUnconfirmed(profileId)
    throw e
}

internal suspend fun <T> pushFullSnapshot(
    profileId: Int,
    gate: PushWatermarkGate,
    readLocal: suspend () -> T,
    push: suspend (T) -> Result<Unit>,
    persistWatermark: suspend (Long) -> Unit,
    clock: () -> Long = System::currentTimeMillis
): Result<Unit> {
    val seenSeq = gate.issuedSeq(profileId)
    val snapshotAtMs = clock()
    val result = markOnThrow(profileId, gate) { push(readLocal()) }
    if (result.isSuccess) {
        persistWatermark(snapshotAtMs)
        gate.confirm(profileId, seenSeq)
    } else {
        gate.markUnconfirmed(profileId)
    }
    return result
}

/** [push] sends entries already saved locally; stamp rule in the KDoc above. */
internal suspend fun pushIncremental(
    profileId: Int,
    gate: PushWatermarkGate,
    push: suspend () -> Result<Unit>,
    persistWatermark: suspend (Long) -> Unit,
    clock: () -> Long = System::currentTimeMillis
): Result<Unit> {
    val startedAtMs = clock()
    val result = markOnThrow(profileId, gate) { push() }
    if (result.isFailure) {
        gate.markUnconfirmed(profileId)
    } else if (gate.isConfirmed(profileId)) {
        persistWatermark(startedAtMs)
    }
    return result
}

/**
 * Runs a snapshot merge; [merge] returns whether it preserved local-only entries. A merge that
 * preserved nothing leaves every local entry it read on the server, so it confirms the marks
 * issued before it started; a preserving merge is followed by a full push instead.
 * [remoteIsEmpty]: both merges keep ALL local entries without reporting them when the remote
 * snapshot is empty, so an empty snapshot never confirms.
 */
internal suspend fun confirmIfNothingPreserved(
    profileId: Int,
    gate: PushWatermarkGate,
    remoteIsEmpty: Boolean,
    merge: suspend () -> Boolean
): Boolean {
    val seenSeq = gate.issuedSeq(profileId)
    val preserved = merge()
    if (!preserved && !remoteIsEmpty) gate.confirm(profileId, seenSeq)
    return preserved
}
