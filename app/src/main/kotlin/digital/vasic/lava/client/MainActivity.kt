package digital.vasic.lava.client

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import digital.vasic.lava.client.handoff.ApiKeyClient
import digital.vasic.lava.client.navigation.MobileNavigation
import digital.vasic.lava.client.platform.OpenFileHandlerImpl
import digital.vasic.lava.client.platform.OpenLinkHandlerImpl
import digital.vasic.lava.client.platform.ShareLinkHandlerImpl
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import lava.applink.AppLinkContract
import lava.common.analytics.AnalyticsTracker
import lava.designsystem.platform.LocalPlatformType
import lava.designsystem.platform.PlatformType
import lava.logger.api.LoggerFactory
import lava.main.MainScreen
import lava.main.MainViewModel
import lava.models.settings.Theme
import lava.navigation.DeepLinks
import lava.navigation.LocalDeepLinks
import lava.navigation.rememberNavigationController
import lava.onboarding.OnboardingAction
import lava.onboarding.OnboardingScreen
import lava.onboarding.OnboardingViewModel
import lava.rating.RatingDialog
import lava.securestorage.PreferencesStorage
import lava.ui.platform.LocalLoggerFactory
import lava.ui.platform.LocalOpenFileHandler
import lava.ui.platform.LocalOpenLinkHandler
import lava.ui.platform.LocalShareLinkHandler
import lava.ui.platform.OpenFileHandler
import lava.ui.platform.OpenLinkHandler
import lava.ui.platform.ShareLinkHandler
import javax.inject.Inject

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject
    lateinit var analytics: AnalyticsTracker

    @Inject
    lateinit var loggerFactory: LoggerFactory

    @Inject
    lateinit var preferencesStorage: PreferencesStorage

    private val viewModel: MainViewModel by viewModels()

    // Task 3.6 (2026-06-03): OnboardingViewModel held at Activity level so
    // onNewIntent can route EXTRA_API_HOST + EXTRA_API_PORT back to it.
    // `by viewModels()` keeps the VM in the Activity's ViewModelStore — the
    // same instance hiltViewModel() inside setContent resolves (both use the
    // same ViewModelStoreOwner: this Activity). MainActivity is declared
    // singleInstance in the manifest, so onNewIntent fires reliably.
    private val onboardingViewModel: OnboardingViewModel by viewModels()

    private val deepLinks = DeepLinks()

    open val deviceType: PlatformType
        get() {
            val uiModeManager = getSystemService(this, UiModeManager::class.java)
            val currentMode = uiModeManager?.currentModeType
            return if (currentMode == UI_MODE_TYPE_TELEVISION) {
                PlatformType.TV
            } else {
                PlatformType.MOBILE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deepLinks.initialDeepLink = intent.data
        }

        var theme: Theme? by mutableStateOf(null)
        var showOnboarding: Boolean? by mutableStateOf(null)
        // Sweep Finding #9 closure (2026-05-17, §6.L 59th invocation).
        //
        // Pre-fix the single launch{} block called `isOnboardingComplete()`
        // once, then ran an infinite `viewModel.theme.collect` that
        // serialized any further re-read. Two parallel `launch{}` blocks
        // now collect their flows independently:
        //   - theme is observed live;
        //   - onboardingComplete is observed via the new
        //     [PreferencesStorage.observeOnboardingComplete] flow, which
        //     emits the current value on subscription AND re-emits when
        //     the SharedPreferences key changes (e.g. a Settings "Reset
        //     onboarding" action). `distinctUntilChanged` suppresses
        //     redundant re-renders.
        // The §6.J anti-bluff requirement: the user-visible welcome
        // screen MUST re-appear if onboardingComplete flips back to false
        // at runtime — pre-fix this required a process restart.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.theme.collect { t -> theme = t }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferencesStorage.observeOnboardingComplete()
                    .distinctUntilChanged()
                    .collect { complete -> showOnboarding = !complete }
            }
        }
        splashScreen.setKeepOnScreenCondition { theme == null || showOnboarding == null }

        setContent {
            theme?.let { theme ->
                val linkHandler = rememberOpenLinkHandler()
                val shareLinkHandler = rememberShareLinkHandler()
                val openFileHandler = rememberOpenFileHandler()
                CompositionLocalProvider(
                    LocalOpenLinkHandler provides linkHandler,
                    LocalShareLinkHandler provides shareLinkHandler,
                    LocalOpenFileHandler provides openFileHandler,
                    LocalPlatformType provides deviceType,
                    LocalLoggerFactory provides loggerFactory,
                    LocalDeepLinks provides deepLinks,
                ) {
                    if (showOnboarding == true) {
                        MainScreen(
                            theme = theme,
                            platformType = deviceType,
                        ) {
                            val viewModel: lava.onboarding.OnboardingViewModel = hiltViewModel()
                            OnboardingScreen(
                                viewModel = viewModel,
                                onComplete = {
                                    lifecycleScope.launch {
                                        preferencesStorage.setOnboardingComplete(true)
                                        showOnboarding = false
                                    }
                                },
                                // §6.AB onboarding-gate enforcement: back-from-Welcome
                                // closes the app entirely. Next launch re-enters
                                // onboarding because `onboardingComplete` was never
                                // written. Per the operator's 27th §6.L invocation:
                                // "until user does not complete onboarding flow with
                                // success with at least one Provider configured and
                                // working (probed with success)" the app keeps
                                // re-prompting.
                                onExitApp = { finishAffinity() },
                                // Task 3.6 (2026-06-03): pass the API-key reader so
                                // the ViewModel can read the loopback key after the
                                // API app returns. Authority is variant-aware via
                                // BuildConfig (§6.R: no literal authority string).
                                apiKeyReader = buildApiKeyReader(),
                            )
                        }
                    } else if (showOnboarding == false) {
                        val navigationController = rememberNavigationController()
                        RatingDialog()
                        MainScreen(
                            theme = theme,
                            platformType = deviceType,
                            content = { MobileNavigation(navigationController) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // IMPORTANT-3b: singleInstance correctness — setIntent so that
        // getIntent() in any subsequent call (e.g. deepLink re-read on
        // resume) returns the NEW intent, not the stale launch intent.
        setIntent(intent)
        val logger = loggerFactory.get("MainActivity")
        logger.d { "New intent: $intent" }
        deepLinks.deepLink = intent.data

        // Task 3.6 (2026-06-03): API-app return intent — carry the loopback
        // host + port to the OnboardingViewModel so it can probe + persist.
        // The API app sends EXTRA_API_HOST + EXTRA_API_PORT when the user
        // taps "Back to Lava client" after starting the on-device API.
        // Only route if BOTH extras are present (defensive — ordinary deeplinks
        // will not carry these keys).
        val apiHost = intent.getStringExtra(AppLinkContract.EXTRA_API_HOST)
        val apiPort = intent.getIntExtra(AppLinkContract.EXTRA_API_PORT, -1)
        if (apiHost != null && apiPort > 0) {
            logger.d { "API-app return: host=$apiHost port=$apiPort — routing to OnboardingViewModel" }
            onboardingViewModel.perform(
                OnboardingAction.OnDeviceApiReturned(host = apiHost, port = apiPort),
            )
        }
    }

    /**
     * Builds the API-key reader lambda for the onboarding screen.
     *
     * The lambda reads the running API app's access key via [ApiKeyClient]
     * using the variant-aware authority (§6.R: no literal string).
     * Called once per composition — [ApiKeyClient] is stateless.
     */
    private fun buildApiKeyReader(): () -> String? {
        val suffix = if (BuildConfig.DEBUG) ".dev.keyprovider" else ".keyprovider"
        val authority = BuildConfig.API_RELEASE_PACKAGE + suffix
        val client = ApiKeyClient(this, authority, analytics)
        // §6.AK: a debug-only autonomous-QA override takes precedence so a keystone
        // run against a standalone lava-api-go (no on-device api-app ContentProvider
        // to read) still exercises the REAL withLocalApiKeyIfMissing keying path.
        // QaKeyInjection.override is compile-time-null in the release sourceset, so
        // release builds are byte-for-byte unchanged (the `?:` always falls through).
        return { QaKeyInjection.override ?: client.read()?.key }
    }

    @Composable
    private fun rememberOpenLinkHandler(): OpenLinkHandler {
        val context = LocalContext.current
        return remember { OpenLinkHandlerImpl(context, loggerFactory) }
    }

    @Composable
    private fun rememberShareLinkHandler(): ShareLinkHandler {
        val context = LocalContext.current
        return remember { ShareLinkHandlerImpl(context) }
    }

    @Composable
    private fun rememberOpenFileHandler(): OpenFileHandler {
        val context = LocalContext.current
        return remember { OpenFileHandlerImpl(context) }
    }
}
