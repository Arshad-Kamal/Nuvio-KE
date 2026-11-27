package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.core.player.ScreensaverController
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class StreamBadgePresentationTest {

    private val playback = MutableStateFlow(false)
    private val screensaver = mockk<ScreensaverController>().also {
        every { it.playbackActive } returns playback
    }
    private val presentation = StreamBadgePresentation(dataStore = mockk(relaxed = true), screensaverController = screensaver)

    @After
    fun tearDown() {
        unmockkObject(StreamBadgeMatcher)
    }

    private val groups = listOf(
        AddonStreams(
            addonName = "Addon",
            addonLogo = null,
            streams = listOf(
                Stream(
                    name = "Movie 2160p DV Atmos",
                    title = null,
                    description = null,
                    url = "https://example.com/movie.mkv",
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = null,
                    addonName = "Addon",
                    addonLogo = null
                )
            )
        )
    )

    private fun rules(name: String, pattern: String) = StreamBadgeRules(
        imports = listOf(
            StreamBadgeImport(
                sourceUrl = "https://example.com/badges.json",
                filters = listOf(
                    StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://cdn/$pattern.png")
                )
            )
        )
    )

    private fun badgesOf(result: List<AddonStreams>): List<StreamBadge> = result.single().streams.single().badges

    @Test
    fun `changed rules rebuild the badges and an equal copy reuses them`() {
        val rulesA = rules("Dolby Vision", "DV")
        val rulesB = rules("Atmos", "Atmos")

        assertEquals(listOf("Dolby Vision"), badgesOf(presentation.apply(groups, rulesA)).map { it.name })

        val fromB = badgesOf(presentation.apply(groups, rulesB))
        assertEquals(listOf("Atmos"), fromB.map { it.name })

        val equalCopy = rulesB.copy()
        assertNotSame(rulesB, equalCopy)
        val fromCopy = badgesOf(presentation.apply(groups, equalCopy))
        assertEquals(1, fromCopy.size)
        assertSame(fromB.single(), fromCopy.single())
    }

    // Spec 130 round 2: parallel first callers (list chunks, the prefetch) compile a pack once.
    @Test
    fun `concurrent first callers compile the rules once`() {
        mockkObject(StreamBadgeMatcher)
        every { StreamBadgeMatcher.compile(any()) } answers { Thread.sleep(100); callOriginal() }
        val rules = rules("Dolby Vision", "DV")
        val start = CountDownLatch(1)

        val threads = (1..2).map { Thread { start.await(); presentation.apply(groups, rules) }.apply { start() } }
        start.countDown()
        threads.forEach { it.join() }

        verify(exactly = 1) { StreamBadgeMatcher.compile(any()) }
    }

    // The warm (Spec 130 round 2 Q3): imported packs only, never during playback, never over another build.
    @Test
    fun `warm skips rules without an import`() = runTest {
        mockkObject(StreamBadgeMatcher)

        presentation.warm(StreamBadgeRules())

        verify(exactly = 0) { StreamBadgeMatcher.compileWhile(any(), any()) }
    }

    @Test
    fun `warm waits while playback is active, then a first use needs no compile`() = runTest {
        mockkObject(StreamBadgeMatcher)
        val rules = rules("Dolby Vision", "DV")
        playback.value = true

        val warm = launch { presentation.warm(rules) }
        runCurrent()
        verify(exactly = 0) { StreamBadgeMatcher.compileWhile(any(), any()) }
        playback.value = false
        warm.join()

        assertEquals(listOf("Dolby Vision"), badgesOf(presentation.apply(groups, rules)).map { it.name })
        verify(exactly = 0) { StreamBadgeMatcher.compile(any()) }
    }

    @Test
    fun `warm stopped by a playback start retries once playback ends`() = runTest {
        mockkObject(StreamBadgeMatcher)
        val calls = AtomicInteger()
        val stopped = CompletableDeferred<Unit>()
        every { StreamBadgeMatcher.compileWhile(any(), any()) } answers {
            if (calls.getAndIncrement() == 0) playback.value = true // playback starts mid-warm
            callOriginal().also { if (it == null) stopped.complete(Unit) }
        }
        val rules = rules("Dolby Vision", "DV")

        val warm = launch(Dispatchers.Default) { presentation.warm(rules) }
        stopped.await()
        assertFalse(warm.isCompleted)
        playback.value = false
        warm.join()

        assertEquals(2, calls.get())
        presentation.apply(groups, rules)
        verify(exactly = 0) { StreamBadgeMatcher.compile(any()) }
    }

    @Test
    fun `warm never replaces a snapshot a foreground build published meanwhile`() = runTest {
        mockkObject(StreamBadgeMatcher)
        val rules = rules("Dolby Vision", "DV")
        lateinit var foreground: StreamBadge
        val warmCall = java.util.concurrent.atomic.AtomicBoolean(true)
        every { StreamBadgeMatcher.compileWhile(any(), any()) } answers {
            // The warm's call only: the foreground compile() below delegates here too.
            if (warmCall.getAndSet(false)) foreground = badgesOf(presentation.apply(groups, rules)).single()
            callOriginal()
        }

        presentation.warm(rules)

        assertSame(foreground, badgesOf(presentation.apply(groups, rules)).single())
        verify(exactly = 1) { StreamBadgeMatcher.compile(any()) }
    }

    // The foreground build lands after the warm's last pattern check: the compareAndSet keeps it.
    @Test
    fun `warm finishing after a foreground build keeps the foreground snapshot`() = runTest {
        mockkObject(StreamBadgeMatcher)
        val rules = rules("Dolby Vision", "DV")
        lateinit var foreground: StreamBadge
        val warmCall = java.util.concurrent.atomic.AtomicBoolean(true)
        every { StreamBadgeMatcher.compileWhile(any(), any()) } answers {
            val first = warmCall.getAndSet(false)
            callOriginal().also {
                if (first) foreground = badgesOf(presentation.apply(groups, rules)).single()
            }
        }

        presentation.warm(rules)

        assertSame(foreground, badgesOf(presentation.apply(groups, rules)).single())
    }
}
