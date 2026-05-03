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
}
