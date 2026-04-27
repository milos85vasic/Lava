package digital.vasic.lava.api.routes

import digital.vasic.lava.api.di.inject
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import lava.network.api.NetworkApi
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto

internal fun Application.configureSearchRoutes() {
    val api by inject<NetworkApi>()

    routing {
        get("/search") {
            call.respond(
                api.getSearchPage(
                    token = call.request.authToken,
                    searchQuery = call.request.queryParameters["query"],
                    categories = call.request.queryParameters["categories"],
                    author = call.request.queryParameters["author"],
                    authorId = call.request.queryParameters["authorId"],
                    sortType = call.request.queryParameters["sort"]?.toEnumOrNull<SearchSortTypeDto>(),
                    sortOrder = call.request.queryParameters["order"]?.toEnumOrNull<SearchSortOrderDto>(),
                    period = call.request.queryParameters["period"]?.toEnumOrNull<SearchPeriodDto>(),
                    page = call.request.queryParameters["page"]?.toIntOrNull(),
                ),
            )
        }
    }
}
