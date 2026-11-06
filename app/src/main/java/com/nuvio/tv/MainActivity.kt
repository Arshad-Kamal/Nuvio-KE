package com.nuvio.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.marquee.MarqueeLoadingScreen
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeToastHost
import com.nuvio.tv.ui.components.marquee.rememberMarqueeToastState
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import java.net.URI
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileLookStore
import com.nuvio.tv.domain.model.resolveLookAvatar
import com.nuvio.tv.ui.components.LocalLookLibrary
import com.nuvio.tv.ui.components.toArt
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.ScreensaverOverlay
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.livetv.data.LiveTvUserStore
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.screens.settings.SettingsAccess
import com.nuvio.tv.ui.screens.settings.SettingsEntry
import com.nuvio.tv.ui.screens.settings.openSettingsAt
import com.nuvio.tv.ui.screens.welcome.DeviceWelcomeHost
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val font: AppFont = AppFont.INTER,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    // True once every batch has emitted; the app shell waits for it so the first frame
    // never draws with placeholder theme values.
    val loaded: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var nightlySelfTrim: com.nuvio.tv.core.runtime.NightlySelfTrim

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var profileLockStateDataStore: ProfileLockStateDataStore

    @Inject
    lateinit var liveTvUserStore: LiveTvUserStore

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var profileLookStore: ProfileLookStore

    @Inject
    lateinit var myPhotosStore: MyPhotosStore

    @Inject
    lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    @Inject
    lateinit var screensaverController: com.nuvio.tv.core.player.ScreensaverController

    @Inject
    lateinit var updateController: com.nuvio.tv.core.update.UpdateController

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)

    /** Continue Watching launcher extras awaiting navigation; set from onCreate AND onNewIntent (G4-1). */
    private val pendingLauncherLaunch = MutableStateFlow<ContinueWatchingLaunch?>(null)

    private lateinit var jankStats: JankStats

    /** Activity-level launcher for external video players. Survives all navigation changes. */
    private val externalPlayerLauncher = registerForActivityResult(
        com.nuvio.tv.core.player.ExternalPlayerResultContract()
    ) { result ->
        Log.d("MainActivity", "External player ActivityResult: $result")
        externalPlaybackTracker.onActivityResult(result)
    }

    /** True after a screensaver wake until the waking press's ACTION_UP has been swallowed. */
    private var swallowKeysUntilUp = false

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(null)

        // Wire the Activity-level launcher to the tracker
        externalPlaybackTracker.activityLauncher = externalPlayerLauncher

        // Spec 52 P7: a public build checks its releases at most once a day at app start.
        updateController.autoCheckIfDue()

        // OLED screensaver: 1 Hz idle ticker while STARTED. collectLatest restarts the
        // loop (and the idle clock) whenever the enable/timeout settings change, and the
        // clock also restarts every time the activity returns to STARTED.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    themeDataStore.screensaverEnabled,
                    themeDataStore.screensaverTimeoutMinutes
                ) { enabled, minutes -> enabled to minutes }
                    .collectLatest { (enabled, minutes) ->
                        if (!enabled) {
                            screensaverController.notifyWake()
                            return@collectLatest
                        }
                        screensaverController.notifyInteraction()
                        while (true) {
                            delay(1_000)
                            screensaverController.maybeEngage(minutes * 60_000L)
                        }
                    }
            }
        }

        window?.decorView?.post {
            val snapshot = com.nuvio.tv.core.player.DisplayCapabilities.detect(this)
            com.nuvio.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        // Extract extras set by the Continue Watching launcher channel preview programs.
        // G4-1: routed through pendingLauncherLaunch so re-taps while the app is
        // already running (onNewIntent) navigate too, not just the cold onCreate read.
        // Fresh starts only (L5-11): recreate() (language change) and process-death restore
        // re-deliver the original intent, which would replay the Watch Next launch or the
        // install consent over the restored back stack. Warm deliveries reach onNewIntent.
        if (savedInstanceState == null) {
            pendingLauncherLaunch.value = parseContinueWatchingLaunch(intent)
            captureDeepLinkIntent(intent)
        }

        setContent {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.NUVIO) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_notice_nuvio_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val profiles by profileManager.profiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            // PIN gate (L9-27): the persisted lock map is the source of truth, replaced by each
            // successful pull and kept on failure, so an offline start cannot unlock a profile.
            // null = not loaded yet, which the gate treats as "maybe locked".
            val profilePinStates by remember(profileLockStateDataStore) {
                profileLockStateDataStore.pinEnabled.map<Map<Int, Boolean>, Map<Int, Boolean>?> { it }
            }.collectAsState(initial = null)

            LaunchedEffect(authState, profiles) {
                if (authState is AuthState.FullAccount) {
                    profileSyncService.refreshProfileLockStates()
                }
            }

            // activeProfileId starts at the default 1 until the stored id loads, so the gate reads
            // the nullable stored id (one flow for id + readiness): until it loads the PIN state is
            // unknown, and the gate cannot check (or auto-enter) the wrong profile.
            val storedActiveProfileId by profileManager.storedActiveProfileId.collectAsState()
            val activeProfileHasPin: Boolean? = remember(storedActiveProfileId, profilePinStates) {
                storedActiveProfileId?.let { id -> profilePinStates?.let { it[id] == true } }
            }

            LaunchedEffect(hasEverSelectedProfile, activeProfileHasPin, rememberLastProfileEnabled) {
                if (rememberLastProfileEnabled && hasEverSelectedProfile && activeProfileHasPin == false && !hasSelectedProfileThisSession) {
                    hasSelectedProfileThisSession = true
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                }
            }

            val avatarCatalog by avatarRepository.catalog.collectAsState()
            // Re-keyed on sign-in: signed out, the load skips the supporter catalog.
            val isFullAccount = authState is AuthState.FullAccount
            LaunchedEffect(isFullAccount) {
                avatarRepository.loadCatalog()
            }

            // Tagged with its profile id: after a switch the old flow's last value stays in the state
            // until the new one emits, and must not be read as the new profile's entry.
            val activeLookFlow = remember(activeProfileId) {
                profileLookStore.entryFor(activeProfileId).map { activeProfileId to it }
            }
            val activeLook by activeLookFlow.collectAsState(initial = null)
            val activeLookEntry = activeLook?.takeIf { it.first == activeProfileId }?.second
            val myPhotos by myPhotosStore.photos.collectAsState()
            val lookLibrary = remember(myPhotos) { LocalLookLibrary(myPhotos.orEmpty()) }
            val activeProfileAvatarArt = remember(activeProfile, avatarCatalog, activeLookEntry, lookLibrary) {
                activeProfile?.let { profile ->
                    resolveLookAvatar(profile, activeLookEntry, lookLibrary.sets, avatarRepository::getAvatarImageUrl)
                        ?.toArt(lookLibrary)
                }
            }

            val mainUiPrefsFlow = remember(
                themeDataStore,
                layoutPreferenceDataStore
            ) {
                // Group flows into batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeFlow = combine(
                    themeDataStore.selectedThemePreference,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                ) { selectedTheme, font, amoledMode, amoledSurfacesMode ->
                    MainUiPrefs(
                        theme = resolveAppTheme(selectedTheme),
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                    )
                }
                val layoutFlow = combine(
                    layoutPreferenceDataStore.discoverLocation,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                ) { discoverLocation, smoothBringIntoView, fastHorizontalNav, composeHighlighter ->
                    MainUiPrefs(
                        discoverLocation = discoverLocation,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                    )
                }
                combine(themeFlow, layoutFlow) { themePrefs, layoutPrefs ->
                    themePrefs.copy(
                        loaded = true,
                        discoverLocation = layoutPrefs.discoverLocation,
                        smoothBringIntoViewEnabled = layoutPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = layoutPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = layoutPrefs.composeHighlighterEnabled
                    )
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs())
            val discoverLocation = mainUiPrefs.discoverLocation

            val uiScalePercent by com.nuvio.tv.data.local.UiScalePreference.flow(applicationContext).collectAsState(initial = 100)
            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                uiScalePercent = uiScalePercent
            ) {
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    NuvioScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides (BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled),
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = NuvioTheme.colors.Background
                    )
                ) {
                    val screensaverVisible by screensaverController.overlayVisible.collectAsState()
                    val screensaverDimPercent by themeDataStore.screensaverDimPercent.collectAsState(
                        initial = ThemeDataStore.DEFAULT_SCREENSAVER_DIM_PERCENT
                    )
                    ScreensaverOverlay(
                        visible = screensaverVisible,
                        dimPercent = screensaverDimPercent
                    )

                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (authState is AuthState.Loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    if (!hasSelectedProfileThisSession && activeProfileHasPin == null) {
                        // Lock map or active profile still loading: show neither the picker nor the app yet.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && (profiles.size > 1 || activeProfileHasPin == true)

                    if (shouldShowProfileSelection) {
                        ProfileSelectionScreen(
                            onProfileSelected = {
                                hasSelectedProfileThisSession = true
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    if (!mainUiPrefs.loaded) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()
                    // Set by the nav-level deep-link effect below. Consent gate (2026-08-26
                    // review): never install from a link without the user seeing the host and
                    // confirming.
                    var pendingInstallConsent by remember { mutableStateOf<String?>(null) }
                    PendingAddonInstallConsent(
                        pendingManifestUrl = pendingInstallConsent,
                        onConfirm = { manifestUrl -> installConsentedAddon(manifestUrl) },
                        onCleared = { pendingInstallConsent = null }
                    )

                    val navController = rememberNavController()
                    // G6: nav ViewModelStore hoisted beside its controller; see OwnedNavHostSection.
                    val navViewModelStoreOwner = remember {
                        object : ViewModelStoreOwner {
                            override val viewModelStore: ViewModelStore = ViewModelStore()
                        }
                    }
                    DisposableEffect(navViewModelStoreOwner) {
                        onDispose { navViewModelStoreOwner.viewModelStore.clear() }
                    }
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true)
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to content when launched from the Continue Watching channel row.
                    // G4-1: keyed on the pending launch (like the deep-link effect below) so
                    // launcher taps while the app is open navigate instead of being dropped.
                    val launcherLaunch by pendingLauncherLaunch.collectAsState()
                    LaunchedEffect(navController, launcherLaunch) {
                        val launch = launcherLaunch ?: return@LaunchedEffect
                        pendingLauncherLaunch.value = null
                        if (launch.launchMode == "stream" && launch.videoId != null && launch.name != null) {
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = launch.videoId,
                                    contentType = launch.contentType,
                                    title = launch.name,
                                    poster = launch.poster,
                                    backdrop = launch.backdrop,
                                    logo = launch.logo,
                                    season = launch.season,
                                    episode = launch.episode,
                                    episodeName = launch.episodeTitle,
                                    contentId = launch.contentId,
                                    contentName = launch.name,
                                    returnToDetailOnBack = launch.contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            ) {
                                launchSingleTop = true
                            }
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = launch.contentId,
                                    itemType = launch.contentType
                                )
                            ) {
                                launchSingleTop = true
                            }
                        }
                    }

                    LaunchedEffect(navController, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                pendingDeepLinkUrl.value = null
                                // Skip only a repeat of the title already on top. launchSingleTop
                                // matched any Details (one route pattern) and reused that entry and
                                // its ViewModel, so a link for another title was dropped (F-3).
                                val top = navController.currentBackStackEntry
                                val alreadyOnTop = top?.destination?.route == Screen.Detail.route &&
                                    top.arguments?.getString("itemId") == deepLink.id &&
                                    top.arguments?.getString("itemType") == deepLink.type
                                if (!alreadyOnTop) {
                                    navController.navigate(
                                        Screen.Detail.createRoute(
                                            itemId = deepLink.id,
                                            itemType = deepLink.type
                                        )
                                    )
                                }
                            }
                            is AppDeepLink.AddonInstall -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(Screen.AddonManager.route) {
                                    launchSingleTop = true
                                }
                                pendingInstallConsent = deepLink.manifestUrl
                            }
                            null -> {
                                pendingDeepLinkUrl.value = null
                            }
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    // Back to Home with a fresh stack: the route-closing effects below share it.
                    val ejectToHome: () -> Unit = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = false }
                            launchSingleTop = true
                        }
                    }
                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            ejectToHome()
                        }
                    }

                    // T1 settings lock: non-primary profiles cannot enter Settings.
                    // Blocked Settings-tab clicks show a notice instead of navigating. The
                    // eject below guards the live deep-link path into AddonManager (the
                    // install consent dialog survives over the ejected Home) and stays
                    // as defense-in-depth: a profile switch rebuilds this nav subtree
                    // from Home (see the navViewModelStoreOwner note), so a settings
                    // route cannot otherwise be on top for a non-primary.
                    // T5: the primary's Settings-access grant (Settings → Profiles)
                    // lifts the whole lock; absent/false keeps it. Initial false is
                    // the fail-safe while the store loads.
                    val isPrimaryProfileActive = activeProfileId == 1
                    val settingsAccessGranted by profileLockStateDataStore.settingsAccessGranted
                        .collectAsState(initial = false)
                    val blockedTopBarRoutes = remember(isPrimaryProfileActive, settingsAccessGranted) {
                        SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive, settingsAccessGranted)
                    }
                    LaunchedEffect(isPrimaryProfileActive, settingsAccessGranted, currentRoute) {
                        if (SettingsAccess.shouldEjectFromRoute(currentRoute, isPrimaryProfileActive, settingsAccessGranted)) {
                            ejectToHome()
                        }
                    }
                    // Spec 34 D17: a profile with Live TV off has no tab. The eject is defence in depth,
                    // as the settings one: a profile switch already rebuilds this nav subtree from Home,
                    // and the switch is only changed from Manage profiles. Null while loading: the tab
                    // waits, nothing ejects.
                    val liveTvEnabled by remember { liveTvUserStore.activeEnabled }
                        .collectAsState(initial = null)
                    LaunchedEffect(liveTvEnabled, currentRoute) {
                        if (liveTvEnabled == false && isLiveTvRoute(currentRoute)) {
                            ejectToHome()
                        }
                    }
                    val toast = rememberMarqueeToastState()
                    val onBlockedTopBarRoute: () -> Unit = {
                        toast.show(context.getString(R.string.settings_managed_by_primary))
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavLiveTv = stringResource(R.string.nav_live_tv)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val topBarTabs = remember(
                        strNavHome,
                        strNavLiveTv,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        discoverLocation,
                        liveTvEnabled
                    ) {
                        val labels = mapOf(
                            Screen.Home.route to strNavHome,
                            Screen.LiveTv.route to strNavLiveTv,
                            Screen.Search.route to strNavSearch,
                            Screen.Discover.route to strNavDiscover,
                            Screen.Library.route to strNavLibrary,
                            Screen.Settings.route to strNavSettings
                        )
                        topBarTabRoutes(discoverLocation, liveTvEnabled).map { route ->
                            TopBarTab(route = route, label = labels.getValue(route))
                        }
                    }
                    val topBarProfile = activeProfile?.let { profile ->
                        TopBarProfile(
                            name = profile.name,
                            colorHex = profile.avatarColorHex,
                            avatarArt = activeProfileAvatarArt
                        )
                    }

                    // Spec 52 T2: the top bar's Update pill.
                    val showUpdatePill by updateController.notice.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalMarqueeToast provides toast) {
                            TopBarScaffold(
                                navController = navController,
                                navViewModelStoreOwner = navViewModelStoreOwner,
                                startDestination = Screen.Home.route,
                                currentRoute = currentRoute,
                                tabs = topBarTabs,
                                blockedRoutes = blockedTopBarRoutes,
                                onBlockedRoute = onBlockedTopBarRoute,
                                profile = topBarProfile,
                                onSwitchProfile = { hasSelectedProfileThisSession = false },
                                onNavigate = { optimisticRoute = it },
                                onExitApp = {
                                    finishAffinity()
                                    finishAndRemoveTask()
                                },
                                showUpdate = showUpdatePill
                            )
                        }

                        val autoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                        autoNextOverlay?.let { ov ->
                            MarqueeLoadingScreen(
                                visible = true,
                                backdropUrl = ov.backdrop,
                                title = ov.title.orEmpty(),
                                logo = ov.logo,
                                contentType = ov.contentType,
                                season = null,
                                episode = null,
                                episodeName = null,
                                year = ov.year,
                                accent = rememberAmbientAccentProvider(ov.backdrop),
                                message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // Spec 33 D8: the first-launch welcome card, after every gate above; it
                        // waits on Home (a launcher or deep-link start lands elsewhere first).
                        DeviceWelcomeHost(
                            onHome = currentRoute == Screen.Home.route,
                            onReview = if (Screen.Settings.route in blockedTopBarRoutes) null else {
                                {
                                    optimisticRoute = Screen.Settings.route
                                    navController.openSettingsAt(SettingsEntry.NETWORK) {
                                        navigateToTopBarRoute(navController, Screen.Settings.route)
                                    }
                                }
                            }
                        )
                        MarqueeToastHost(state = toast, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        // G4-1: launcher "Continue Watching" extras were only read in onCreate,
        // so the shortcut was inert whenever the app was already running.
        // Conditional write mirrors captureDeepLinkIntent: a plain MAIN re-launch
        // (no extras) must not cancel a still-pending episode launch.
        parseContinueWatchingLaunch(intent)?.let { pendingLauncherLaunch.value = it }
    }

    /** Launcher-channel extras for one pending Continue Watching navigation (G4-1). */
    private data class ContinueWatchingLaunch(
        val contentId: String,
        val contentType: String,
        val launchMode: String?,
        val videoId: String?,
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?
    )

    private fun parseContinueWatchingLaunch(intent: Intent?): ContinueWatchingLaunch? {
        val contentId = intent?.getStringExtra("contentId") ?: return null
        val contentType = intent.getStringExtra("contentType") ?: return null
        return ContinueWatchingLaunch(
            contentId = contentId,
            contentType = contentType,
            launchMode = intent.getStringExtra("launchMode"),
            videoId = intent.getStringExtra("videoId"),
            name = intent.getStringExtra("name"),
            poster = intent.getStringExtra("poster"),
            backdrop = intent.getStringExtra("backdrop"),
            logo = intent.getStringExtra("logo"),
            season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
            episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
            episodeTitle = intent.getStringExtra("episodeTitle")
        )
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    /**
     * Runs a consented deep-link addon install on the Activity scope (L5-01): confirming
     * removes the consent dialog from composition, so a dialog-owned scope would cancel the
     * install mid-fetch and the addon would never be added.
     */
    private fun installConsentedAddon(manifestUrl: String) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
            val result = deepLinkHandler.installAddon(manifestUrl)
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OLED screensaver: while the dim overlay is visible, the first key press wakes
        // it and the whole press (down, repeats, and the matching up) is swallowed so
        // waking never also navigates. Stray key-ups after wake are swallowed too, so
        // ACTION_UP handlers below never see an up without its down.
        if (screensaverController.overlayVisible.value) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                screensaverController.notifyWake()
                swallowKeysUntilUp = true
            }
            return true
        }
        if (swallowKeysUntilUp) {
            if (event.action == KeyEvent.ACTION_UP) {
                swallowKeysUntilUp = false
            }
            return true
        }
        screensaverController.notifyInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            externalPlaybackTracker.autoNextOverlay.value != null
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Compose Dialogs are separate platform windows: their keys never reach this
        // Activity, so idle detection is blind while one is focused. Pause screensaver
        // eligibility while this window lacks focus; refocus restarts the idle clock.
        screensaverController.setWindowFocused(hasFocus)
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        startupSyncService.startPeriodicSurfacePulls()
        nightlySelfTrim.onAppForegrounded()
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // M3: first background after the 04:00 window runs the nightly self-trim
        // (disk-cache trim + idle exit); no-op while foreground by construction.
        runCatching { nightlySelfTrim.onAppBackgrounded() }
    }

    override fun onDestroy() {
        // The @Singleton tracker must not retain this destroyed activity through its launcher;
        // a newer activity may already have installed its own in onCreate.
        if (externalPlaybackTracker.activityLauncher === externalPlayerLauncher) {
            externalPlaybackTracker.activityLauncher = null
        }
        super.onDestroy()
    }
}

