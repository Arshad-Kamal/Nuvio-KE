package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingLibraryEntry
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingRateLimitException
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.core.tracking.trackerCallFailure
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.toSavedLibraryItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/*
 * Library transfer (Spec 104 S4c, mockup stages D1-D4). Directions: Copy between any two of Nuvio and the connected
 * trackers, Move only from Nuvio (D14), never into a tracker in [TRANSFER_DEFERRED_DESTINATIONS] (D17). The service
 * runs on its own scope, so leaving Settings does not stop it.
 */

/** Never offered as To (D17): a transfer into Simkl waits for Spec 106 to prove its one-status-per-title handling. */
internal val TRANSFER_DEFERRED_DESTINATIONS: Set<TrackingSource> = setOf(TrackingSource.SIMKL)

/** Where [from] may transfer to, given the [connected] trackers (Nuvio always takes part). */
fun transferDestinations(from: TrackingSource, connected: Set<TrackingSource>): List<TrackingSource> =
    (connected + TrackingSource.NUVIO)
        .filter { it != from && it !in TRANSFER_DEFERRED_DESTINATIONS }
        .sortedBy { it.ordinal }

/** The sources with at least one allowed destination. */
fun transferSources(connected: Set<TrackingSource>): List<TrackingSource> =
    (connected + TrackingSource.NUVIO)
        .filter { transferDestinations(it, connected).isNotEmpty() }
        .sortedBy { it.ordinal }

fun transferMoveAllowed(from: TrackingSource): Boolean = from == TrackingSource.NUVIO

enum class LibraryTransferMode { COPY, MOVE }

enum class LibraryTransferOutcome { DONE, STOPPED, STOPPED_AFTER_ERRORS }

enum class LibraryTransferStopReason {
    RATE_LIMIT,
    /** Five titles in a row failed. */
    FAILURES,
    /** A tracker end was disconnected, revoked or signed in to another account. */
    ACCOUNT_CHANGED,
    /** A Move into a tracker that gave no account name: a switch of account could not be noticed. */
    ACCOUNT_UNCONFIRMED,
    READ_FAILED,
    REMOVE_FAILED
}

/** Stage D1: the plan as shown. Start takes it back, so a Move removes no more than its confirm named. */
data class LibraryTransferPreview(
    val profileId: Int,
    val from: TrackingSource,
    val to: TrackingSource,
    val plan: LibraryTransferPlan
)

/**
 * Result rows (0 rows are left out). They overlap by design: [copied] + [couldNotCopy] + [notCopiedYet] = the To copy
 * count; [removed] + [notRemoved] = the Move candidates (both 0 when a Move stopped before its removal step).
 */
data class LibraryTransferCounts(
    val copied: Int,
    val alreadyThere: Int,
    val couldNotCopy: Int,
    val notCopiedYet: Int,
    val removed: Int = 0,
    val notRemoved: Int = 0
)

data class LibraryTransferResult(
    val from: TrackingSource,
    val to: TrackingSource,
    val mode: LibraryTransferMode,
    val outcome: LibraryTransferOutcome,
    val reason: LibraryTransferStopReason? = null,
    /** The end the [reason] is about (a failed read, a changed account); null when it is not about one end. */
    val failedEnd: TrackingSource? = null,
    /** Null when the run stopped before writing anything (headline and reason only). */
    val counts: LibraryTransferCounts? = null,
    /** The destination could not be read after the copy: Copied comes from the write answers, a Move removed nothing. */
    val checkFailed: Boolean = false,
    /** The run's plan: titles in From (the result's notes, "Nuvio still has all 212") and those no id matches. */
    val sourceTitles: Int = 0,
    val unmatched: Int = 0
)

sealed interface LibraryTransferState {
    data object Idle : LibraryTransferState

    data class Running(
        val from: TrackingSource,
        val to: TrackingSource,
        val mode: LibraryTransferMode,
        val done: Int,
        val total: Int
    ) : LibraryTransferState

    data class Finished(val result: LibraryTransferResult) : LibraryTransferState
}

/** A destination or source could not be read (stage D1 shows it; a run stops with [LibraryTransferStopReason.READ_FAILED]). */
class LibraryTransferReadException(val source: TrackingSource, cause: Throwable) :
    Exception("${source.storageId} read failed", cause)

