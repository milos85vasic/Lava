package lava.tracker.client

import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.testing.FakeTrackerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [CrossTrackerFallbackPolicy] using real DefaultTrackerRegistry +
 * FakeTrackerClient (the lowest permitted boundary). All assertions are
 * on the returned [TrackerDescriptor] — the value the SDK + UI use to
 * propose the cross-tracker fallback to the user.
 *
 * Bluff-Audit:
 * - Test type: VM-CONTRACT — primary assertion is on the proposal value;
 *   the on-device modal rendering is the Challenge gate covered by the
 *   Phase 5 e2e test.
 * - Real-stack: real DefaultTrackerRegistry. No mockk on the SUT.
 * - Falsifiability rehearsal: hard-coded `return null` inside
 *   proposeFallback caused
 *   `proposes_alternative_supporting_capability` to fail with
 *   "expected RuTor descriptor, got null". Reverted before commit.
 * - Forbidden patterns: SUT mocking is absent (the policy is real),
 *   no verify-only assertions, no @Ignore.
 */
class CrossTrackerFallbackPolicyTest {

    private fun descriptor(
        id: String,
        capabilities: Set<TrackerCapability>,
    ): TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = id
        override val displayName: String = "Tracker $id"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl("https://$id.example/", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = capabilities
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = id
    }

    private fun registry(vararg descriptors: TrackerDescriptor): DefaultTrackerRegistry =
        DefaultTrackerRegistry().also { reg ->
            for (d in descriptors) {
                val client = FakeTrackerClient(d)
                reg.register(object : TrackerClientFactory {
                    override val descriptor: TrackerDescriptor = d
                    override fun create(config: PluginConfig): TrackerClient = client
                })
            }
        }

    // VM-CONTRACT
    @Test
    fun proposes_alternative_supporting_capability() {
        val rutracker = descriptor("rutracker", setOf(TrackerCapability.SEARCH))
        val rutor = descriptor("rutor", setOf(TrackerCapability.SEARCH))
        val policy = CrossTrackerFallbackPolicy(registry(rutracker, rutor))

        val proposal = policy.proposeFallback(
            failedTrackerId = "rutracker",
            capability = TrackerCapability.SEARCH,
        )

        assertEquals("rutor", proposal?.trackerId)
    }

    // VM-CONTRACT
    @Test
    fun returns_null_when_no_other_tracker_supports_capability() {
        val rutracker = descriptor("rutracker", setOf(TrackerCapability.SEARCH))
        val rutor = descriptor("rutor", setOf(TrackerCapability.BROWSE)) // no SEARCH
        val policy = CrossTrackerFallbackPolicy(registry(rutracker, rutor))

        val proposal = policy.proposeFallback(
            failedTrackerId = "rutracker",
            capability = TrackerCapability.SEARCH,
        )

        assertNull(proposal)
    }

    // VM-CONTRACT
    @Test
    fun returns_null_when_only_failed_tracker_registered() {
        val rutracker = descriptor("rutracker", setOf(TrackerCapability.SEARCH))
        val policy = CrossTrackerFallbackPolicy(registry(rutracker))

        val proposal = policy.proposeFallback(
            failedTrackerId = "rutracker",
            capability = TrackerCapability.SEARCH,
        )

        assertNull(proposal)
    }

    // VM-CONTRACT
    @Test
    fun returns_null_when_user_opted_out() {
        val rutracker = descriptor("rutracker", setOf(TrackerCapability.SEARCH))
        val rutor = descriptor("rutor", setOf(TrackerCapability.SEARCH))
        val policy = CrossTrackerFallbackPolicy(registry(rutracker, rutor))

        val proposal = policy.proposeFallback(
            failedTrackerId = "rutracker",
            capability = TrackerCapability.SEARCH,
            userOptedIn = false,
        )

        assertNull(proposal)
    }

    // VM-CONTRACT
    @Test
    fun excludes_failed_tracker_even_when_it_supports_capability() {
        val rutracker = descriptor("rutracker", setOf(TrackerCapability.SEARCH))
        val policy = CrossTrackerFallbackPolicy(registry(rutracker))

        // The failed tracker self-excludes — exhaustion of rutracker's
        // mirrors means we cannot route through rutracker again.
        val proposal = policy.proposeFallback(
            failedTrackerId = "rutracker",
            capability = TrackerCapability.SEARCH,
        )

        assertNull(proposal)
    }

    // VM-CONTRACT
    @Test
    fun proposes_first_matching_tracker_when_two_alternatives_exist() {
        val a = descriptor("a", setOf(TrackerCapability.SEARCH))
        val b = descriptor("b", setOf(TrackerCapability.SEARCH))
        val c = descriptor("c", setOf(TrackerCapability.SEARCH))
        val policy = CrossTrackerFallbackPolicy(registry(a, b, c))

        val proposal = policy.proposeFallback(
            failedTrackerId = "a",
            capability = TrackerCapability.SEARCH,
        )
        // Either b or c is acceptable — registry order is unspecified —
        // but it MUST NOT be a (the failed tracker).
        assert(proposal?.trackerId in setOf("b", "c"))
    }
}
