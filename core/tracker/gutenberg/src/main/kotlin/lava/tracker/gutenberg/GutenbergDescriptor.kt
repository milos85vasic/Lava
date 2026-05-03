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
}
