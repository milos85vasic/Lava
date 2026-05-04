package lava.tracker.rutor

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * Descriptor for the RuTor tracker (rutor.info / rutor.is). Anonymous-by-default
 * per SP-3a decision 7b-ii: AUTH_REQUIRED is declared so the SDK consumer can
 * call CommentsTracker.addComment() etc., but no read operation is gated by
 * authentication.
 *
 * Constitutional clause 6.E (Capability Honesty): the capabilities set is the
 * exact set of TrackerFeature impls RuTorClient.getFeature() will resolve.
 * FORUM and FAVORITES are intentionally absent — RuTor offers categories only
 * (no nested forum tree) and has no per-user favorites list endpoint
 * comparable to RuTracker's; declaring them would be a bluff.
 */
object RuTorDescriptor : TrackerDescriptor {
    override val trackerId: String = "rutor"
    override val displayName: String = "RuTor.info"
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl("https://rutor.info", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        MirrorUrl("https://rutor.is", priority = 1, protocol = Protocol.HTTPS),
        MirrorUrl("https://www.rutor.info", priority = 2, protocol = Protocol.HTTPS),
        MirrorUrl("https://www.rutor.is", priority = 3, protocol = Protocol.HTTPS),
        MirrorUrl("http://6tor.org", priority = 4, protocol = Protocol.HTTP, region = "ipv6-only"),
    )
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.BROWSE,
        TrackerCapability.TOPIC,
        TrackerCapability.COMMENTS,
        TrackerCapability.TORRENT_DOWNLOAD,
        TrackerCapability.MAGNET_LINK,
        TrackerCapability.RSS,
        TrackerCapability.AUTH_REQUIRED,
        // No FORUM, no FAVORITES — RuTor lacks these in a comparable form.
        // No CAPTCHA_LOGIN — RuTor uses a plain form POST.
    )
    override val authType: AuthType = AuthType.FORM_LOGIN
    override val encoding: String = "UTF-8"
    override val expectedHealthMarker: String = "RuTor"

    // Constitutional clause 6.G — verified by Challenge Tests C1, C3, C4,
    // C6, C7, C8 in app/src/androidTest/kotlin/lava/app/challenges/.
    override val verified: Boolean = true

    // Phase 1.5 (2026-05-04): RuTor permits anonymous browse/search per
    // SP-3a decision 7b-ii. AUTH_REQUIRED in the capability set is for
    // optional features like adding comments; the search/browse path
    // works without credentials.
    override val supportsAnonymous: Boolean = true
}
