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

    // SP-3a follow-up (LF-5 RESOLVED, 2026-04-30): UPLOAD and USER_PROFILE
    // were declared on the descriptor but had no matching feature interface
    // in :core:tracker:api/feature/ (no UploadableTracker, no ProfileTracker).
    // RuTrackerClient.getFeature<T>() therefore could never return non-null
    // for those two — a clause-6.E (Capability Honesty) violation.
    // Resolution: drop the two capabilities so the descriptor matches what
    // the SDK actually exposes. Adding feature interfaces is scope creep
    // beyond SP-3a; the legacy UploadTorrentUseCase + GetCurrentProfileUseCase
    // continue to ship as legacy plumbing under :core:tracker:rutracker but
    // are NOT advertised through the SDK descriptor.
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
    )
    override val authType: AuthType = AuthType.CAPTCHA_LOGIN
    override val encoding: String = "Windows-1251"
    override val expectedHealthMarker: String = "rutracker"

    // Constitutional clause 6.G — verified by Challenge Tests C1, C2, C4,
    // C5, C7, C8 in app/src/androidTest/kotlin/lava/app/challenges/.
    override val verified: Boolean = true
}
