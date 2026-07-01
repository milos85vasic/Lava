package lava.network.api

import lava.network.dto.topic.TopicPageDto

/**
 * LVA-070 — Lava-domain seam for fetching a topic page from a SPECIFIC provider
 * (the provider whose search result the user tapped), via the Tracker SDK's
 * per-provider client.
 *
 * This is the topic-detail sibling of [HttpDownloadSource]. It exists because
 * the multi-provider search path (`LavaTrackerSdk.streamMultiSearch`) resolves
 * each provider's client by id, but the legacy [NetworkApi] topic path only
 * delegates to the SDK when the active endpoint is direct rutracker
 * (`SwitchingNetworkApi.shouldUseSdk`). With a LAN / GoApi endpoint configured,
 * an archiveorg / gutenberg topic therefore fell through to the proxy
 * `…/topic2/{id}` endpoint — which does not serve those providers and, in the
 * autonomous-QA emulator, did not even resolve (`UnknownHostException`),
 * surfacing the topic screen's error state ("Something went wrong…").
 *
 * The implementation lives in `core:network:impl` and delegates to
 * `LavaTrackerSdk.getTopicPage(trackerId, id, page)`, mirroring the existing
 * [NetworkApi] / `SwitchingNetworkApi` / [HttpDownloadSource] pattern so
 * `core:domain` and `core:data` never import `core:tracker:*`.
 *
 * Capability Honesty (clause 6.E): [getTopicPage] returns null when the
 * provider does NOT declare TOPIC, when [trackerId] is unknown, or when the
 * underlying fetch / parse throws. Null is an honest "no topic surface / fetch
 * failed" signal the caller falls back on — never a fabricated page.
 */
interface TopicPageSource {
    /**
     * Fetches the topic page identified by [id] from the provider [trackerId].
     *
     * @return the topic-page DTO (the same wire shape [NetworkApi.getTopicPage]
     *   produces), or null when the provider has no topic surface or the fetch
     *   fails.
     */
    suspend fun getTopicPage(trackerId: String, id: String, page: Int): TopicPageDto?
}
