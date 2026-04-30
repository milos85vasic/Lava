package lava.tracker.api

import lava.sdk.api.MirrorUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.reflect.KClass

private interface SampleFeature : TrackerFeature

class TrackerDescriptorContractTest {
    private object SampleDescriptor : TrackerDescriptor {
        override val trackerId = "sample"
        override val displayName = "Sample Tracker"
        override val baseUrls = listOf(MirrorUrl("https://sample.example", isPrimary = true))
        override val capabilities = setOf(TrackerCapability.SEARCH)
        override val authType = AuthType.NONE
        override val encoding = "UTF-8"
        override val expectedHealthMarker = "Sample"
    }

    private class SampleClient : TrackerClient {
        override val descriptor = SampleDescriptor

        override suspend fun healthCheck() = true

        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = null

        override fun close() {}
    }

    @Test
    fun `descriptor exposes id from HasId equal to trackerId`() {
        assertEquals("sample", SampleDescriptor.id)
        assertEquals(SampleDescriptor.id, SampleDescriptor.trackerId)
    }

    @Test
    fun `client returns null for unsupported feature`() {
        val client = SampleClient()
        val result = client.getFeature(SampleFeature::class)
        assertNull(result)
    }
}
