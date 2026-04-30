package lava.tracker.rutracker

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

object RuTrackerDescriptor : TrackerDescriptor {
    override val trackerId: String = "rutracker"
    override val displayName: String = "RuTracker.org"
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl(url = "https://rutracker.org", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        MirrorUrl(url = "https://rutracker.net", priority = 1, protocol = Protocol.HTTPS),
        MirrorUrl(url = "https://rutracker.cr", priority = 2, protocol = Protocol.HTTPS),
    )
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.BROWSE,
        TrackerCapability.FORUM,
        TrackerCapability.TOPIC,
        TrackerCapability.COMMENTS,
        TrackerCapability.FAVORITES,
        TrackerCapability.TORRENT_DOWNLOAD,
        TrackerCapability.MAGNET_LINK,
        TrackerCapability.AUTH_REQUIRED,
        TrackerCapability.CAPTCHA_LOGIN,
        TrackerCapability.UPLOAD,
        TrackerCapability.USER_PROFILE,
    )
    override val authType: AuthType = AuthType.CAPTCHA_LOGIN
    override val encoding: String = "Windows-1251"
    override val expectedHealthMarker: String = "rutracker"
}
