package lava.topic

import androidx.lifecycle.SavedStateHandle
import lava.models.search.Filter
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationDeepLink
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.appendOptionalArgs
import lava.navigation.model.appendOptionalParams
import lava.navigation.model.appendRequiredArgs
import lava.navigation.model.appendRequiredParams
import lava.navigation.model.buildDeepLink
import lava.navigation.model.buildRoute
import lava.navigation.require
import lava.navigation.ui.NavigationAnimations
import lava.navigation.viewModel

private const val TopicIdKey = "t"

// LVA-052 — optional source-provider id so the topic download action can branch
// HTTP_DOWNLOAD providers (archiveorg / gutenberg) to the HTTP-file path vs the
// `.torrent` path. Optional + nullable: existing openTopic(id) call sites
// (category / favorites / search_result / visited / deep-link) keep producing
// the same route minus `?p=...`; the ViewModel resolves a missing provider id to
// the active tracker for full back-compat. NOT a hardcoded id (§6.R) — the value
// is always supplied by the caller's active descriptor.
private const val ProviderIdKey = "p"
private const val TopicRoute = "topic"

context(NavigationGraphBuilder)
fun addTopic(
    back: () -> Unit,
    openCategory: (id: String) -> Unit,
    openLogin: () -> Unit,
    openSearch: (filter: Filter) -> Unit,
    deepLinkUrls: List<String> = emptyList(),
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(
        route = TopicRoute,
        optionalArgsBuilder = {
            appendRequiredArgs(TopicIdKey)
            appendOptionalArgs(ProviderIdKey)
        },
    ),
    arguments = listOf(
        NavigationArgument(TopicIdKey),
        NavigationArgument(ProviderIdKey, nullable = true),
    ),
    deepLinks = deepLinkUrls.map { url ->
        NavigationDeepLink(
            buildDeepLink(url) { appendOptionalArgs(TopicIdKey, ProviderIdKey) },
        )
    },
    animations = animations,
) {
    TopicScreen(
        viewModel = viewModel(),
        back = back,
        openCategory = openCategory,
        openLogin = openLogin,
        openSearch = openSearch,
    )
}

/**
 * Navigates to a topic. [providerId] is the id of the source provider whose
 * topic this is; when null the topic download action falls back to the active
 * tracker (preserving the behaviour of the no-provider call sites). The `t`
 * id stays a required path segment; `p` is appended as `?p=...` only when
 * supplied, so existing `openTopic(id)` calls produce the identical route.
 */
context(NavigationGraphBuilder, NavigationController)
fun openTopic(id: String, providerId: String? = null) {
    navigate(
        buildRoute(
            route = TopicRoute,
            requiredArgsBuilder = { appendRequiredParams(id) },
            optionalArgsBuilder = { appendOptionalParams(ProviderIdKey to providerId) },
        ),
    )
}

internal val SavedStateHandle.id: String
    get() = require(TopicIdKey)

/**
 * LVA-052 — the source-provider id for the active topic, or null when the topic
 * was opened without one (favorites / visited / deep-link, which cannot supply
 * it without a Room column — see TopicViewModel). Null routes the download
 * action to the active-tracker fallback.
 */
internal val SavedStateHandle.providerId: String?
    get() = get<String>(ProviderIdKey)?.takeIf(String::isNotBlank)
