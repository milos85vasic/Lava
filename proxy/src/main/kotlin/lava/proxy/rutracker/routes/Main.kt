package lava.proxy.rutracker.routes

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import lava.network.api.NetworkApi
import lava.proxy.rutracker.di.inject

internal fun Application.configureMainRoutes() {
    val api by inject<NetworkApi>()

    routing {
        get("/") {
            call.respond(api.checkAuthorized(token = call.request.authToken))
        }
        get("/index") {
            call.respond(api.checkAuthorized(token = call.request.authToken))
        }
    }
}
