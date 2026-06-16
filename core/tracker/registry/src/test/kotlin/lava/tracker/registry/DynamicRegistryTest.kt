package lava.tracker.registry

import lava.sdk.api.MapPluginConfig
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.registry.PluginFactory
import lava.tracker.api.AuthType
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Real-stack test for [DefaultTrackerRegistry.populateFrom] (Dynamic Provider
 * Discovery, spec §4.2, plan Task 4.2).
 *
 * The SUT is the REAL [DefaultTrackerRegistry] wrapping the REAL SDK
 * [lava.sdk.registry.DefaultPluginRegistry]; the only stand-ins are the
 * [FakeBundledFactory] (a compiled-in provider) and a trivial client builder —
 * neither is the SUT, both behave like their production counterparts (a factory
 * yields a client keyed by descriptor id).
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *  Removing `dynamicIds.forEach { delegate.unregister(it) }` from
 *  [DefaultTrackerRegistry.populateFrom] makes [populateFrom_replaces_doesNotAccumulate]
 *  FAIL: re-populating with [descB] leaves descA still registered, so list()
 *  returns [descA, descB] and `assertEquals(listOf("b"), ids)` fails with
 *  "expected:<[b]> but was:<[a, b]>".
 */
class DynamicRegistryTest {

    private fun remote(id: String) = RemoteTrackerDescriptor(
        trackerId = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        baseUrls = listOf(MirrorUrl(url = "https://$id.example", isPrimary = true)),
        capabilities = setOf(TrackerCapability.SEARCH),
        authType = AuthType.NONE,
        encoding = "UTF-8",
    )

    /** A stand-in compiled-in provider factory (id-keyed). */
    private class FakeBundledFactory(id: String) :
        PluginFactory<TrackerDescriptor, TrackerClient> {
        override val descriptor: TrackerDescriptor = object : TrackerDescriptor {
            override val trackerId = id
            override val displayName = id
            override val baseUrls = listOf(MirrorUrl(url = "https://$id.bundled", isPrimary = true))
            override val capabilities = setOf(TrackerCapability.SEARCH)
            override val authType = AuthType.NONE
            override val encoding = "UTF-8"
            override val expectedHealthMarker = ""
        }
        override fun create(config: PluginConfig): TrackerClient = StubClient(descriptor)
    }

    private class StubClient(override val descriptor: TrackerDescriptor) : TrackerClient {
        override suspend fun healthCheck() = true
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = null
        override fun close() {}
    }

    private fun newRegistryWithBundled(vararg bundledIds: String): DefaultTrackerRegistry {
        val registry = DefaultTrackerRegistry()
        registry.setApiClientFactory { StubClient(it) }
        bundledIds.forEach { registry.register(FakeBundledFactory(it)) }
        return registry
    }

    @Test
    fun populateFrom_registersOneClientPerDescriptor() {
        val registry = newRegistryWithBundled()
        registry.populateFrom(listOf(remote("a"), remote("b")))

        val ids = registry.list().map { it.id }.sorted()
        assertEquals(listOf("a", "b"), ids)
        // The registry resolves a real client per id via the installed builder.
        assertEquals("a", registry.get("a", MapPluginConfig()).descriptor.id)
        assertEquals("b", registry.get("b", MapPluginConfig()).descriptor.id)
    }

    @Test
    fun populateFrom_replaces_doesNotAccumulate() {
        val registry = newRegistryWithBundled()
        registry.populateFrom(listOf(remote("a")))
        registry.populateFrom(listOf(remote("b")))

        val ids = registry.list().map { it.id }
        assertEquals(listOf("b"), ids)
        assertFalse("re-populate MUST drop the previous dynamic provider", registry.isRegistered("a"))
        assertTrue(registry.isRegistered("b"))
    }

    @Test
    fun populateFrom_emptyList_restoresBundledFallback() {
        // Seven compiled-in providers, exactly like the production fallback count.
        val bundled = listOf(
            "rutracker",
            "rutor",
            "nnmclub",
            "kinozal",
            "iptorrents",
            "archiveorg",
            "gutenberg",
        )
        val registry = newRegistryWithBundled(*bundled.toTypedArray())

        // First a dynamic catalogue replaces them...
        registry.populateFrom(listOf(remote("jackett-1337x"), remote("jackett-yts")))
        assertEquals(
            listOf("jackett-1337x", "jackett-yts"),
            registry.list().map { it.id }.sorted(),
        )

        // ...then an empty catalogue (fetch failed / offline default) restores the
        // bundled fallback — the list is NEVER blank (§6.AB anti-bluff).
        registry.populateFrom(emptyList())

        val ids = registry.list().map { it.id }.sorted()
        assertEquals(bundled.sorted(), ids)
        bundled.forEach { assertTrue("bundled '$it' restored", registry.isRegistered(it)) }
    }

    @Test
    fun populateFrom_dynamicProviderShadowsBundledOfSameId() {
        val registry = newRegistryWithBundled("rutracker")
        // API vends an updated rutracker descriptor — it must win (last-write).
        val apiRutracker = remote("rutracker").copy(displayName = "RuTracker (API)")
        registry.populateFrom(listOf(apiRutracker))

        val descriptor = registry.list().single { it.id == "rutracker" }
        assertEquals("RuTracker (API)", descriptor.displayName)
    }
}
