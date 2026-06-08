package lava.tracker.iptorrents

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * Descriptor for IPTorrents — a private English tracker behind Cloudflare.
 *
 * ## Why this is a JACKETT-DELEGATING descriptor, not a native HTML provider
 * Per `docs/design/iptorrents-support.md` (option 3b, the agreed honest path):
 * IPTorrents gates every request behind Cloudflare's JS/Turnstile challenge,
 * which the SDK's OkHttp+Jsoup providers physically cannot pass. A native
 * `IPTorrentsSearchParser` would assert against HTML we cannot fetch — a
 * §11.4 PASS-bluff by construction. Instead this provider's feature impls
 * DELEGATE to the local lava-api-go `GET /jackett/search?indexer=iptorrents`
 * route, which drives Jackett's maintained IPTorrents Cardigann definition +
 * FlareSolverr (Cloudflare bypass) server-side. The app sees ONE endpoint
 * (lava-api-go); Jackett + FlareSolverr + IPTorrents credentials all stay in
 * the local stack.
 *
 * ## Capability Honesty (clause 6.E) — every declared capability is BACKED by
 * the route's wire contract (verified against
 * `lava-api-go/internal/handlers/v1/jackett.go` `mapJackettResults` +
 * `internal/provider/provider.go` `SearchItem`):
 *
 *   - SEARCH           ← the route's whole purpose: returns a JSON SearchResult
 *                        of SearchItem rows. [IPTorrentsClient.getFeature] resolves
 *                        [IPTorrentsSearch].
 *   - TORRENT_DOWNLOAD ← each row's `downloadUrl` (a Jackett /dl/ .torrent proxy
 *                        link, mapped from the Torznab `.torrent` enclosure).
 *                        [IPTorrentsDownload.downloadTorrentFile] fetches it.
 *   - MAGNET_LINK      ← each row's `magnetLink` (mapped from a magnet enclosure
 *                        OR the Torznab `magneturl` attr). [IPTorrentsDownload.
 *                        getMagnetLink] returns the cached magnet synchronously.
 *
 * ## What is intentionally ABSENT (declaring it would be a §6.E bluff)
 *   - No BROWSE / TOPIC / COMMENTS / FAVORITES — the `/jackett/search` route is a
 *     flat (non-paginated) search-only Torznab query; lava-api-go exposes NO
 *     jackett browse/topic/comments route, so there is no IPTorrents code path
 *     for those features. Adding the capability without a reachable route is
 *     exactly the declared-but-empty bluff the 6.E gate exists to catch.
 *   - No AUTH_REQUIRED — IPTorrents authentication happens SERVER-SIDE inside
 *     Jackett's gitignored config volume (§6.H). The Android app never performs
 *     a FORM_LOGIN against IPTorrents and there is no [AuthenticatableTracker]
 *     impl here, so AUTH_REQUIRED is NOT declared. [authType] = FORM_LOGIN is
 *     descriptor metadata describing how Jackett authenticates upstream — not a
 *     capability the app surfaces.
 */
object IPTorrentsDescriptor : TrackerDescriptor {
    override val trackerId: String = "iptorrents"
    override val displayName: String = "IPTorrents"

    // The user-visible reachable surface is the lava-api-go sidecar, not the
    // IPTorrents domain directly (the app never talks to iptorrents.com — that
    // is what Cloudflare + Jackett + FlareSolverr handle server-side). The
    // canonical IPTorrents host is recorded here for display/health metadata
    // only; the request URL is the configured lava-api-go route (see
    // IPTorrentsJackettApi), never this base URL.
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl("https://iptorrents.com", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        MirrorUrl("https://iptorrents.me", priority = 1, protocol = Protocol.HTTPS),
    )

    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.TORRENT_DOWNLOAD,
        TrackerCapability.MAGNET_LINK,
    )

    // Jackett authenticates to IPTorrents on the app's behalf via a FORM_LOGIN
    // Cardigann definition; the creds live in Jackett's config volume (§6.H),
    // not in the app. This field is metadata, not an app-surfaced capability.
    override val authType: AuthType = AuthType.FORM_LOGIN
    override val encoding: String = "UTF-8"
    override val expectedHealthMarker: String = "IPTorrents"

    // Constitutional clause 6.G — verified ONLY once the §6.G real-stack proof
    // (search → real .torrent/magnet via the running sidecar) has executed AND
    // an operator real-device attestation has been recorded. Until then the
    // provider stays hidden from the user-facing list (fail-closed).
    override val verified: Boolean = false

    // No app-side anonymous toggle: the app never authenticates against
    // IPTorrents (Jackett does, server-side), so the FORM_LOGIN anonymous toggle
    // is irrelevant. Fail-closed default (false), consistent with kinozal.
    override val supportsAnonymous: Boolean = false

    // The IPTorrents surface IS reachable through lava-api-go's /jackett/search
    // route (landed 05ecd014). apiSupported=true reflects that the route family
    // exists; the provider-list UI still additionally gates on `verified`.
    override val apiSupported: Boolean = true
}
