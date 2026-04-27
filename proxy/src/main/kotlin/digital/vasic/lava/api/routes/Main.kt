package digital.vasic.lava.api.routes

import digital.vasic.lava.api.di.inject
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import lava.network.api.NetworkApi

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
