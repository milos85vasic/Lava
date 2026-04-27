package digital.vasic.lava.api

import digital.vasic.lava.api.plugins.configureKoin
import digital.vasic.lava.api.plugins.configureMonitoring
import digital.vasic.lava.api.plugins.configureSerialization
import digital.vasic.lava.api.plugins.configureStatusPages
import digital.vasic.lava.api.routes.configureAuthRoutes
import digital.vasic.lava.api.routes.configureFavoritesRoutes
import digital.vasic.lava.api.routes.configureForumRoutes
import digital.vasic.lava.api.routes.configureMainRoutes
import digital.vasic.lava.api.routes.configureSearchRoutes
import digital.vasic.lava.api.routes.configureStaticRoutes
import digital.vasic.lava.api.routes.configureTopicRoutes
import digital.vasic.lava.api.routes.configureTorrentRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

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
