package com.nuvio.tv.core.contentfilter

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.activeProfileIdOrNull
import com.nuvio.tv.data.local.ContentFilterRead
import com.nuvio.tv.data.local.ProfileContentFilterStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.contentfilter.BuiltInPacks
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a gate applies (Spec 122 V1): [filter] hides; with [preview] a hit is kept and marked instead
 * (Spec 122; [filter] may then be null: preview on, nothing caught). [ownPackNames] names the profile's
 * own packs for the card badge (own pack id -> name). [previewProfileId]: the profile previewed (the
 * top-bar chip names it, 122/D4), null unless [preview].
 */
data class ActiveFilter(
    val filter: ContentFilter?,
    val preview: Boolean,
    val ownPackNames: Map<String, String> = emptyMap(),
    val previewProfileId: Int? = null
)

/**
 * The active profile's content filter (Spec 117 P1), read once per call like [com.nuvio.tv.core.profile.ActiveRatingCap]:
 * surfaces read it when they load (Home when it draws, P5b) and a profile switch rebuilds them.
 *
 * Fails closed as the cap does (64/D1): an unreadable active id or unreadable settings turn every
 * built-in pack on. Preview (Spec 122 D3): only while the primary profile is active and its settings
 * name a profile that exists does the primary get that profile's filter with `preview = true`; any
 * other active profile gets its own filter, never a preview. Compiled filters are cached per settings
 * value in two slots (active + previewed); `preview` is worked out on every call, never cached (N1). The built-in packs come from the APK's pack file,
 * read once; it is covered by PackFileTest, so a parse failure is a build defect and throws.
 */
