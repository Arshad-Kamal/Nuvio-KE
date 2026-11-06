package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun repo(dir: File = tmp.root) = ChannelRepository(dir, { now }, UnconfinedTestDispatcher())

    private fun ch(source: String, id: String, lostAtMs: Long? = null) = Channel(
        key = "$source#id:$id",
        sourceId = source,
        name = id.uppercase(),
        streams = listOf(LiveStream("https://example.org/$id.m3u8")),
        lostAtMs = lostAtMs
    )

    @Test
    fun `a refresh persists and a new instance reads it back`() = runTest {
        repo().replaceSource("a", listOf(ch("a", "x"), ch("a", "y")))
        val second = repo()
        second.load()
        assertEquals(listOf("a#id:x", "a#id:y"), second.channels.value.map { it.key })
    }

    @Test
    fun `vanished channels are kept as lost, hidden, revived, then pruned`() = runTest {
        val r = repo()
        r.replaceSource("a", listOf(ch("a", "x"), ch("a", "y")))
        now += 1000
        r.replaceSource("a", listOf(ch("a", "x")))
        val y = r.channels.value.single { it.key == "a#id:y" }
        assertEquals(now, y.lostAtMs)
        assertEquals(listOf("a#id:x"), ChannelRepository.visible(r.channels.value).map { it.key })

        // A later refresh that still misses it keeps the first lost time.
        val lostAt = now
        now += 1000
        r.replaceSource("a", listOf(ch("a", "x")))
        assertEquals(lostAt, r.channels.value.single { it.key == "a#id:y" }.lostAtMs)

        // Back in the list: visible again.
        r.replaceSource("a", listOf(ch("a", "x"), ch("a", "y")))
        assertNull(r.channels.value.single { it.key == "a#id:y" }.lostAtMs)

        // Lost past retention: pruned.
        r.replaceSource("a", listOf(ch("a", "x")))
        now += ChannelRepository.LOST_RETENTION_MS
        r.replaceSource("a", listOf(ch("a", "x")))
        assertEquals(listOf("a#id:x"), r.channels.value.map { it.key })
    }

    @Test
    fun `a refreshed source keeps its place, a new one goes last`() {
        val current = listOf(ch("a", "1"), ch("b", "1"), ch("b", "2"), ch("c", "1"))
        val merged = ChannelRepository.mergeSource(current, "b", listOf(ch("b", "3")), now)
        assertEquals(
            listOf("a#id:1", "b#id:3", "b#id:1", "b#id:2", "c#id:1"),
            merged.map { it.key }
        )
        assertEquals(listOf(null, null, now, now, null), merged.map { it.lostAtMs })
        assertEquals("d#id:1", ChannelRepository.mergeSource(current, "d", listOf(ch("d", "1")), now).last().key)
    }

    @Test
    fun `overrides live in their own file and never rewrite the cache`() = runTest {
        val r = repo()
        r.replaceSource("a", listOf(ch("a", "x")))
        val cache = File(tmp.root, "channels.json")
        val before = cache.readText()
        r.setOverride("a#id:x", EpgOverride(EpgRef("epgpw-in", "542998")))
        assertEquals(before, cache.readText())

        val second = repo()
        second.load()
        assertEquals(EpgRef("epgpw-in", "542998"), second.overrides.value["a#id:x"]?.ref)
        second.setOverride("a#id:x", null)
        assertTrue(second.overrides.value.isEmpty())
    }

    @Test
    fun `removing a source drops its channels and their overrides`() = runTest {
        val r = repo()
        r.replaceSource("a", listOf(ch("a", "x")))
        r.replaceSource("b", listOf(ch("b", "x")))
        r.setOverride("a#id:x", EpgOverride(null))
        r.setOverride("b#id:x", EpgOverride(null))
        r.removeSource("a")
        assertEquals(listOf("b#id:x"), r.channels.value.map { it.key })
        assertEquals(setOf("b#id:x"), r.overrides.value.keys)
    }

    @Test
    fun `mutating a fresh instance without load keeps what is on disk`() = runTest {
        repo().replaceSource("a", listOf(ch("a", "x")))
        repo().setOverride("a#id:x", EpgOverride(null))
        val r = repo()
        r.replaceSource("b", listOf(ch("b", "y")))
        r.setOverride("b#id:y", EpgOverride(null))
        assertEquals(listOf("a#id:x", "b#id:y"), r.channels.value.map { it.key })
        assertEquals(setOf("a#id:x", "b#id:y"), r.overrides.value.keys)
    }

    @Test
    fun `overrides survive a refresh, lost state persists, pruning drops the override`() = runTest {
        val r = repo()
        r.replaceSource("a", listOf(ch("a", "x"), ch("a", "y")))
        r.setOverride("a#id:y", EpgOverride(EpgRef("e", "1")))
        r.replaceSource("a", listOf(ch("a", "x")))
        assertEquals(EpgRef("e", "1"), r.overrides.value["a#id:y"]?.ref)

        val reloaded = repo()
        reloaded.load()
        assertEquals(now, reloaded.channels.value.single { it.key == "a#id:y" }.lostAtMs)

        now += ChannelRepository.LOST_RETENTION_MS
        reloaded.replaceSource("a", listOf(ch("a", "x")))
        assertTrue(reloaded.overrides.value.isEmpty())
    }

    @Test
    fun `removing a source with an unreadable cache keeps every override`() = runTest {
        repo().setOverride("b#id:x", EpgOverride(null))
        File(tmp.root, "channels.json").writeText("garbage")
        val r = repo()
        r.removeSource("a")
        assertEquals(setOf("b#id:x"), r.overrides.value.keys)
    }

    @Test
    fun `a corrupt overrides file starts empty without touching channels`() = runTest {
        repo().replaceSource("a", listOf(ch("a", "x")))
        File(tmp.root, "epg-overrides.json").writeText("[1,")
        val r = repo()
        r.load()
        assertTrue(r.overrides.value.isEmpty())
        assertEquals(1, r.channels.value.size)
    }

    @Test
    fun `repeated keys in a listing keep the first`() {
        val merged = ChannelRepository.mergeSource(
            emptyList(), "a", listOf(ch("a", "x"), ch("a", "x").copy(name = "Dup")), now
        )
        assertEquals(listOf("X"), merged.map { it.name })
    }

    @Test
    fun `a failed write leaves memory and disk as they were`() = runTest {
        val r = repo()
        r.replaceSource("a", listOf(ch("a", "x")))
        // A directory where the temp file must go makes the write fail.
        File(tmp.root, "channels.json.tmp").mkdirs()
        val failed = runCatching { r.replaceSource("a", listOf(ch("a", "y"))) }
        assertTrue(failed.isFailure)
        assertEquals(listOf("a#id:x"), r.channels.value.map { it.key })
    }

    @Test
    fun `an unreadable cache starts empty, and a refresh replaces it`() = runTest {
        File(tmp.root, "channels.json").writeText("{not json")
        val r = repo()
        r.load()
        assertTrue(r.channels.value.isEmpty())
        r.replaceSource("a", listOf(ch("a", "x")))
        val second = repo()
        second.load()
        assertEquals(1, second.channels.value.size)
        assertTrue(File(tmp.root, "channels.json.tmp").exists().not())
    }
}
