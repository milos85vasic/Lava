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

    // Constitutional clause 6.G — verified=false. Forensic note 2026-05-04:
    // the tentative flip to true was REVERTED after manual rehearsal on a
    // real Android emulator (CZ_API34_Phone) showed the Continue tap on
    // the AuthType.NONE provider login screen does NOT navigate to the
    // main app. The IA SDK-level fix (commit 49714c0) correctly emits
    // LoginSideEffect.Success but the post-login navigation from the
    // root onboarding screen has no parent to back() to. See
    // .lava-ci-evidence/sixth-law-incidents/2026-05-04-onboarding-navigation.json
    // for the forensic record.
    override val verified: Boolean = false
}
