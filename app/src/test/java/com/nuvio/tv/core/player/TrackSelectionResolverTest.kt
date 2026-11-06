package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerRuntimeController.PendingEngineSwitchTrackPreference
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedSubtitleSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.RememberedTrackSelection
import com.nuvio.tv.core.player.PlayerRuntimeController.TrackPreference
import com.nuvio.tv.core.player.TrackSelectionResolver.AudioStep
import com.nuvio.tv.core.player.TrackSelectionResolver.Channel
import com.nuvio.tv.core.player.TrackSelectionResolver.SubtitleStep
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 21 P5: the track-preference restore decisions (precedence, per-track steps, what stays pending). */
class TrackSelectionResolverTest {

    private val url = "https://cdn.example/title.mkv"
    private val englishAudio = RememberedTrackSelection(language = "eng", name = "TrueHD 7.1", trackId = "1:1")
    private val frenchAudio = RememberedTrackSelection(language = "fre", name = "AC3 5.1", trackId = "1:2")
    private val persisted = TrackPreference(audio = englishAudio)
    private val carried = TrackPreference(audio = frenchAudio)

    private fun carry(streamUrl: String = url, engine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER) =
        PendingEngineSwitchTrackPreference(streamUrl = streamUrl, preference = carried, sourceEngine = engine)

    private fun track(index: Int, language: String?, name: String = "Track $index", selected: Boolean = false) =
        TrackInfo(index = index, name = name, language = language, isSelected = selected)

    private fun addon(id: String, lang: String, addonName: String = "OpenSubtitles") =
        Subtitle(id = id, url = "https://subs.example/$id.srt", lang = lang, addonName = addonName, addonLogo = null)

    // --- source precedence ---

    @Test
    fun `source precedence - carry for this url, stale carry, persisted, none`() {
        data class Case(
            val name: String,
            val carry: PendingEngineSwitchTrackPreference?,
            val persisted: TrackPreference?,
            val channel: Channel?,
            val preference: TrackPreference?,
            val engine: InternalPlayerEngine?,
            val dropStale: Boolean
        )
        val mpvCarry = carry(engine = InternalPlayerEngine.MVP_PLAYER)
        val cases = listOf(
            Case("live carry beats persisted", mpvCarry, persisted, Channel.ENGINE_SWITCH_CARRY, carried, InternalPlayerEngine.MVP_PLAYER, false),
            Case("live carry alone", carry(), null, Channel.ENGINE_SWITCH_CARRY, carried, InternalPlayerEngine.EXOPLAYER, false),
            Case("stale carry dropped, persisted used", carry(streamUrl = "https://other/x.mkv"), persisted, Channel.PERSISTED, persisted, null, true),
            Case("stale carry dropped, nothing left", carry(streamUrl = "https://other/x.mkv"), null, null, null, null, true),
            Case("persisted alone", null, persisted, Channel.PERSISTED, persisted, null, false),
            Case("nothing", null, null, null, null, null, false),
        )
        for (case in cases) {
            val resolution = TrackSelectionResolver.resolveSource(case.carry, case.persisted, url)
            assertEquals(case.name, case.channel, resolution.source?.channel)
            assertEquals(case.name, case.preference, resolution.source?.preference)
            assertEquals(case.name, case.engine, resolution.source?.carrySourceEngine)
            assertEquals(case.name, case.dropStale, resolution.dropStaleCarry)
        }
    }

    // --- audio ---

    @Test
    fun `audio step - deferred, no match, already selected, select`() {
        var matcherCalls = 0
        val deferred = TrackSelectionResolver.audioStep(englishAudio, emptyList()) { matcherCalls++; 0 }
        assertEquals(AudioStep.Deferred, deferred)
        assertEquals("no tracks: matcher not consulted", 0, matcherCalls)

        val tracks = listOf(track(0, "fre"), track(1, "eng", selected = true), track(2, "eng"))
        assertEquals(AudioStep.NoMatch, TrackSelectionResolver.audioStep(englishAudio, tracks) { -1 })
        assertEquals(AudioStep.AlreadySelected(1), TrackSelectionResolver.audioStep(englishAudio, tracks) { 1 })
        assertEquals(AudioStep.Select(0), TrackSelectionResolver.audioStep(englishAudio, tracks) { 0 })
        assertEquals(AudioStep.Select(2), TrackSelectionResolver.audioStep(englishAudio, tracks) { 2 })
    }

    @Test
    fun `audio stays pending until a later update sees it selected`() {
        assertTrue(AudioStep.Deferred.keepsPending)
        assertTrue(AudioStep.Select(0).keepsPending)
        assertFalse(AudioStep.AlreadySelected(0).keepsPending)
        assertFalse(AudioStep.NoMatch.keepsPending)
    }

    // --- subtitles ---

