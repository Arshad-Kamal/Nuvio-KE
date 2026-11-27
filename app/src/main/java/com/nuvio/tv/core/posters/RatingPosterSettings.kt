package com.nuvio.tv.core.posters

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The active profile's rating-poster setting (Spec 114 P0-4). */
sealed interface RatingPosterState {
    /** Not read yet: cards that would use a rating poster wait, so they never load addon art first. */
    data object Loading : RatingPosterState
    data object Off : RatingPosterState
    data class On(val template: RatingPosterTemplate) : RatingPosterState
}

/**
 * The one place cards, prefetchers and the image client read the setting from (the AccessHeaders shape):
 * a static [StateFlow], so composables with no ViewModel can collect it. [RatingPosterSettings] feeds it.
 */
object RatingPosters {
    private val mutableState = MutableStateFlow<RatingPosterState>(RatingPosterState.Loading)
    val state: StateFlow<RatingPosterState> = mutableState.asStateFlow()

    /** The template in use now, or null while Loading or Off. */
    val activeTemplate: RatingPosterTemplate?
        get() = (state.value as? RatingPosterState.On)?.template

    internal fun publish(state: RatingPosterState) {
        mutableState.value = state
    }
}

/**
 * Per-profile store (feature [FEATURE], not in the profile-settings sync list, so the template and its
 * key never leave this TV; the profile's files go with the profile and on sign-out). Built at app start
 * by the ImageLoader provider, it publishes the active profile's state to [RatingPosters].
 */
@Singleton
class RatingPosterSettings @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ratingPosterStates(profileManager.storedActiveProfileId, ::templateFor)
            .onEach(RatingPosters::publish)
            .launchIn(scope)
    }

    private fun templateFor(profileId: Int): Flow<String?> =
        factory.get(profileId, FEATURE).data.map { it[TEMPLATE_KEY] }

    /**
     * Saves [template] (already checked, P0-7) for [profileId], the profile the caller captured when the
     * action began, so a switch during the save check never writes one profile's key into another's store.
     */
    suspend fun save(profileId: Int, template: RatingPosterTemplate) {
        factory.get(profileId, FEATURE).edit { it[TEMPLATE_KEY] = template.raw }
    }

    suspend fun clear(profileId: Int) {
        factory.get(profileId, FEATURE).edit { it.remove(TEMPLATE_KEY) }
    }

    companion object {
        const val FEATURE = "rating_posters"
        private val TEMPLATE_KEY = stringPreferencesKey("template")
    }
}

internal const val FIRST_READ_TIMEOUT_MS = 2_000L
internal const val READ_RETRY_MS = 30_000L

/**
 * The state for whichever profile is active. A null profile id (not loaded yet) is Loading, never the
 * default profile's setting. Per profile: Loading first, then the stored template; a read error is Off
 * (an IO error reads again after [READ_RETRY_MS]) without ending later profile switches; a first read slower than [firstReadTimeoutMs] shows Off until it
 * lands, and then it still wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun ratingPosterStates(
    profileIds: Flow<Int?>,
    templateFor: (Int) -> Flow<String?>,
    firstReadTimeoutMs: Long = FIRST_READ_TIMEOUT_MS,
): Flow<RatingPosterState> =
    profileIds.distinctUntilChanged().flatMapLatest { profileId ->
        if (profileId == null) flowOf(RatingPosterState.Loading)
        else profileState(templateFor(profileId), firstReadTimeoutMs)
    }.distinctUntilChanged()

private fun profileState(stored: Flow<String?>, firstReadTimeoutMs: Long): Flow<RatingPosterState> = channelFlow {
    send(RatingPosterState.Loading)
    // One decision point: on IO the timer and the first value can land on two threads at once, and a
    // cancelled timer already inside send(Off) could otherwise put a stale Off after On.
    val gate = Mutex()
    var settled = false
    val slowRead = launch {
        delay(firstReadTimeoutMs)
        gate.withLock { if (!settled) send(RatingPosterState.Off) }
    }
    stored.map(::stateOf)
        // A failed read publishes Off and reads again later, so a template saved afterwards still shows.
        .retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            emit(RatingPosterState.Off)
            delay(READ_RETRY_MS)
            true
        }
        .catch { emit(RatingPosterState.Off) }
        .collect { state ->
            gate.withLock {
                settled = true
                slowRead.cancel()
                send(state)
            }
        }
}

/** A stored template that no longer parses (rules tightened later) reads as Off. */
private fun stateOf(raw: String?): RatingPosterState {
    if (raw.isNullOrBlank()) return RatingPosterState.Off
    return when (val parsed = RatingPosterTemplate.parse(raw)) {
        is RatingPosterTemplate.Parsed.Valid -> RatingPosterState.On(parsed.template)
        is RatingPosterTemplate.Parsed.Refused -> RatingPosterState.Off
    }
}
