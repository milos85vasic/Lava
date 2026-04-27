package lava.logger.impl

import lava.logger.api.Logger
import lava.logger.api.LoggerFactory
import javax.inject.Inject

internal class LoggerFactoryImpl @Inject constructor() : LoggerFactory {
    override fun get(tag: String): Logger = StubLogger
}
