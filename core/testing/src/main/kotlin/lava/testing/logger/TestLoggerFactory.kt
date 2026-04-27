package lava.testing.logger

import lava.logger.api.Logger
import lava.logger.api.LoggerFactory

class TestLoggerFactory : LoggerFactory {
    override fun get(tag: String): Logger = StubLogger

    private companion object {
        object StubLogger : Logger {
            override fun i(message: () -> String) = Unit
            override fun d(message: () -> String) = Unit
            override fun d(t: Throwable?, message: () -> String) = Unit
            override fun e(message: () -> String) = Unit
            override fun e(t: Throwable?, message: () -> String) = Unit
        }
    }
}
