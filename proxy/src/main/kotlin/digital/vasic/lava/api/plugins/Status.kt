package digital.vasic.lava.api.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.logError
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import lava.network.model.BadRequest
import lava.network.model.Forbidden
import lava.network.model.LavaProxyError
import lava.network.model.NoConnection
import lava.network.model.NoData
import lava.network.model.NotFound
import lava.network.model.Unauthorized
import lava.network.model.Unknown
import java.io.IOException

internal fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logError(call, cause)
            when (cause) {
                is IOException -> call.respond(status = HttpStatusCode.GatewayTimeout, message = Unit)
                is IllegalStateException -> call.respond(status = HttpStatusCode.BadRequest, message = Unit)
                is IllegalArgumentException -> call.respond(status = HttpStatusCode.BadRequest, message = Unit)
                is MissingRequestParameterException -> call.respond(status = HttpStatusCode.BadRequest, message = Unit)
                is LavaProxyError -> {
                    when (cause) {
                        BadRequest -> call.respond(status = HttpStatusCode.BadRequest, message = Unit)
                        Forbidden -> call.respond(status = HttpStatusCode.Forbidden, message = Unit)
                        NoConnection -> call.respond(status = HttpStatusCode.BadGateway, message = Unit)
                        NoData -> call.respond(status = HttpStatusCode.NoContent, message = Unit)
                        NotFound -> call.respond(status = HttpStatusCode.NotFound, message = Unit)
                        Unauthorized -> call.respond(status = HttpStatusCode.Unauthorized, message = Unit)
                        Unknown -> call.respond(status = HttpStatusCode.InternalServerError, message = Unit)
                    }
                }
                else -> call.respond(status = HttpStatusCode.InternalServerError, message = Unit)
            }
        }
    }
}
