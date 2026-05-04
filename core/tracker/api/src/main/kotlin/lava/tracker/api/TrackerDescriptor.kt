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

    /**
     * Constitutional clause 6.G — Provider Operational Verification gate.
     *
     * `true` ONLY when this tracker has at least one passing Challenge Test
     * exercising its primary user-visible flow (login / search / browse /
     * download as appropriate for its capability set) on a real Android
     * device or in the project's emulator container.
     *
     * Defaults to `false` so any new descriptor is hidden from the
     * user-facing provider list until it earns verification. Per 6.G clause
     * 4: "Unsupported providers MUST NOT appear in the provider list
     * shipped to end users." The provider-list UI MUST filter on this flag.
     *
     * To flip a descriptor from `false` to `true` in code:
     *   1. Add a Challenge Test in `app/src/androidTest/kotlin/lava/app/challenges/`
     *      that traverses the real screen → ViewModel → SDK → real network
     *      stack for the tracker's primary flow.
     *   2. Run the falsifiability rehearsal (deliberate-mutation + observe
     *      failure + revert) and record the Bluff-Audit stamp in the commit
     *      that flips this flag.
     *   3. Operator records a real-device attestation under
     *      `.lava-ci-evidence/<tag>/real-device-verification.md` before
     *      cutting the next release tag.
     */
    val verified: Boolean get() = false
}
