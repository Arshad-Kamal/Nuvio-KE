package com.nuvio.tv.livetv.data

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HiddenChannelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.root) = HiddenChannelStore(dir, UnconfinedTestDispatcher())

    @Test
    fun `hide and unhide persist across instances`() = runTest {
        val first = store()
        assertTrue(first.setHidden("a", true))
        assertTrue(first.setHidden("b", true))
        assertTrue(first.setHidden("a", false))
        val second = store()
        second.load()
        assertEquals(setOf("b"), second.keys.value)
    }

    @Test
    fun `an unwritable file changes nothing`() = runTest {
        val blocker = tmp.newFile("not-a-dir")
        val s = store(File(blocker, "livetv"))
        assertFalse(s.setHidden("a", true))
        assertEquals(emptySet<String>(), s.keys.value)
    }
}
