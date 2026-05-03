package lava.tracker.nnmclub

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * Descriptor for the NNM-Club tracker (nnmclub.to / nnm-club.me).
 *
 * Capability Honesty (clause 6.E): the capabilities set is the exact set of
 * TrackerFeature impls NnmclubClient.getFeature() will resolve.
 */
object NnmclubDescriptor : TrackerDescriptor {
    override val trackerId: String = "nnmclub"
    override val displayName: String = "NNM-Club"
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl("https://nnmclub.to", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        MirrorUrl("https://nnm-club.me", priority = 1, protocol = Protocol.HTTPS),
    )
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.BROWSE,
        TrackerCapability.TOPIC,
        TrackerCapability.COMMENTS,
        TrackerCapability.TORRENT_DOWNLOAD,
        TrackerCapability.MAGNET_LINK,
        TrackerCapability.AUTH_REQUIRED,
        // No FORUM — NNM-Club has a forum tree but the SDK surface is not yet wired.
        // No FAVORITES — the scraping surface does not expose a dedicated favorites list endpoint.
    )
    override val authType: AuthType = AuthType.FORM_LOGIN
    override val encoding: String = "windows-1251"
    override val expectedHealthMarker: String = "NNM-Club"
}
