package digital.vasic.lava.api.routes

import digital.vasic.lava.api.di.inject
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import lava.network.api.NetworkApi

internal fun Application.configureForumRoutes() {
    val api by inject<NetworkApi>()

    routing {
        get("/forum") {
            call.respond(api.getForum())
        }

        get("/forum/{id}") {
            call.respond(
                api.getCategory(
                    id = call.parameters.getOrFail("id"),
                    page = call.request.queryParameters["page"]?.toIntOrNull(),
                ),
            )
        }
    }
}
