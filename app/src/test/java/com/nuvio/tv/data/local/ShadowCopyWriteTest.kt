package com.nuvio.tv.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** B26 seams of ProfileDataStoreFactory's shadow copy: atomic write + serialised, coalesced runs. */
class ShadowCopyWriteTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `copies source to backup and leaves no temp file`() {
        val source = File(tmpFolder.root, "p.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val backup = File(tmpFolder.root, "p.preferences_pb.bak").apply { writeBytes(byteArrayOf(9)) }

        writeShadowCopyAtomically(source, backup)

        assertArrayEquals(byteArrayOf(1, 2, 3), backup.readBytes())
        assertFalse(File(tmpFolder.root, "p.preferences_pb.bak.tmp").exists())
    }

    @Test
    fun `empty source deletes the stale backup`() {
        val source = File(tmpFolder.root, "p.preferences_pb").apply { writeBytes(ByteArray(0)) }
        val backup = File(tmpFolder.root, "p.preferences_pb.bak").apply { writeBytes(byteArrayOf(9)) }

        writeShadowCopyAtomically(source, backup)

        assertFalse(backup.exists())
    }

    @Test
    fun `runs never overlap`() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val runner = CoalescingSerialRunner {
            maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(2)
            active.decrementAndGet()
        }

        val threads = List(8) { thread { repeat(20) { runner.run() } } }
        threads.forEach { it.join(10_000) }

        assertEquals(1, maxActive.get())
    }

    @Test
    fun `request arriving while a run is already waiting is dropped`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val runs = AtomicInteger(0)
        val runner = CoalescingSerialRunner {
            if (runs.incrementAndGet() == 1) {
                firstEntered.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
            }
        }

        val first = thread { runner.run() }
        firstEntered.await(10, TimeUnit.SECONDS)
        val second = thread { runner.run() }
        // BLOCKED on the monitor means the second request is queued behind the first.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, second.state)

        // Dropped: returns while the first run still holds the lock.
        val third = thread { runner.run() }
        third.join(5_000)
        val thirdReturnedEarly = !third.isAlive

        releaseFirst.countDown()
        first.join(10_000)
        second.join(10_000)
        third.join(10_000)
        assertTrue(thirdReturnedEarly)
        assertEquals(2, runs.get())
    }
}
