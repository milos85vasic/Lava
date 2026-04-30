package lava.tracker.api

import lava.sdk.api.HasId
import lava.sdk.api.MirrorUrl

interface TrackerDescriptor : HasId {
    /** Stable identifier, e.g. "rutracker", "rutor". Equal to [id] from HasId. */
    val trackerId: String
    override val id: String get() = trackerId

    /** Human-readable display name shown in UI. */
    val displayName: String

    /** Primary + mirror URLs. The first MirrorUrl with isPrimary=true is the canonical address. */
    val baseUrls: List<MirrorUrl>

    /** Capabilities this tracker actually supports — not declarations of intent. Constitutional 6.E. */
    val capabilities: Set<TrackerCapability>

    /** Authentication mechanism. */
    val authType: AuthType

    /** Encoding used by the tracker, e.g. "UTF-8", "Windows-1251". */
    val encoding: String

    /** Substring (case-insensitive) that must appear on the tracker's root page for a HEALTHY probe. */
    val expectedHealthMarker: String
}
