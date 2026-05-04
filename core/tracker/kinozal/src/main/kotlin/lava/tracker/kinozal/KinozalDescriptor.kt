package lava.tracker.kinozal

import lava.sdk.api.MirrorUrl
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * Descriptor for the Kinozal tracker (kinozal.tv / kinozal.me).
 *
 * Capability Honesty (clause 6.E): the capabilities set is the exact set of
 * TrackerFeature impls KinozalClient.getFeature() will resolve.
 */
object KinozalDescriptor : TrackerDescriptor {
    override val trackerId: String = "kinozal"
    override val displayName: String = "Kinozal.tv"
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl("https://kinozal.tv", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        MirrorUrl("https://kinozal.me", priority = 1, protocol = Protocol.HTTPS),
    )
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.BROWSE,
        TrackerCapability.TOPIC,
        TrackerCapability.COMMENTS,
        TrackerCapability.TORRENT_DOWNLOAD,
        TrackerCapability.MAGNET_LINK,
        TrackerCapability.AUTH_REQUIRED,
    )
    override val authType: AuthType = AuthType.FORM_LOGIN
    override val encoding: String = "windows-1251"
    override val expectedHealthMarker: String = "Kinozal"

    // Constitutional clause 6.G — verified=false. Same forensic finding
    // as ArchiveOrgDescriptor: post-login navigation broken on the root
    // onboarding screen. See .lava-ci-evidence/sixth-law-incidents/
    // 2026-05-04-onboarding-navigation.json.
    override val verified: Boolean = false
}
