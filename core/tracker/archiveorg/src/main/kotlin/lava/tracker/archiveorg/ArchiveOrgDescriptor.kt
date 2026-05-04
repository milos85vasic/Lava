package lava.tracker.archiveorg

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * Descriptor for the Internet Archive provider (archive.org).
 *
 * Internet Archive is a digital library, not a torrent tracker. It exposes
 * JSON APIs for search, browse, topic metadata, and HTTP download. No auth,
 * no torrents, no magnets, no comments, no favorites.
 *
 * Constitutional clause 6.E (Capability Honesty): the capabilities set is the
 * exact set of TrackerFeature impls ArchiveOrgClient.getFeature() will resolve.
 */
object ArchiveOrgDescriptor : TrackerDescriptor {
    override val trackerId: String = "archiveorg"
    override val displayName: String = "Internet Archive"
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl("https://archive.org", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
    )
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.BROWSE,
        TrackerCapability.FORUM,
        TrackerCapability.TOPIC,
        // No TORRENT_DOWNLOAD — Internet Archive serves files over HTTP.
        // No MAGNET_LINK, no COMMENTS, no FAVORITES, no AUTH_REQUIRED.
    )
    override val authType: AuthType = AuthType.NONE
    override val encoding: String = "UTF-8"
    override val expectedHealthMarker: String = "Internet Archive"

    // Constitutional clause 6.G — verified=true. Phase 4.1a (2026-05-04):
    // C11 (Continue → authorized main app) verified on the multi-AVD
    // matrix infrastructure (Phase 3) at CZ_API34_Phone. Falsifiability
    // rehearsed: reverting the layer-1 IA fix (the AuthType.NONE
    // short-circuit in ProviderLoginViewModel.onSubmitClick) makes
    // C11 fail at the post-Continue waitUntil. The IA forensic-anchor
    // bug class is therefore regression-protected at the user-visible
    // surface. Evidence: .lava-ci-evidence/sp3a-challenges/
    // C11-2026-05-04-redesign.json.
    //
    // Deep-coverage (search archive.org for "ubuntu" → result row)
    // is owed pending nav-compose 2.9.0 upgrade — same blocker as
    // C4-C8's deep versions. Tracked in the plan doc.
    override val verified: Boolean = true

    // Phase 1.5: archive.org has no auth surface — it is implicitly
    // anonymous. The flag value is informational here (the AuthType.NONE
    // path in ProviderLoginViewModel always takes precedence).
    override val supportsAnonymous: Boolean = true
}