    private fun subtitleStep(
        selection: RememberedSubtitleSelection,
        subtitleTracks: List<TrackInfo> = emptyList(),
        addonSubtitles: List<Subtitle> = emptyList(),
        addonSubtitlesLoading: Boolean = false,
        carrySourceEngine: InternalPlayerEngine? = null,
        usingMpv: Boolean = false,
        sparse: Boolean = false,
        match: Int = -1,
        seenEngine: MutableList<InternalPlayerEngine?> = mutableListOf()
    ): SubtitleStep = TrackSelectionResolver.subtitleStep(
        selection = selection,
        subtitleTracks = subtitleTracks,
        addonSubtitles = addonSubtitles,
        addonSubtitlesLoading = addonSubtitlesLoading,
        carrySourceEngine = carrySourceEngine,
        usingMpv = usingMpv,
        sparseMpvSubtitleMetadata = sparse,
        matchInternal = { _, engine -> seenEngine += engine; match }
    )

    @Test
    fun `disabled subtitle re-applies while a track is on and stays pending, else clears`() {
        val on = subtitleStep(RememberedSubtitleSelection.Disabled, listOf(track(0, "eng", selected = true)))
        assertEquals(SubtitleStep.Disable(reapply = true), on)
        assertTrue(on.keepsPending)

        val off = subtitleStep(RememberedSubtitleSelection.Disabled, listOf(track(0, "eng")))
        assertEquals(SubtitleStep.Disable(reapply = false), off)
        assertFalse(off.keepsPending)
    }

    @Test
    fun `internal subtitle - deferred, select, already selected`() {
        val internal = RememberedSubtitleSelection.Internal(RememberedTrackSelection(language = "eng", name = "English"))
        val deferred = subtitleStep(internal)
        assertEquals(SubtitleStep.InternalDeferred, deferred)
        assertTrue(deferred.keepsPending)

        val tracks = listOf(track(0, "eng"), track(1, "eng", selected = true))
        val select = subtitleStep(internal, tracks, match = 0)
        assertEquals(SubtitleStep.InternalSelect(0), select)
        assertFalse(select.keepsPending)

        val already = subtitleStep(internal, tracks, match = 1)
        assertEquals(SubtitleStep.InternalAlreadySelected(1), already)
        assertTrue("kept for a pipeline restart", already.keepsPending)
    }

    @Test
    fun `internal matcher gets the carry engine, null for persisted`() {
        val internal = RememberedSubtitleSelection.Internal(RememberedTrackSelection(language = "eng", name = "English"))
        val seen = mutableListOf<InternalPlayerEngine?>()
        val tracks = listOf(track(0, "eng"))
        subtitleStep(internal, tracks, carrySourceEngine = InternalPlayerEngine.EXOPLAYER, match = 0, seenEngine = seen)
        subtitleStep(internal, tracks, carrySourceEngine = null, match = 0, seenEngine = seen)
        assertEquals(listOf(InternalPlayerEngine.EXOPLAYER, null), seen)
    }

    @Test
    fun `internal no match defers only for an Exo carry onto sparse MPV metadata`() {
        val internal = RememberedSubtitleSelection.Internal(RememberedTrackSelection(language = "eng", name = "English"))
        val tracks = listOf(track(0, null, name = "Subtitle 1"))
        val exo = InternalPlayerEngine.EXOPLAYER
        val defer = subtitleStep(internal, tracks, carrySourceEngine = exo, usingMpv = true, sparse = true)
        assertEquals(SubtitleStep.InternalSparseMpvDefer, defer)
        assertTrue(defer.keepsPending)

        // Any one condition missing: no defer, falls through to the addon fallback / no match.
        val noDefer = listOf(
            subtitleStep(internal, tracks, carrySourceEngine = null, usingMpv = true, sparse = true),
            subtitleStep(internal, tracks, carrySourceEngine = InternalPlayerEngine.MVP_PLAYER, usingMpv = true, sparse = true),
            subtitleStep(internal, tracks, carrySourceEngine = exo, usingMpv = false, sparse = true),
            subtitleStep(internal, tracks, carrySourceEngine = exo, usingMpv = true, sparse = false),
        )
        noDefer.forEach { assertEquals(SubtitleStep.InternalNoMatch("en"), it) }

        // A match wins over the defer.
        assertEquals(
            SubtitleStep.InternalSelect(0),
            subtitleStep(internal, tracks, carrySourceEngine = exo, usingMpv = true, sparse = true, match = 0)
        )
    }

    @Test
    fun `internal no match falls back to an addon subtitle of the same language variant`() {
        val brazilian = RememberedSubtitleSelection.Internal(
            RememberedTrackSelection(language = "por", name = "Brazilian Portuguese")
        )
        val european = addon("os-1", "pt")
        val brazilianAddon = addon("os-2", "pt-br")
        val tracks = listOf(track(0, "eng"))

        val fallback = subtitleStep(brazilian, tracks, addonSubtitles = listOf(european, brazilianAddon))
        assertEquals(SubtitleStep.InternalAddonFallback(brazilianAddon, "pt-br"), fallback)
        assertFalse(fallback.keepsPending)

        val none = subtitleStep(brazilian, tracks, addonSubtitles = listOf(european))
        assertEquals(SubtitleStep.InternalNoMatch("pt-br"), none)
        assertFalse(none.keepsPending)
    }

