package lava.logger.api

interface LoggerFactory {
    fun get(tag: String): Logger
}