@Singleton
class ActiveContentFilter internal constructor(
    private val profileDataStore: ProfileDataStore,
    private val store: ProfileContentFilterStore,
    private val readPackFile: () -> String,
    private val io: CoroutineDispatcher
) {
    @Inject
    constructor(
        profileDataStore: ProfileDataStore,
        store: ProfileContentFilterStore,
        @ApplicationContext context: Context
    ) : this(
        profileDataStore,
        store,
        { context.assets.open(BuiltInPacks.ASSET_NAME).bufferedReader().use { it.readText() } },
        Dispatchers.IO
    )

    private class Compiled(val read: ContentFilterRead, val filter: ContentFilter?, val ownPackNames: Map<String, String>)

    private val builtInLock = Mutex()
    @Volatile private var builtIns: List<FilterPack>? = null
    // Most recent first, at most two; swapped whole, so a racing call loses at most a cache entry.
    @Volatile private var compiled: List<Compiled> = emptyList()

    /**
     * What the gates apply: null when the active profile filters nothing and is not previewing. While
     * previewing, non-null even when the previewed profile filters nothing (the top-bar chip still shows).
     */
    suspend fun current(): ActiveFilter? {
        val profileId = profileDataStore.activeProfileIdOrNull()
        val read = if (profileId == null) ContentFilterRead.Unreadable else store.settingsFor(profileId).first()
        if (profileId == ProfileContentFilterStore.PRIMARY_PROFILE_ID) {
            previewedRead(read)?.let { previewed ->
                val compiled = compiled(previewed)
                return ActiveFilter(
                    compiled.filter,
                    preview = true,
                    ownPackNames = compiled.ownPackNames,
                    previewProfileId = previewIdOf(profileId, read)
                )
            }
        }
        val compiled = compiled(read)
        return compiled.filter?.let { ActiveFilter(it, preview = false, ownPackNames = compiled.ownPackNames) }
    }

    /**
     * Emits when what [current] returns may have changed (Home redraws on it, 117/P5b): the active
     * profile id, its settings and, while the primary previews another profile, that profile's
     * settings, followed anew when the previewed id changes (122/V2 N3). Emits the current inputs on
     * collection. An unreadable active id ends the flow (the draws fail closed on their own reads).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settingsChanges(): Flow<Unit> = profileDataStore.activeProfileId
        .catch { e -> if (e !is IOException) throw e }
        .distinctUntilChanged()
        .flatMapLatest { profileId ->
            store.settingsFor(profileId).flatMapLatest { read ->
                val previewId = previewIdOf(profileId, read)?.takeIf { it != profileId }
                if (previewId == null) {
                    flowOf(FilterInputs(profileId, read, previewed = null))
                } else {
                    store.settingsFor(previewId)
                        // R2: that profile's store is being cleared (its deletion is in progress).
                        .catch { e -> if (e !is CancellationException) throw e }
                        .map { FilterInputs(profileId, read, it) }
                        // Always one value per input set, so a collector may skip the first as the state it read.
                        .onEmpty { emit(FilterInputs(profileId, read, previewed = null)) }
                }
            }
        }
        .distinctUntilChanged()
        .map { }

    /**
     * [current] on collection and again whenever [settingsChanges] emits: for what the whole app shows
     * at once (own-pack names for the card marks, the preview chip; Spec 122 D4, D5), not for gates.
     * A read cancelled by the active profile's store being cleared (its deletion) emits null and the
     * flow goes on (the switch that follows emits again), unless the collector itself was cancelled.
     */
    fun states(): Flow<ActiveFilter?> = settingsChanges().map {
        try {
            current()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private data class FilterInputs(val profileId: Int, val read: ContentFilterRead, val previewed: ContentFilterRead?)

    /** The settings the primary previews, or null when preview is off or names no existing profile (N2). */
    private suspend fun previewedRead(primary: ContentFilterRead): ContentFilterRead? {
        val previewId = previewIdOf(ProfileContentFilterStore.PRIMARY_PROFILE_ID, primary) ?: return null
        if (previewId == ProfileContentFilterStore.PRIMARY_PROFILE_ID) return primary
        val exists = try {
            profileDataStore.profilesList.first().any { it.id == previewId }
        } catch (e: IOException) {
            false
        }
        if (!exists) return null
        return try {
            store.settingsFor(previewId).first()
        } catch (e: CancellationException) {
            // R2: that profile's store is being cleared (its deletion is in progress); preview is off,
            // unless this call itself was cancelled.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** The profile [profileId]'s settings preview, when it is the primary (Spec 122 V1); the only home of that rule. */
    private fun previewIdOf(profileId: Int, read: ContentFilterRead): Int? =
        if (profileId != ProfileContentFilterStore.PRIMARY_PROFILE_ID) null
        else (read as? ContentFilterRead.Stored)?.settings?.previewProfileId

    private suspend fun compiled(read: ContentFilterRead): Compiled {
        compiled.firstOrNull { it.read == read }?.let { return it }
        val entry = Compiled(read, compile(read), ownPackNames(read))
        compiled = listOf(entry) + compiled.take(1)
        return entry
    }

    private fun ownPackNames(read: ContentFilterRead): Map<String, String> = when (read) {
        ContentFilterRead.Unreadable -> emptyMap()
        is ContentFilterRead.Stored -> read.settings.ownPacks
            .filter { it.hasOwnPackId } // as compile: an unprefixed id never names a built-in pack
            .associate { it.id to it.name }
    }

    /** The built-in packs in file order (the editor lists them by localized name itself). */
    suspend fun builtInPacks(): List<FilterPack> = builtIns ?: builtInLock.withLock {
        builtIns ?: withContext(io) { BuiltInPacks.parse(readPackFile()) }.also { builtIns = it }
    }

    private suspend fun compile(read: ContentFilterRead): ContentFilter? {
        val settings = when (read) {
            ContentFilterRead.Unreadable -> return ContentFilter(builtInPacks())
            is ContentFilterRead.Stored -> read.settings
        }
        val packs = buildList {
            if (settings.enabledPacks.isNotEmpty()) {
                val builtIn = builtInPacks()
                addAll(builtIn.filter { it.id in settings.enabledPacks })
                val known = builtIn.mapTo(HashSet()) { it.id } + settings.ownPacks.map { it.id }
                val unknown = settings.enabledPacks.count { it !in known }
                if (unknown > 0) Log.w(TAG, "Ignored $unknown unknown content filter pack id(s)")
            }
            // Only entries that can match keep the contract "null when nothing is filtered".
            val myWords = settings.myWords.filter(::matchable)
            if (myWords.isNotEmpty()) add(FilterPack(ContentFilterSettings.MY_WORDS_ID, myWords.map { PackWord(it) }))
            settings.ownPacks.forEach { own ->
                when {
                    own.id !in settings.enabledPacks -> Unit
                    !own.hasOwnPackId -> Log.w(TAG, "Ignored an own pack without the own-pack id prefix")
                    else -> own.toFilterPack().let { pack ->
                        if (pack.words.isNotEmpty() || pack.genreIds.isNotEmpty() ||
                            pack.genreNames.isNotEmpty() || pack.keywordIds.isNotEmpty()
                        ) add(pack)
                    }
                }
            }
        }
        // Building the index is small (a few hundred words); only the pack file read leaves the caller's thread.
        return if (packs.isEmpty()) null else ContentFilter(packs)
    }

    private companion object {
        const val TAG = "ActiveContentFilter"

        fun matchable(word: String): Boolean = ContentFilter.tokenize(word).isNotEmpty()
    }
}
