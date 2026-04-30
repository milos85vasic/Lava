package lava.tracker.registry

import lava.sdk.api.MapPluginConfig
import lava.sdk.api.MirrorUrl
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class DefaultTrackerRegistryTest {

    private fun descriptor(id: String, caps: Set<TrackerCapability>) = object : TrackerDescriptor {
        override val trackerId = id
        override val displayName = id
        override val baseUrls = listOf(MirrorUrl("https://$id.example", isPrimary = true))
        override val capabilities = caps
        override val authType = AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = id
    }

    private fun fakeFactory(d: TrackerDescriptor) = object : TrackerClientFactory {
        override val descriptor = d
        override fun create(config: lava.sdk.api.PluginConfig) = object : TrackerClient {
            override val descriptor = d
            override suspend fun healthCheck() = true
            override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = null
            override fun close() {}
        }
    }

    @Test
    fun `trackersWithCapability filters by declared capabilities`() {
        val reg = DefaultTrackerRegistry()
        reg.register(fakeFactory(descriptor("a", setOf(TrackerCapability.SEARCH, TrackerCapability.BROWSE))))
        reg.register(fakeFactory(descriptor("b", setOf(TrackerCapability.BROWSE))))
        val withSearch = reg.trackersWithCapability(TrackerCapability.SEARCH)
        assertEquals(1, withSearch.size)
        assertEquals("a", withSearch[0].trackerId)
        val withBrowse = reg.trackersWithCapability(TrackerCapability.BROWSE)
        assertEquals(2, withBrowse.size)
    }

    @Test
    fun `register makes the factory retrievable by id`() {
        val reg = DefaultTrackerRegistry()
        reg.register(fakeFactory(descriptor("a", emptySet())))
        val client = reg.get("a", MapPluginConfig())
        assertEquals("a", client.descriptor.trackerId)
        assertTrue(reg.isRegistered("a"))
    }
}
