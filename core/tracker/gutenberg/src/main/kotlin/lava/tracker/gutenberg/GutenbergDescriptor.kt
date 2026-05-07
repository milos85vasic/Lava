package lava.tracker.gutenberg

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * Descriptor for the Project Gutenberg provider.
 *
 * Project Gutenberg is a free e-book library, not a torrent tracker.
 * It uses the Gutendex JSON API (https://gutendex.com).
 *
 * Capability Honesty (clause 6.E): the capabilities set is the exact set of
 * TrackerFeature impls GutenbergClient.getFeature() will resolve.
 * FORUM, FAVORITES, COMMENTS, AUTH_REQUIRED, MAGNET_LINK and RSS are
 * intentionally absent — Project Gutenberg does not offer these surfaces.
 */
object GutenbergDescriptor : TrackerDescriptor {
    override val trackerId: String = "gutenberg"
    override val displayName: String = "Project Gutenberg"
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl("https://gutendex.com", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
    )
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.BROWSE,
        TrackerCapability.TOPIC,
        TrackerCapability.TORRENT_DOWNLOAD,
    )
    override val authType: AuthType = AuthType.NONE
    override val encoding: String = "UTF-8"
    override val expectedHealthMarker: String = "Gutenberg"

    // Constitutional clause 6.G — verified=true. Phase 4.1b (2026-05-04):
    // C12 (Continue → authorized main app) verified on the multi-AVD
    // matrix infrastructure (Phase 3) at CZ_API34_Phone. Falsifiability
    // shares C11's mutation protocol — both providers route through the
    // same AuthType.NONE short-circuit + signalAuthorized bridge.
    // Evidence: .lava-ci-evidence/sp3a-challenges/C12-2026-05-04-redesign.json.
    //
    // Deep-coverage (search gutendex.com for "shakespeare" → book row)
    // owed pending nav-compose 2.9.0 upgrade.
    override val verified: Boolean = true

    // Phase 1.5: gutendex.com is a public unauthenticated JSON API.
    // Implicitly anonymous; flag value is informational.
    override val supportsAnonymous: Boolean = true
    override val apiSupported: Boolean = true
}
