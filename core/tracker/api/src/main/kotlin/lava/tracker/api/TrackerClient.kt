package lava.tracker.api

import kotlin.reflect.KClass

interface TrackerClient : AutoCloseable {
    val descriptor: TrackerDescriptor

    /** Lightweight liveness probe. Used by MirrorManager and SDK init. */
    suspend fun healthCheck(): Boolean

    /**
     * Returns a feature-interface implementation if the tracker supports the requested
     * capability, or null otherwise. Constitutional clause 6.E (Capability Honesty)
     * requires: capability declared in descriptor ⇒ this returns non-null.
     */
    fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T?
}
