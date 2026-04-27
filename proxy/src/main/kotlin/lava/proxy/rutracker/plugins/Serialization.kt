package lava.proxy.rutracker.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import lava.network.serialization.JsonFactory

internal fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(JsonFactory.create())
    }
}
