package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Typeface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.SubtitleFontServer
import com.nuvio.tv.core.server.SubtitleFontWebPage
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.UserSubtitleFont
import com.nuvio.tv.data.local.UserSubtitleFontAddResult
import com.nuvio.tv.data.local.UserSubtitleFontStore
import com.nuvio.tv.data.local.loadUserSubtitleTypeface
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A sent font goes to the store as sent; the phone page answers with the store's result. */
internal typealias AddSubtitleFont = suspend (fileName: String, bytes: ByteArray) -> UserSubtitleFontAddResult

/** A running font page and its QR address. */
internal class StartedFontServer(val server: SubtitleFontServer, val url: String)

/** Starts the subtitle font phone page on this TV's LAN address; null with no network or no free port. */
class SubtitleFontServerStarter @Inject constructor(@ApplicationContext private val context: Context) {
    internal fun start(fontCount: () -> Int, addFont: AddSubtitleFont): StartedFontServer? {
        val ip = DeviceIpAddress.get(context) ?: return null
        val server = SubtitleFontServer.startOnAvailablePort(
            pageHtml = { SubtitleFontWebPage.html(context) },
            fontCount = fontCount,
            addFont = addFont
        ) ?: return null
        return StartedFontServer(server, server.url(ip))
    }
}

/**
 * Spec 132 S4: Playback › Subtitles › Font (sandbox `spec-132-subtitle-fonts` stages 1, 2, 4, 6, 7, 9).
 * The panel lists Default and the TV's fonts (each drawn in its own face, loaded only while the
 * panel is open), picks one for this profile, removes one behind a confirm, and runs the phone page
 * while it is open, at most [phoneLifetime]. At five fonts the page keeps running and answers
 * "full" (a phone that loaded it earlier is told why); the panel shows the limit instead of the QR.
 * Main thread, except the phone's sends.
 */
@HiltViewModel
class SubtitleFontsViewModel internal constructor(
    private val store: UserSubtitleFontStore,
    private val settings: PlayerSettingsDataStore,
    private val startPhone: (fontCount: () -> Int, addFont: AddSubtitleFont) -> StartedFontServer?,
    private val loadTypeface: (File) -> Typeface?,
    private val io: CoroutineDispatcher,
    private val phoneLifetime: Duration
) : ViewModel() {

    @Inject
    constructor(
        store: UserSubtitleFontStore,
        settings: PlayerSettingsDataStore,
        starter: SubtitleFontServerStarter
    ) : this(store, settings, starter::start, ::loadUserSubtitleTypeface, Dispatchers.IO, PHONE_LIFETIME)

    /** Oldest first (a sent font joins the end); null until the store has read its folder. */
    val fonts: StateFlow<List<UserSubtitleFont>?> = store.fonts

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    /** The font the remove confirm asks about, shown in the panel's place; null = none. */
    private val _confirm = MutableStateFlow<UserSubtitleFont?>(null)
    val confirm: StateFlow<UserSubtitleFont?> = _confirm.asStateFlow()

    private val _phoneUrl = MutableStateFlow<String?>(null)
    val phoneUrl: StateFlow<String?> = _phoneUrl.asStateFlow()

    /** Each listed font's face by id while the panel is open; a font Android can't load has none. */
    private val _typefaces = MutableStateFlow<Map<String, Typeface>>(emptyMap())
    val typefaces: StateFlow<Map<String, Typeface>> = _typefaces.asStateFlow()

    private var phoneServer: SubtitleFontServer? = null
    private var phoneLifetimeJob: Job? = null
    private var typefaceJob: Job? = null
    private var removeJob: Job? = null
    /** Ids whose face Android could not load this panel session: not tried again until it reopens. */
    private val unloadable = mutableSetOf<String>()

    init {
        // A sent or removed font changes the list; the open panel loads the new face.
        viewModelScope.launch { store.fonts.collect { if (_panelOpen.value) loadTypefaces(it) } }
    }

    fun openPanel() {
        if (_panelOpen.value) return
        _panelOpen.value = true
        _confirm.value = null
        openPhone()
        loadTypefaces(store.fonts.value)
    }

    /** Back, a pick, ON_STOP or leaving the pane: the phone page ends with the panel. */
    fun closePanel() {
        _panelOpen.value = false
        _confirm.value = null
        typefaceJob?.cancel()
        typefaceJob = null
        _typefaces.value = emptyMap()
        unloadable.clear()
        closePhone()
    }

    /** OK on a row: [font] (null = Default) for this profile, then the panel closes. */
    fun pick(font: UserSubtitleFont?) {
        viewModelScope.launch { settings.setSubtitleFont(font?.id, font?.family) }
        closePanel()
    }

    /** Hold OK (or MENU) on a font row. */
    fun askRemove(font: UserSubtitleFont) {
        if (_panelOpen.value) _confirm.value = font
    }

    fun cancelConfirm() {
        _confirm.value = null
    }

    /**
     * Deletes the asked font from this TV. This profile's choice of it goes back to Default first
     * (so its row never shows the font as gone), and comes back if the removal fails; another
     * profile's choice stays and its row says the font is gone (stage 9).
     */
    fun confirmRemove() {
        val font = _confirm.value ?: return
        if (removeJob?.isActive == true) return
        removeJob = viewModelScope.launch {
            val chosen = settings.playerSettings.first().subtitleStyle.fontId == font.id
            if (chosen) settings.setSubtitleFont(null, null)
            // A failed removal leaves the font in the list (no separate error state is drawn).
            if (!store.remove(font.id) && chosen && settings.playerSettings.first().subtitleStyle.fontId == null) {
                // Unless a pick landed meanwhile.
                settings.setSubtitleFont(font.id, font.family)
            }
            if (_confirm.value == font) _confirm.value = null
        }
    }

    private fun loadTypefaces(list: List<UserSubtitleFont>?) {
        if (list == null) return
        typefaceJob?.cancel()
        typefaceJob = viewModelScope.launch {
            val known = _typefaces.value
            val skip = unloadable.toSet()
            val loaded = withContext(io) {
                list.filterNot { it.id in skip }.associate { font -> font.id to (known[font.id] ?: loadTypeface(font.file)) }
            }
            if (!_panelOpen.value) return@launch
            unloadable += loaded.filterValues { it == null }.keys
            _typefaces.value = loaded.mapNotNull { (id, face) -> face?.let { id to it } }.toMap()
        }
    }

    private fun openPhone() {
        if (phoneServer != null) return
        val started = startPhone({ store.fonts.value?.size ?: 0 }, store::add) ?: return
        phoneServer = started.server
        _phoneUrl.value = started.url
        phoneLifetimeJob = viewModelScope.launch {
            delay(phoneLifetime)
            if (phoneServer === started.server) closePhone()
        }
    }

    private fun closePhone() {
        phoneLifetimeJob?.cancel()
        phoneLifetimeJob = null
        val closing = phoneServer
        phoneServer = null
        _phoneUrl.value = null
        // A send already being saved finishes on its own thread; only its answer can be lost.
        closing?.stop()
    }

    override fun onCleared() {
        closePhone()
        super.onCleared()
    }

    companion object {
        /** As the Access headers and TMDB key pages: a page left open is not reachable for long. */
        val PHONE_LIFETIME: Duration = 10.minutes
    }
}
