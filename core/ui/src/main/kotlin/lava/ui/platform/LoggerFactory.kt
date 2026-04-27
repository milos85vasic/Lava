package lava.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import lava.logger.api.LoggerFactory

val LocalLoggerFactory = staticCompositionLocalOf<LoggerFactory> {
    error("no LoggerFactory provided")
}
