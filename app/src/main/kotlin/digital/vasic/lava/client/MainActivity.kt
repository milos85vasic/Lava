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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import digital.vasic.lava.client.navigation.MobileNavigation
import digital.vasic.lava.client.platform.OpenFileHandlerImpl
import digital.vasic.lava.client.platform.OpenLinkHandlerImpl
import digital.vasic.lava.client.platform.ShareLinkHandlerImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lava.designsystem.platform.LocalPlatformType
import lava.designsystem.platform.PlatformType
import lava.logger.api.LoggerFactory
import lava.login.OnboardingScreen
import lava.main.MainScreen
import lava.main.MainViewModel
import lava.models.settings.Theme
import lava.navigation.DeepLinks
import lava.navigation.LocalDeepLinks
import lava.navigation.rememberNavigationController
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
    lateinit var loggerFactory: LoggerFactory

    @Inject
    lateinit var preferencesStorage: PreferencesStorage

    private val viewModel: MainViewModel by viewModels()

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
        var showOnboarding by mutableStateOf(false)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val t = viewModel.theme.first()
                theme = t
                val onboardingComplete = preferencesStorage.isOnboardingComplete()
                showOnboarding = !onboardingComplete
            }
        }
        splashScreen.setKeepOnScreenCondition { theme == null }

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
                    if (showOnboarding) {
                        MainScreen(
                            theme = theme,
                            platformType = deviceType,
                        ) {
                            OnboardingScreen(
                                onComplete = {
                                    lifecycleScope.launch {
                                        preferencesStorage.setOnboardingComplete(true)
                                        showOnboarding = false
                                    }
                                },
                            )
                        }
                    } else {
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
        loggerFactory.get("MainActivity").d { "New intent: $intent" }
        deepLinks.deepLink = intent.data
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
