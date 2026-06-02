package lava.api.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for the standalone Lava API app.
 *
 * Hilt root. D-infra wires no Hilt modules yet (the Service constructs its
 * controller directly); the annotation is present so D-ui can add injected
 * ViewModels without re-shaping the app.
 */
@HiltAndroidApp
class ApiApplication : Application()