    @Test
    fun `addon subtitle - exact match only, await the list, else clear`() {
        val wanted = RememberedSubtitleSelection.Addon(
            id = "os-7", url = "https://subs.example/os-7.srt", language = "en", addonName = "OpenSubtitles"
        )
        val exact = addon("os-7", "en")
        val sameLanguageOtherId = addon("os-8", "en")
        val sameIdOtherAddon = addon("os-7", "en", addonName = "SubDL")

        assertEquals(
            SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = false),
            subtitleStep(wanted, addonSubtitles = listOf(sameLanguageOtherId, sameIdOtherAddon, exact))
        )
        // Pending until MPV confirms: only a carry onto MPV.
        assertEquals(
            SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = true),
            subtitleStep(wanted, addonSubtitles = listOf(exact), carrySourceEngine = InternalPlayerEngine.EXOPLAYER, usingMpv = true)
        )
        assertEquals(
            SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = true),
            subtitleStep(wanted, addonSubtitles = listOf(exact), carrySourceEngine = InternalPlayerEngine.MVP_PLAYER, usingMpv = true)
        )
        assertEquals(
            SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = false),
            subtitleStep(wanted, addonSubtitles = listOf(exact), carrySourceEngine = null, usingMpv = true)
        )
        assertEquals(
            SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = false),
            subtitleStep(wanted, addonSubtitles = listOf(exact), carrySourceEngine = InternalPlayerEngine.MVP_PLAYER, usingMpv = false)
        )
        // An exact match wins while the list is still loading.
        assertEquals(
            SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = false),
            subtitleStep(wanted, addonSubtitles = listOf(exact), addonSubtitlesLoading = true)
        )

        val loading = subtitleStep(wanted, addonSubtitles = listOf(sameLanguageOtherId), addonSubtitlesLoading = true)
        assertEquals(SubtitleStep.AddonAwaitList, loading)
        assertTrue(loading.keepsPending)
        assertEquals(SubtitleStep.AddonAwaitList, subtitleStep(wanted, addonSubtitles = emptyList()))

        val noMatch = subtitleStep(wanted, addonSubtitles = listOf(sameLanguageOtherId, sameIdOtherAddon))
        assertEquals(SubtitleStep.AddonNoMatch, noMatch)
        assertFalse(noMatch.keepsPending)
    }

    @Test
    fun `addon select stays pending only until MPV confirms, and asks MPV only when it matters`() {
        val exact = addon("os-7", "en")
        var asked = 0
        val notWaiting = SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = false)
        assertFalse(notWaiting.keepsPending)
        assertFalse(notWaiting.keepsPendingAfterSelect { asked++; false })
        assertEquals("MPV not queried without a carry onto MPV", 0, asked)

        val waiting = SubtitleStep.AddonSelect(exact, keepUntilMpvConfirms = true)
        assertTrue(waiting.keepsPendingAfterSelect { false })
        assertFalse(waiting.keepsPendingAfterSelect { true })
    }

    // --- what stays pending ---

    @Test
    fun `remaining keeps only the fields still pending`() {
        val subtitle = RememberedSubtitleSelection.Disabled
        val both = TrackPreference(audio = englishAudio, subtitle = subtitle)
        assertEquals(both, TrackSelectionResolver.remaining(both, keepAudio = true, keepSubtitle = true))
        assertEquals(TrackPreference(audio = englishAudio), TrackSelectionResolver.remaining(both, keepAudio = true, keepSubtitle = false))
        assertEquals(TrackPreference(subtitle = subtitle), TrackSelectionResolver.remaining(both, keepAudio = false, keepSubtitle = true))
        assertNull(TrackSelectionResolver.remaining(both, keepAudio = false, keepSubtitle = false))
        assertNull(
            "an absent field is never invented",
            TrackSelectionResolver.remaining(TrackPreference(subtitle = subtitle), keepAudio = true, keepSubtitle = false)
        )
    }

    // --- defaults ---

    @Test
    fun `lossless audio default is blocked by any audio pick or a seen audio preference`() {
        fun blocked(remembered: Boolean = false, persisted: Boolean = false, seen: Boolean = false, carry: Boolean = false) =
            TrackSelectionResolver.defaultAudioBlocked(
                rememberedAudio = englishAudio.takeIf { remembered },
                persistedAudio = englishAudio.takeIf { persisted },
                persistedAudioSeenForContent = seen,
                carryAudio = frenchAudio.takeIf { carry }
            )
        assertFalse(blocked())
        assertTrue(blocked(remembered = true))
        assertTrue(blocked(persisted = true))
        assertTrue(blocked(seen = true))
        assertTrue(blocked(carry = true))
    }
}
