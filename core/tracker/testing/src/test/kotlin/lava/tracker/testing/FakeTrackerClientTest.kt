package lava.tracker.testing

import lava.sdk.api.MirrorUrl
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.feature.SearchableTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FakeTrackerClientTest {

    private fun descriptor(caps: Set<TrackerCapability>) = object : TrackerDescriptor {
        override val trackerId = "fake"
        override val displayName = "Fake"
        override val baseUrls = listOf(MirrorUrl("https://fake.example", isPrimary = true))
        override val capabilities = caps
        override val authType = AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = "fake"
    }

    @Test
    fun `getFeature returns non-null when capability is declared`() {
        val client = FakeTrackerClient(descriptor(setOf(TrackerCapability.SEARCH)))
        assertNotNull(client.getFeature(SearchableTracker::class))
    }

    @Test
    fun `getFeature returns null when capability is NOT declared`() {
        val client = FakeTrackerClient(descriptor(emptySet()))
        assertNull(client.getFeature(SearchableTracker::class))
    }
}
