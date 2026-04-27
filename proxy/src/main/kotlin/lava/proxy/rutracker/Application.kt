package lava.proxy.rutracker

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import lava.proxy.rutracker.plugins.configureKoin
import lava.proxy.rutracker.plugins.configureMonitoring
import lava.proxy.rutracker.plugins.configureSerialization
import lava.proxy.rutracker.plugins.configureStatusPages
import lava.proxy.rutracker.routes.configureAuthRoutes
import lava.proxy.rutracker.routes.configureFavoritesRoutes
import lava.proxy.rutracker.routes.configureForumRoutes
import lava.proxy.rutracker.routes.configureMainRoutes
import lava.proxy.rutracker.routes.configureSearchRoutes
import lava.proxy.rutracker.routes.configureStaticRoutes
import lava.proxy.rutracker.routes.configureTopicRoutes
import lava.proxy.rutracker.routes.configureTorrentRoutes

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureKoin()
        configureMonitoring()
        configureSerialization()
        configureStatusPages()
        configureMainRoutes()
        configureAuthRoutes()
        configureForumRoutes()
        configureSearchRoutes()
        configureTopicRoutes()
        configureTorrentRoutes()
        configureFavoritesRoutes()
        configureStaticRoutes()
    }.start(wait = true)
}
