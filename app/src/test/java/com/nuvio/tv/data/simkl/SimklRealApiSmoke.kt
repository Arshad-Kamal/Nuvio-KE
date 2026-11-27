package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.server.PasteCheck
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingAccountStore
import com.nuvio.tv.core.tracking.TrackingDevicePoll
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.domain.model.LibraryEntryInput
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 106's real-API smoke, owed before Close (D5, D9): the production Simkl classes on a real OkHttp client against
 * api.simkl.com, with Arshad's own app id and a throwaway Simkl account. Skipped unless [CONFIG] exists, so it never
 * runs in the normal suites. The file lives outside the repo (never tracked; AGENTS.md § Security rules):
 *
 *     client_id=<the Simkl app id, as pasted on the TV (D9)>
 *     throwaway_account=yes   # the run marks, unmarks, adds and removes titles on the signed-in account
 *
 * The first run signs in by Simkl's device flow: the code and address go to [REPORT]; open simkl.com/pin on a phone
 * signed in to the throwaway account and enter it (15 min). The tokens are then kept in [CONFIG] (mode 600) for later
 * runs. Run detached, the stall watchdog off (the sign-in waits idle):
 *
 *     GRADLEW_SERIAL_STALL_MIN=0 gradlew-serial :app:testFullDebugUnitTest \
 *         --tests 'com.nuvio.tv.data.simkl.SimklRealApiSmoke' --rerun
 *
 * Steps (Spec 106: D9; S5 as built § Close smoke; S4a; F1-F3 as built), each PASS/FAIL in [REPORT]:
 * D9 the pasted id passes the app-id check and a made-up one is refused; S5 three entry-numbered anime episodes are
 * marked then unmarked on their own Simkl entries with the sibling entry untouched; S4a related titles and the page
 * address for one film; F1 a film added, moved and removed through the list picker's calls.
 */
class SimklRealApiSmoke {
    @get:Rule val folder = TemporaryFolder()

    private var failed = false

    @Test
    fun smoke() {
        assumeTrue("no $CONFIG: the real-API smoke is skipped", CONFIG.isFile)
        val props = Properties().apply { CONFIG.inputStream().use(::load) }
        val clientId = props.getProperty("client_id")?.trim().orEmpty()
        check(clientId.isNotEmpty()) { "$CONFIG has no client_id" }
        check(props.getProperty("throwaway_account") == "yes") { "$CONFIG must say throwaway_account=yes (the run writes to the account)" }
        REPORT.writeText("Spec 106 real-API smoke, ${java.time.Instant.now()}\n")
        runBlocking { run(props, clientId) }
        assertTrue("smoke failed: see $REPORT", !failed)
    }

