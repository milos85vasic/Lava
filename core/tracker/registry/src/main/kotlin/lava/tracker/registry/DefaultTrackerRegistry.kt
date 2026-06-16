package lava.tracker.registry

import lava.sdk.api.PluginConfig
import lava.sdk.registry.DefaultPluginRegistry
import lava.sdk.registry.PluginFactory
import lava.sdk.registry.PluginRegistry
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * Lava-domain default registry. Composes the SDK's [DefaultPluginRegistry] via
 * Kotlin interface delegation (the SDK class is final by design — submodule
 * pins are frozen contracts). Adds [trackersWithCapability] which the generic
 * SDK registry can't express because it doesn't know [TrackerCapability], plus
 * the Dynamic Provider Discovery [populateFrom] surface (spec §4.2).
 *
 * Provider sets:
 *  - **Bundled** — the compiled-in factories registered via [register] at DI
 *    time (rutracker, rutor, …). Their ids are remembered as the fallback set.
 *  - **Dynamic** — `ApiBackedTrackerClient`s installed via [populateFrom] from
 *    the API-vended catalogue. [populateFrom] REPLACES the dynamic set each call;
 *    an empty catalogue restores the bundled set so the list is never empty.
 */
class DefaultTrackerRegistry(
    private val delegate: PluginRegistry<TrackerDescriptor, TrackerClient> =
        DefaultPluginRegistry<TrackerDescriptor, TrackerClient>(),
) : TrackerRegistry, PluginRegistry<TrackerDescriptor, TrackerClient> by delegate {

    /** Bundled factories captured at [register] time, keyed by id (the fallback set). */
    private val bundledFactories =
        ConcurrentHashMap<String, PluginFactory<TrackerDescriptor, TrackerClient>>()

    /** Ids currently installed by [populateFrom] (the replaceable dynamic set). */
    private val dynamicIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var apiClientFactory: ((RemoteTrackerDescriptor) -> TrackerClient)? = null

    override fun register(factory: PluginFactory<TrackerDescriptor, TrackerClient>) {
        bundledFactories[factory.descriptor.id] = factory
        delegate.register(factory)
    }

    override fun trackersWithCapability(capability: TrackerCapability): List<TrackerDescriptor> =
        list().filter { capability in it.capabilities }

    override fun setApiClientFactory(factory: (RemoteTrackerDescriptor) -> TrackerClient) {
        apiClientFactory = factory
    }

    override fun populateFrom(descriptors: List<RemoteTrackerDescriptor>) {
        // 1. Tear down the previous dynamic set.
        dynamicIds.forEach { delegate.unregister(it) }
        dynamicIds.clear()

        if (descriptors.isEmpty()) {
            // 2a. Empty catalogue (fetch failed / offline-default API) → restore
            //     the bundled fallback so the list is NEVER blank (§6.AB: fetch
            //     failure shows the fallback, not an empty provider list).
            bundledFactories.forEach { (id, factory) ->
                if (!delegate.isRegistered(id)) delegate.register(factory)
            }
            return
        }

        val factory = apiClientFactory
            ?: error(
                "populateFrom called before setApiClientFactory — the " +
                    "ApiBackedTrackerClient builder must be installed at DI wiring time.",
            )

        // 2b. A non-empty catalogue is the runtime SOURCE OF TRUTH (spec §4.2):
        //     it REPLACES the bundled set rather than coexisting with it. Remove
        //     every bundled provider that the catalogue does not re-declare; a
        //     catalogue entry with a bundled id is re-installed below as the
        //     dynamic (API-backed) client (the API instance vouches for it).
        val incomingIds = descriptors.map { it.trackerId }.toSet()
        bundledFactories.keys.forEach { bundledId ->
            if (bundledId !in incomingIds) delegate.unregister(bundledId)
        }

        // 2c. Install one ApiBackedTrackerClient per descriptor (last-write-wins
        //     per id, so a dynamic entry shadows a same-id bundled provider).
        descriptors.forEach { descriptor ->
            delegate.register(ApiClientFactory(descriptor, factory))
            dynamicIds.add(descriptor.trackerId)
        }
    }

    /** Adapts a [RemoteTrackerDescriptor] + builder into a [PluginFactory]. */
    private class ApiClientFactory(
        private val remote: RemoteTrackerDescriptor,
        private val build: (RemoteTrackerDescriptor) -> TrackerClient,
    ) : PluginFactory<TrackerDescriptor, TrackerClient> {
        override val descriptor: TrackerDescriptor = remote
        override fun create(config: PluginConfig): TrackerClient = build(remote)
    }
}
