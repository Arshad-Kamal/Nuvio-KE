package com.nuvio.tv.core.report

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.access.AccessHeaderStore
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.source.SourceStore
import com.nuvio.tv.livetv.source.XtreamAccountStore
import com.nuvio.tv.livetv.source.XtreamLogin
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/** What the scrub needs (D6 steps 2-3): the known values and the installed addon hosts with names. */
data class ReportSecrets(val knownValues: Set<String>, val addonHosts: Map<String, String?>)

/**
 * Spec 116 D6 step 3's sources, raw values only (the derivation is [ReportScrub.knownValuesFrom]):
 * every profile's installed addon URLs, Live TV sources and their header guides, Xtream logins
 * (Spec 109), access headers, the TMDB user and built-in keys, and the Supabase session tokens. A new stored URL or credential in the
 * app belongs here (`app/AGENTS.md`). Not sources: MDBList, Trakt and debrid keys (stores removed,
 * `LegacyFeatureDataCleanup`), TorrServer (no stored URL). Spec 114's rating-poster template joins
 * once Spec 114 lands.
 */
class ReportSecretSources @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val addonPreferences: AddonPreferences,
    private val addonRepository: AddonRepository,
    private val sourceStore: SourceStore,
    private val xtreamAccounts: XtreamAccountStore,
    private val accessHeaderStore: AccessHeaderStore,
    private val tmdbKeys: TmdbApiKeyProvider,
    private val auth: Auth,
) {

    suspend fun read(): ReportSecrets = withContext(Dispatchers.IO) {
        // ProfileDataStore, never ProfileManager.profiles.value (its initial value is a placeholder).
        val addonUrls = profileDataStore.profilesList.first().flatMap { addonPreferences.getStoredAddonUrls(it.id) }
        // The active profile's addons, in install order, carry the names; other profiles' hosts get none.
        val named = addonRepository.getInstalledAddons().first().map { it.baseUrl to it.displayName }
        sourceStore.load()
        val liveUrls = sourceStore.sources.value.flatMap { listOf(it.url) + it.headerGuides?.urls.orEmpty() }
        val sites = accessHeaderStore.snapshot()
        val session = auth.currentSessionOrNull()
        assemble(
            addonUrls = addonUrls,
            namedAddons = named,
            liveUrls = liveUrls,
            xtreamLogins = xtreamAccounts.all(),
            accessHosts = sites.map { it.host },
            credentials = sites.flatMap { site -> site.headers.map { it.value } } +
                listOfNotNull(
                    // Null until the key store's first read, which settles even on a failed read.
                    withTimeoutOrNull(KEY_WAIT_MS) { tmdbKeys.state.filterNotNull().first() }?.userKey,
                    BuildConfig.TMDB_API_KEY,
                    session?.accessToken,
                    session?.refreshToken,
                ),
        )
    }

    internal companion object {
        /** Path words before an Xtream account (`host/live/USER/PASS/1.ts`, `host/playlist/USER/PASS/m3u_plus`). */
        private val XTREAM_WORDS = setOf("live", "movie", "series", "playlist", "get", "timeshift")
        private const val MIN_LOGIN = 6
        private const val KEY_WAIT_MS = 1_000L

        fun assemble(
            addonUrls: List<String>,
            namedAddons: List<Pair<String, String>>,
            liveUrls: List<String>,
            accessHosts: List<String>,
            credentials: List<String>,
            xtreamLogins: List<XtreamLogin>,
        ): ReportSecrets {
            val addonHosts = LinkedHashMap<String, String?>()
            for ((url, name) in namedAddons) hostOf(url)?.let { addonHosts.putIfAbsent(it, name.ifBlank { null }) }
            for (url in addonUrls) hostOf(url)?.let { addonHosts.putIfAbsent(it, null) }
            // A playlist's login sits inside its URL (no separate fields); an Xtream login source keeps
            // its own (Spec 109). D6 step 3 passes both as credentials.
            val logins = liveUrls.flatMap(::xtreamLogins) + xtreamLogins.flatMap { listOf(it.username, it.password) }
            val known = ReportScrub.knownValuesFrom(
                storedUrls = addonUrls + liveUrls + accessHosts.map { "https://$it" },
                credentials = credentials.filter { it.isNotBlank() } + logins,
                addonHosts = addonHosts.keys,
            )
            return ReportSecrets(known, addonHosts)
        }

        /**
         * The two path segments after an Xtream path word, at [MIN_LOGIN]+ chars in any case, whatever
         * their shape: a numeric or dotted login is still a login (r2 `fd50e338`).
         */
        fun xtreamLogins(url: String): List<String> {
            val segments = url.toHttpUrlOrNull()?.pathSegments ?: return emptyList()
            val out = ArrayList<String>(2)
            segments.forEachIndexed { i, segment ->
                if (segment.lowercase() in XTREAM_WORDS) {
                    segments.subList(i + 1, minOf(i + 3, segments.size)).filterTo(out) { it.length >= MIN_LOGIN }
                }
            }
            return out
        }

        private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host
    }
}
