package digital.vasic.lava.api.routes

import digital.vasic.lava.api.di.inject
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import lava.network.api.NetworkApi

internal fun Application.configureTorrentRoutes() {
    val api by inject<NetworkApi>()

    routing {
        get("/torrent/{id}") {
            call.respond(
                api.getTorrent(
                    token = call.request.authToken,
                    id = call.parameters.getOrFail("id"),
                ),
            )
        }

        get("/download/{id}") {
            val (contentDisposition, contentType, bytes) = api.download(
                token = call.request.authToken,
                id = call.parameters.getOrFail("id"),
            )
            call.response.header("Content-Disposition", contentDisposition)
            call.respondBytes(
                status = HttpStatusCode.OK,
                contentType = ContentType.parse(contentType),
                bytes = bytes,
            )
        }
    }
}
