package lava.tracker.client

import lava.database.entity.ClonedProviderEntity
import lava.sdk.api.MirrorUrl
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor

/**
 * SP-4 Phase A+B (Task 15) — synthetic [TrackerDescriptor] that surfaces a
 * user-cloned provider in the same list the user picker UI reads.
 *
 * Behavioural contract:
 *  - [trackerId]      ← [ClonedProviderEntity.syntheticId]
 *  - [displayName]    ← [ClonedProviderEntity.displayName]
 *  - [baseUrls]       ← single [MirrorUrl] pointing at the cloned primary URL
 *                       with `isPrimary = true`
 *  - Every other field delegates to [source] — capabilities, authType,
 *    encoding, expectedHealthMarker, verified, supportsAnonymous,
 *    apiSupported — so the clone inherits the source provider's behaviour
 *    contract verbatim. The clone is a *re-pointed* descriptor, not a
 *    different tracker.
 *
 * Anti-Bluff (clause 6.E Capability Honesty): because [capabilities]
 * delegates to [source], every capability the source declares is what the
 * clone declares. That guarantees consumers (e.g. multi-provider search)
 * that hit `getFeature(...)` on a cloned descriptor's tracker id see the
 * same feature set the source would expose, preventing a "clone has the
 * label but no behaviour" bluff.
 */
internal class ClonedTrackerDescriptor(
    private val source: TrackerDescriptor,
    private val override: ClonedProviderEntity,
) : TrackerDescriptor {
    override val trackerId: String = override.syntheticId
    override val displayName: String = override.displayName
    override val baseUrls: List<MirrorUrl> = listOf(
        MirrorUrl(url = override.primaryUrl, isPrimary = true),
    )
    override val capabilities: Set<TrackerCapability> get() = source.capabilities
    override val authType: AuthType get() = source.authType
    override val encoding: String get() = source.encoding
    override val expectedHealthMarker: String get() = source.expectedHealthMarker
    override val verified: Boolean get() = source.verified
    override val supportsAnonymous: Boolean get() = source.supportsAnonymous
    override val apiSupported: Boolean get() = source.apiSupported
}
