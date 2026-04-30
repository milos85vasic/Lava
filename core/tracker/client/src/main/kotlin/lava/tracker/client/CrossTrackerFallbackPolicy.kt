package lava.tracker.client

import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the SDK should propose a switch to a different tracker
 * after the active tracker exhausts all its mirrors. Added in SP-3a
 * Phase 4 (Task 4.6) per decision 7a-ii (one-tap modal, opt-out).
 *
 * Returns null when:
 *  - the user has opted out (`userOptedIn = false`)
 *  - no other registered tracker declares the same capability
 *  - the only registered tracker is the one that just failed
 */
@Singleton
class CrossTrackerFallbackPolicy @Inject constructor(
    private val registry: TrackerRegistry,
) {

    /**
     * Returns the highest-priority alternative tracker that supports
     * [capability], excluding [failedTrackerId]. Returns null when no
     * alternative is available or the user has opted out.
     */
    fun proposeFallback(
        failedTrackerId: String,
        capability: TrackerCapability,
        userOptedIn: Boolean = true,
    ): TrackerDescriptor? {
        if (!userOptedIn) return null
        return registry.trackersWithCapability(capability)
            .firstOrNull { it.trackerId != failedTrackerId }
    }
}