    private suspend fun run(props: Properties, clientId: String) {
        val config = SimklApiConfiguration(clientId = { clientId }, appVersion = "smoke")
        val http = OkHttpClient()
        val client = SimklClient(OkHttpSimklEngine(http), config)
        val accounts = accountStore()
        val quota = SimklQuota()
        val session = SimklSession(client, SimklTokens(client, config, accounts), quota)
        val store = SimklSyncStore(folder.newFolder("sync"), Dispatchers.IO)
        val pending = SimklPendingMarks(folder.newFolder("pending"), Dispatchers.IO)
        val writer = SimklHistoryWriter(session, pending, logRefused = {}, logHeld = {}, snapshotOf = store::get)
        val sync = SimklSync(session, store, accounts, replayPending = writer::replayPending)
        val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val related = SimklRelated(SimklClient(OkHttpSimklEngine(noRedirects), config), config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = SimklTrackingLibraryProvider(store, sync, writer, MutableStateFlow(PROFILE), scope, Dispatchers.Default)
        try {
            // D9: the check K3 runs before saving a pasted id.
            step("D9 pasted id accepted") { checkSimklAppId(client, clientId).also { it == PasteCheck.Saved || fail("got $it") } }
            step("D9 made-up id refused") { checkSimklAppId(client, "nuviosmoke-not-an-id").also { it == PasteCheck.Refused || fail("got $it") } }

            signIn(props, client, config, accounts, session, store) ?: return
            step("sync settles") { sync.refresh(PROFILE, TrackingRefreshIntent.USER_INITIATED) || fail("not settled") }

            ANIME_CASES.forEach { case -> animeCase(case, session, writer, sync) }

            step("S4a related titles for Inception") {
                related.related(INCEPTION, "movie", SimklAnimeSeasons.PER_SHOW).also { it.isNotEmpty() || fail("empty") }
                    .take(5).joinToString { it.name }
            }
            step("F1 link back for Inception") {
                related.pageAddress(INCEPTION, "movie")?.also { it.startsWith("simkl.com/movies/") || fail(it) } ?: fail("null")
            }

            filmLists(library, session, sync)
        } finally {
            scope.cancel()
            saveTokens(props, accounts.get(PROFILE, TrackingProviderId.SIMKL))
        }
    }

    /** Stored tokens, else Simkl's device flow (the code goes to [REPORT]); null when no sign-in happened. */
    private suspend fun signIn(
        props: Properties,
        client: SimklClient,
        config: SimklApiConfiguration,
        accounts: TrackingAccountStore,
        session: SimklSession,
        store: SimklSyncStore
    ): Unit? {
        props.getProperty("access_token")?.takeIf(String::isNotBlank)?.let { access ->
            accounts.save(
                PROFILE, TrackingProviderId.SIMKL,
                TrackingAccount(access, props.getProperty("refresh_token"), props.getProperty("expires_at")?.toLongOrNull())
            )
            note("signed in with the stored tokens")
            return Unit
        }
        val auth = SimklDeviceAuth(client, config, accounts, session, onGrantChange = { store.clear(it) })
        val code = auth.start(PROFILE)
        note("SIGN IN: open ${code.verificationUriComplete ?: code.verificationUri} and enter ${code.userCode} (until ${java.time.Instant.ofEpochMilli(code.expiresAtEpochMs)})")
        var interval = code.intervalSeconds * 1_000L
        while (System.currentTimeMillis() < code.expiresAtEpochMs) {
            delay(interval)
            when (auth.poll(PROFILE, code)) {
                TrackingDevicePoll.Approved -> {
                    saveTokens(props, accounts.get(PROFILE, TrackingProviderId.SIMKL))
                    note("signed in (${accounts.get(PROFILE, TrackingProviderId.SIMKL)?.accountName})")
                    return Unit
                }
                TrackingDevicePoll.SlowDown -> interval += 5_000L
                TrackingDevicePoll.Expired -> break
                else -> Unit
            }
        }
        record(false, "sign-in", "expired before approval")
        return null
    }

    /** S5's Close smoke: mark then unmark episode 1 by its per-entry video id; the sibling entry never changes. */
    private suspend fun animeCase(case: AnimeCase, session: SimklSession, writer: SimklHistoryWriter, sync: SimklSync) {
        val media = buildTrackingMediaReference("series", case.parent, videoId = case.videoId, seasonNumber = 1, episodeNumber = 1)
        val before = animeWatched(session)
        if (before[case.entry].orEmpty().contains(1)) return record(false, "S5 ${case.videoId}", "account not clean: ${case.entry} ep 1 already watched")
        step("S5 ${case.videoId} mark lands on ${case.entry}") {
            writer.addToHistory(PROFILE, listOf(TrackingHistoryItem(media, System.currentTimeMillis())))
            val after = animeWatched(session)
            (after[case.entry].orEmpty().contains(1)) || fail("${case.entry} ep 1 not watched; watched now ${after.filterValues { it.isNotEmpty() }}")
            (after[case.sibling] == before[case.sibling]) || fail("sibling ${case.sibling} changed: ${before[case.sibling]} -> ${after[case.sibling]}")
        }
        step("S5 ${case.videoId} unmark clears ${case.entry}") {
            sync.refresh(PROFILE, TrackingRefreshIntent.USER_INITIATED) || fail("sync before the unmark not settled")
            writer.removeFromHistory(PROFILE, listOf(media))
            val after = animeWatched(session)
            !after[case.entry].orEmpty().contains(1) || fail("${case.entry} ep 1 still watched")
            (after[case.sibling] == before[case.sibling]) || fail("sibling ${case.sibling} changed: ${before[case.sibling]} -> ${after[case.sibling]}")
        }
    }

    /** F1-F3's calls on one film: add onto Plan to watch, move to Dropped, remove with the confirm's consent. */
    private suspend fun filmLists(library: SimklTrackingLibraryProvider, session: SimklSession, sync: SimklSync) {
        val film = LibraryEntryInput(itemId = INCEPTION, itemType = "movie", title = "Inception", releaseInfo = "2010")
        if (filmStatus(session) != null) return record(false, "F1", "account not clean: Inception is on a Simkl list")
        step("F1 add onto Plan to watch") {
            library.moveToList(PROFILE, film, TrackingListStatus.PLAN_TO_WATCH).also { it == null || fail("was on $it") }
            filmStatus(session).also { it == "plantowatch" || fail("Simkl has $it") }
        }
        step("F1 move to Dropped") {
            library.moveToList(PROFILE, film, TrackingListStatus.DROPPED).also { it == TrackingListStatus.PLAN_TO_WATCH || fail("was on $it") }
            filmStatus(session).also { it == "dropped" || fail("Simkl has $it") }
        }
        step("F1b removal asks first, then goes with consent") {
            sync.refresh(PROFILE, TrackingRefreshIntent.USER_INITIATED) || fail("not settled")
            val agreed = library.listing(PROFILE, film)
            agreed.removalNeedsConfirm || fail("Dropped should ask first: $agreed")
            runCatching { library.removeFromSimkl(PROFILE, film, agreed = null) }
                .exceptionOrNull().let { it is SimklRemovalNeedsConfirmException || fail("unconfirmed removal gave $it") }
            library.removeFromSimkl(PROFILE, film, agreed)
            filmStatus(session).also { it == null || fail("Simkl still has $it") }
        }
    }

    /** Simkl's anime entries as all-items gives them now: Simkl id to the watched episode numbers. */
    private suspend fun animeWatched(session: SimklSession): Map<Long, Set<Int>> {
        val body = session.call(PROFILE, SimklRequest(SimklHttpMethod.GET, "/sync/all-items/anime", FULL_QUERY)).body
        return decodeAllItems(body).mapNotNull { entry ->
            val id = entry.media?.simklId ?: return@mapNotNull null
            id to entry.seasons.flatMap { season -> season.episodes.filter { it.watchedAt != null }.mapNotNull { it.number } }.toSet()
        }.toMap()
    }

    /** Inception's Simkl list now, from all-items (null = on none). */
    private suspend fun filmStatus(session: SimklSession): String? {
        val body = session.call(PROFILE, SimklRequest(SimklHttpMethod.GET, "/sync/all-items/movies")).body
        return decodeAllItems(body).firstOrNull { it.media?.id("imdb") == INCEPTION }?.status?.apiValue
    }

    private suspend fun step(name: String, block: suspend () -> Any?) {
        try {
            record(true, name, block()?.toString().orEmpty())
        } catch (failure: Throwable) {
            record(false, name, "${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    private fun record(passed: Boolean, name: String, detail: String) {
        if (!passed) failed = true
        note("${if (passed) "PASS" else "FAIL"} $name${if (detail.isNotEmpty()) " ($detail)" else ""}")
    }

    private fun note(line: String) {
        REPORT.appendText("$line\n")
    }

    private fun fail(why: String): Nothing = throw AssertionError(why)

    /** Back into [CONFIG] (owner-only), so the next run needs no sign-in. Never printed. */
    private fun saveTokens(props: Properties, account: TrackingAccount?) {
        account ?: return
        props.setProperty("access_token", account.accessToken)
        account.refreshToken?.let { props.setProperty("refresh_token", it) }
        account.expiresAtEpochMs?.let { props.setProperty("expires_at", it.toString()) }
        CONFIG.outputStream().use { props.store(it, "Spec 106 real-API smoke (local only, never tracked)") }
        Files.setPosixFilePermissions(CONFIG.toPath(), PosixFilePermissions.fromString("rw-------"))
    }

    /** An entry-numbered anime episode ([videoId]) whose watch belongs on [entry], never on its [sibling] (S5). */
    private data class AnimeCase(val parent: String, val videoId: String, val entry: Long, val sibling: Long)

    private companion object {
        const val PROFILE = 1
        const val INCEPTION = "tt1375666"
        val DIR = File(System.getProperty("user.home"), ".config/nuvio-ke")
        val CONFIG = File(DIR, "simkl-smoke.properties")
        val REPORT = File(DIR, "simkl-smoke-report.txt")

        /** Spec 106 S5 as built § Close smoke: AoT Final Season Part 2, Re:Zero S2 Part 2, Mushoku Tensei II Part 2. */
        val ANIME_CASES = listOf(
            AnimeCase("kitsu:44240", "kitsu:44240:1", entry = 1579947, sibling = 1120029),
            AnimeCase("mal:42203", "mal:42203:1", entry = 1367345, sibling = 1063491),
            AnimeCase("mal:50602", "mal:50602:1", entry = 1809622, sibling = 1766469)
        )

        /** SimklSync's full read: episode rows with their watch times. */
        val FULL_QUERY = mapOf("extended" to "full_anime_seasons", "episode_watched_at" to "yes", "include_all_episodes" to "yes")
    }
}