/**
 * Composes the install-consent dialog when a pending manifest URL exists. Composed
 * once in MainActivity, fed by the nav-level deep-link effect.
 */
@Composable
private fun PendingAddonInstallConsent(
    pendingManifestUrl: String?,
    onConfirm: (String) -> Unit,
    onCleared: () -> Unit
) {
    pendingManifestUrl?.let { manifestUrl ->
        AddonInstallConsentDialog(
            manifestUrl = manifestUrl,
            onConfirm = onConfirm,
            onDismiss = onCleared
        )
    }
}

/**
 * Consent gate for addon installs arriving via nuvio:// / stremio:// deep links
 * (2026-08-26 review major: links could install addons with only a toast). Shows
 * the resolved host; install runs only on explicit confirm. Presentational only:
 * [onConfirm] runs the install on a scope that outlives this dialog.
 */
@Composable
private fun AddonInstallConsentDialog(
    manifestUrl: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val host = runCatching { URI(manifestUrl).host }.getOrNull() ?: manifestUrl
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.addon_install_confirm_title),
        subtitle = stringResource(R.string.addon_install_confirm_subtitle, host)
    ) {
        PanelActionRow(
            label = stringResource(R.string.addon_install_confirm_install),
            onClick = {
                onDismiss()
                onConfirm(manifestUrl)
            }
        )
        PanelActionRow(
            label = stringResource(R.string.addon_install_confirm_cancel),
            onClick = onDismiss
        )
    }
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    /** Where the chosen app language lives: a SharedPreferences file read before any Activity. */
    const val PREFS_NAME = "app_locale"
    const val PREFS_KEY_TAG = "locale_tag"

    @Volatile
    var localeTag: String = UNSET
}
