package com.nuvio.tv.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L9-02/L9-10 + hybrid ruling (2026-10-05): stamps are pre-read/start times of SUCCESSFUL
 * pushes; incremental stamps wait while any push for the profile is unconfirmed.
 */
class PushWatermarkTest {
    private val gate = PushWatermarkGate()

    @Test
    fun `full push stamps the time taken before the local read, not completion time`() = runTest {
        var now = 1_000L
        var persisted: Long? = null

        val result = pushFullSnapshot(
            profileId = 1,
            gate = gate,
            readLocal = { now = 2_000L; listOf("a") },
            push = { now = 3_000L; Result.success(Unit) },
            persistWatermark = { persisted = it },
            clock = { now }
        )

        assertTrue(result.isSuccess)
        assertEquals(1_000L, persisted)
    }

    @Test
    fun `an entry written while the push runs stays above the watermark`() = runTest {
        var now = 1_000L
        val local = mutableMapOf("pushed" to 900L)
        var persisted = 0L

        pushFullSnapshot(
            profileId = 1,
            gate = gate,
            readLocal = { local.toMap() },
            push = { snapshot ->
                // Playback saves a new entry after the read, before the RPC returns.
                now = 1_500L
                local["written-during-push"] = now
                assertEquals(setOf("pushed"), snapshot.keys)
                Result.success(Unit)
            },
            persistWatermark = { persisted = it },
            clock = { now }
        )

        // Merge rule: a local key missing remotely is deleted only when ts <= watermark.
        val deletableIfMissingRemotely = local.filterValues { it <= persisted }.keys
        assertEquals(setOf("pushed"), deletableIfMissingRemotely)
    }

    @Test
    fun `a failed full push does not move the watermark and blocks incremental stamps`() = runTest {
        var persisted: Long? = null

        val result = pushFullSnapshot(
            profileId = 1,
            gate = gate,
            readLocal = { emptyList<String>() },
            push = { Result.failure(IllegalStateException("rpc failed")) },
            persistWatermark = { persisted = it },
            clock = { 5_000L }
        )
        pushIncremental(1, gate, push = { Result.success(Unit) }, persistWatermark = { persisted = it }, clock = { 6_000L })

        assertTrue(result.isFailure)
        assertNull(persisted)
    }

    private fun confirmNow(profileId: Int) = gate.confirm(profileId, gate.issuedSeq(profileId))

    @Test
    fun `a new process starts unconfirmed, so incremental pushes do not stamp yet`() = runTest {
        var persisted: Long? = null

        pushIncremental(1, gate, push = { Result.success(Unit) }, persistWatermark = { persisted = it }, clock = { 1_000L })

        assertNull(persisted)
    }

    @Test
    fun `incremental push stamps its start time while the profile is confirmed`() = runTest {
        confirmNow(1)
        var now = 1_000L
        var persisted: Long? = null

        pushIncremental(1, gate, push = { now = 2_000L; Result.success(Unit) }, persistWatermark = { persisted = it }, clock = { now })

        assertEquals(1_000L, persisted)
    }

    @Test
    fun `a thrown incremental failure blocks later stamps for that profile only`() = runTest {
        confirmNow(1)
        confirmNow(2)
        val persisted = mutableMapOf<Int, Long>()

        runCatching {
            pushIncremental(1, gate, push = { throw IllegalStateException("rpc") }, persistWatermark = { persisted[1] = it }, clock = { 1_000L })
        }
        pushIncremental(1, gate, push = { Result.success(Unit) }, persistWatermark = { persisted[1] = it }, clock = { 2_000L })
        pushIncremental(2, gate, push = { Result.success(Unit) }, persistWatermark = { persisted[2] = it }, clock = { 3_000L })

        assertNull(persisted[1])
        assertEquals(3_000L, persisted[2])
    }

    @Test
    fun `a full push confirms only the marks it saw at its start`() = runTest {
        var persisted: Long? = null
        gate.markUnconfirmed(1)

        pushFullSnapshot(
            profileId = 1,
            gate = gate,
            readLocal = { gate.markUnconfirmed(1); emptyList<String>() }, // a push fails mid-flight
            push = { Result.success(Unit) },
            persistWatermark = { persisted = it },
            clock = { 1_000L }
        )
        assertEquals(1_000L, persisted)
        assertFalse(gate.isConfirmed(1))

        pushIncremental(1, gate, push = { Result.success(Unit) }, persistWatermark = { persisted = it }, clock = { 2_000L })
        assertEquals(1_000L, persisted)
    }

    @Test
    fun `overlapping full pushes cannot erase a mark issued after both read local data`() = runTest {
        gate.markUnconfirmed(1)
        val seenByBoth = gate.issuedSeq(1)
        gate.markUnconfirmed(1) // a single push fails after both full pushes read local data

        gate.confirm(1, seenByBoth) // first full push succeeds
        gate.confirm(1, seenByBoth) // second, overlapping full push succeeds

        assertFalse(gate.isConfirmed(1))
    }

    @Test
    fun `a snapshot merge confirms only when it preserved nothing from a non-empty remote`() = runTest {
        confirmIfNothingPreserved(1, gate, remoteIsEmpty = false) { true }
        assertFalse(gate.isConfirmed(1))
        confirmIfNothingPreserved(1, gate, remoteIsEmpty = true) { false }
        assertFalse(gate.isConfirmed(1))

        confirmIfNothingPreserved(1, gate, remoteIsEmpty = false) { gate.markUnconfirmed(1); false }
        assertFalse(gate.isConfirmed(1)) // the mark issued during the merge stays

        confirmIfNothingPreserved(1, gate, remoteIsEmpty = false) { false }
        assertTrue(gate.isConfirmed(1))
        assertFalse(gate.isConfirmed(2))
    }
}