/**
 * Runs one transfer at a time (Spec 104 § S4 plan, steps 1-5). The Nuvio side is [LibraryPreferences] with the profile
 * captured at the preview; no read follows the active profile. Nuvio adds and removals are pending changes the next
 * gated Nuvio sync sends (no push from here). Failures are logged by class only.
 */
@Singleton
class LibraryTransferService internal constructor(
    private val libraryPreferences: LibraryPreferences,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val accounts: TrackingAccountStore,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        libraryPreferences: LibraryPreferences,
        libraryProviders: TrackingLibraryProviderRegistry,
        accounts: TrackingAccountStore
    ) : this(libraryPreferences, libraryProviders, accounts, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val _state = MutableStateFlow<LibraryTransferState>(LibraryTransferState.Idle)
    val state: StateFlow<LibraryTransferState> = _state.asStateFlow()

    @Volatile
    private var stopRequested = false

    /** Step 1 (stage D1): reads both ends of [profileId] and plans. Throws [LibraryTransferReadException]. */
    suspend fun preview(profileId: Int, from: TrackingSource, to: TrackingSource): LibraryTransferPreview {
        require(to != from && to !in TRANSFER_DEFERRED_DESTINATIONS) { "Transfer ${from.storageId} -> ${to.storageId}" }
        return LibraryTransferPreview(profileId, from, to, computeTransferPlan(read(profileId, from), read(profileId, to)))
    }

    /** Starts [preview] as [mode]; false while another transfer runs or for a Move that is not from Nuvio. */
    fun start(preview: LibraryTransferPreview, mode: LibraryTransferMode): Boolean {
        if (mode == LibraryTransferMode.MOVE && !transferMoveAllowed(preview.from)) return false
        while (true) {
            val current = _state.value
            if (current is LibraryTransferState.Running) return false
            val running = LibraryTransferState.Running(preview.from, preview.to, mode, 0, preview.plan.toCopy.size)
            if (_state.compareAndSet(current, running)) break
        }
        stopRequested = false
        val failed = LibraryTransferResult(preview.from, preview.to, mode, LibraryTransferOutcome.STOPPED_AFTER_ERRORS)
        scope.launch {
            var result = failed
            try {
                result = run(preview, mode)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "transfer failed: ${error.javaClass.simpleName}")
            } finally {
                // Always leaves Running, even when the scope is cancelled, so the panel never shows a run forever.
                _state.value = LibraryTransferState.Finished(result)
            }
        }
        return true
    }

    /** Stage D2's Stop: the run ends at its next step boundary and reports what it did. */
    fun stop() {
        if (_state.value is LibraryTransferState.Running) stopRequested = true
    }

    /** Close on a result: back to the plan next time. */
    fun dismiss() {
        val current = _state.value
        if (current is LibraryTransferState.Finished) _state.compareAndSet(current, LibraryTransferState.Idle)
    }

    /** Step 4's read: the destination's entries, or why there are none. */
    private sealed interface DestinationRead {
        class Listed(val entries: List<TrackingLibraryEntry>) : DestinationRead
        data object Failed : DestinationRead
        class AccountChanged(val end: TrackingSource) : DestinationRead
    }

    private suspend fun run(preview: LibraryTransferPreview, mode: LibraryTransferMode): LibraryTransferResult {
        val (profileId, from, to) = preview
        fun startStop(reason: LibraryTransferStopReason, end: TrackingSource?) =
            LibraryTransferResult(from, to, mode, LibraryTransferOutcome.STOPPED_AFTER_ERRORS, reason, end)

        // Step 2: the accounts and a fresh plan; a stale panel never decides.
        val ends = listOf(from, to).filter { it.providerId != null }
        val startAccounts = HashMap<TrackingSource, String?>()
        for (end in ends) {
            val account = trackerAccount(profileId, end) ?: return startStop(LibraryTransferStopReason.ACCOUNT_CHANGED, end)
            startAccounts[end] = account.name
        }
        if (mode == LibraryTransferMode.MOVE) {
            startAccounts.entries.firstOrNull { it.value == null }
                ?.let { return startStop(LibraryTransferStopReason.ACCOUNT_UNCONFIRMED, it.key) }
        }
        val plan = try {
            computeTransferPlan(read(profileId, from), read(profileId, to))
        } catch (error: LibraryTransferReadException) {
            return startStop(LibraryTransferStopReason.READ_FAILED, error.source)
        }
        /** The first tracker end whose account is no longer the one captured at Start, else null. */
        suspend fun changedEnd(): TrackingSource? = ends.firstOrNull { end ->
            val now = trackerAccount(profileId, end)
            now == null || now.name != startAccounts[end]
        }

        var outcome = LibraryTransferOutcome.DONE
        var reason: LibraryTransferStopReason? = null
        var failedEnd: TrackingSource? = null
        fun stopWith(newReason: LibraryTransferStopReason, end: TrackingSource? = null) {
            if (reason != null) return
            outcome = LibraryTransferOutcome.STOPPED_AFTER_ERRORS
            reason = newReason
            failedEnd = end
        }

        // Step 3: copy; a title is written once its write was sent; failures counted in titles.
        val written = mutableListOf<TransferTitle>()
        val accepted = mutableSetOf<TransferTitle>()
        var failedInRow = 0
        _state.value = LibraryTransferState.Running(from, to, mode, 0, plan.toCopy.size)
        for (batch in plan.toCopy.chunked(TRANSFER_BATCH_SIZE)) {
            if (stopRequested) {
                outcome = LibraryTransferOutcome.STOPPED
                break
            }
            changedEnd()?.let { end ->
                stopWith(LibraryTransferStopReason.ACCOUNT_CHANGED, end)
                break
            }
            val write = write(profileId, to, batch)
            written += write.sent
            accepted += write.accepted
            var tooManyFailures = false
            for (title in write.sent) {
                failedInRow = if (title in write.accepted) 0 else failedInRow + 1
                if (failedInRow >= MAX_FAILED_IN_ROW) tooManyFailures = true
            }
            _state.value = LibraryTransferState.Running(from, to, mode, written.size, plan.toCopy.size)
            if (write.rateLimited) {
                stopWith(LibraryTransferStopReason.RATE_LIMIT, to)
                break
            }
            if (tooManyFailures) {
                stopWith(LibraryTransferStopReason.FAILURES, to)
                break
            }
        }

        // Step 4: one fresh read of the destination, between two account checks (none after an account stop).
        val destination = if (reason == LibraryTransferStopReason.ACCOUNT_CHANGED) {
            DestinationRead.Failed
        } else {
            verifiedRead(profileId, to, ::changedEnd)
        }
        if (destination is DestinationRead.AccountChanged) stopWith(LibraryTransferStopReason.ACCOUNT_CHANGED, destination.end)
        val listed = (destination as? DestinationRead.Listed)?.entries
        val copied = if (listed == null) {
            written.count { it in accepted }
        } else {
            val listedKeys = listed.flatMapTo(HashSet(), ::transferKeys)
            written.count { title -> title.keys.any { it in listedKeys } }
        }
        var counts = LibraryTransferCounts(
            copied = copied,
            alreadyThere = plan.alreadyThere.size,
            couldNotCopy = written.size - copied,
            notCopiedYet = plan.toCopy.size - written.size
        )

        // Step 5: a Move removes from Nuvio only after a copy that ran to the end, at most what its confirm named.
        if (mode == LibraryTransferMode.MOVE && outcome == LibraryTransferOutcome.DONE) {
            val named = preview.plan.moveCandidates.flatMapTo(HashSet()) { it.keys }
            val candidates = plan.moveCandidates.filter { title -> title.keys.any { it in named } }
            val confirmed = listed?.let { confirmedAt(candidates, it) }
            when {
                confirmed == null -> counts = counts.copy(notRemoved = candidates.size)
                stopRequested -> outcome = LibraryTransferOutcome.STOPPED
                else -> {
                    val removeKeys = confirmed.flatMapTo(HashSet()) { it.keys }
                    val failure = trackerCallFailure {
                        libraryPreferences.removeItemsWhere(profileId) { item ->
                            transferKeys(item.toLibraryEntry().asTransferEntry()).any { it in removeKeys }
                        }
                    }
                    counts = if (failure == null) {
                        // A confirmed title with no entry left (removed meanwhile) is gone from Nuvio too.
                        counts.copy(removed = confirmed.size, notRemoved = candidates.size - confirmed.size)
                    } else {
                        Log.w(TAG, "removal failed: ${failure.javaClass.simpleName}")
                        stopWith(LibraryTransferStopReason.REMOVE_FAILED)
                        counts.copy(notRemoved = candidates.size)
                    }
                }
            }
        }
        return LibraryTransferResult(
            from, to, mode, outcome, reason, failedEnd, counts,
            checkFailed = listed == null,
            sourceTitles = plan.sourceTitles,
            unmatched = plan.unmatched
        )
    }

    private suspend fun verifiedRead(
        profileId: Int,
        to: TrackingSource,
        changedEnd: suspend () -> TrackingSource?
    ): DestinationRead {
        changedEnd()?.let { return DestinationRead.AccountChanged(it) }
        val entries = try {
            read(profileId, to)
        } catch (error: LibraryTransferReadException) {
            return DestinationRead.Failed
        }
        changedEnd()?.let { return DestinationRead.AccountChanged(it) }
        return DestinationRead.Listed(entries)
    }

    private class Account(val name: String?)

    /** [end]'s account on [profileId], or null when it is missing, revoked, unreadable or has no library provider. */
    private suspend fun trackerAccount(profileId: Int, end: TrackingSource): Account? {
        val id = end.providerId ?: return null
        var found: Account? = null
        val failure = trackerCallFailure {
            val account = accounts.get(profileId, id)
            if (account != null && !account.revoked && libraryProviders.provider(id) != null) found = Account(account.accountName)
        }
        if (failure != null) Log.w(TAG, "${end.storageId} account read failed: ${failure.javaClass.simpleName}")
        return found
    }

    private suspend fun read(profileId: Int, end: TrackingSource): List<TrackingLibraryEntry> {
        var entries: List<TrackingLibraryEntry> = emptyList()
        val failure = trackerCallFailure {
            entries = when (val id = end.providerId) {
                null -> libraryPreferences.getAllItems(profileId).map { it.toLibraryEntry().asTransferEntry() }
                else -> {
                    val provider = checkNotNull(libraryProviders.provider(id)) { "No library provider" }
                    withTimeout(READ_TIMEOUT_MS) { provider.readLibrary(profileId) }
                }
            }
        }
        if (failure != null) {
            Log.w(TAG, "${end.storageId} read failed: ${failure.javaClass.simpleName}")
            throw LibraryTransferReadException(end, failure)
        }
        return entries
    }

    /** One batch: the titles [sent] (all but those after a rate limit), those [accepted], and whether it was rate limited. */
    private class BatchWrite(val sent: List<TransferTitle>, val accepted: Set<TransferTitle>, val rateLimited: Boolean = false)

    private suspend fun write(profileId: Int, to: TrackingSource, batch: List<TransferTitle>): BatchWrite {
        val id = to.providerId
        if (id == null) {
            val accepted = batch.filterTo(mutableSetOf()) { title ->
                trackerCallFailure { libraryPreferences.addItem(title.writeInput().toSavedLibraryItem(), profileId) }
                    ?.also { Log.w(TAG, "nuvio write failed: ${it.javaClass.simpleName}") } == null
            }
            return BatchWrite(batch, accepted)
        }
        val provider = libraryProviders.provider(id) ?: return BatchWrite(batch, emptySet())
        val inputs = batch.associateBy { it.writeInput() }
        var acceptedIds: Set<String> = emptySet()
        // The provider bounds each call (the default addAll each item); this bounds the batch as a whole.
        val failure = trackerCallFailure {
            acceptedIds = withTimeout(WRITE_TIMEOUT_MS * TRANSFER_BATCH_SIZE) { provider.addAll(profileId, inputs.keys.toList()) }
        }
        if (failure != null) Log.w(TAG, "${to.storageId} write failed: ${failure.javaClass.simpleName}")
        val rateLimit = failure as? TrackingRateLimitException
        if (rateLimit != null) acceptedIds = rateLimit.accepted
        val sentIds = rateLimit?.sent
        val sent = if (sentIds == null) batch else inputs.filterKeys { it.itemId in sentIds }.values.toList()
        val accepted = inputs.filterKeys { it.itemId in acceptedIds }.values.toSet()
        return BatchWrite(sent, accepted, rateLimited = rateLimit != null)
    }

    private companion object {
        const val TAG = "LibraryTransfer"
        const val TRANSFER_BATCH_SIZE = 10
        const val MAX_FAILED_IN_ROW = 5
        const val READ_TIMEOUT_MS = 5 * 60_000L
        const val WRITE_TIMEOUT_MS = 30_000L
    }
}
