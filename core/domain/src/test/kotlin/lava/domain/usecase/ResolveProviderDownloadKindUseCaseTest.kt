package lava.domain.usecase

import kotlinx.coroutines.test.runTest
import lava.network.api.ProviderCapabilitySource
import lava.network.api.ProviderDownloadKind
import lava.testing.TestDispatchers
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * LVA-052 — pins the download-kind resolution that [lava.topic.TopicViewModel]
 * branches on. The SUT is the REAL [ResolveProviderDownloadKindUseCase] wired to
 * a behaviorally-equivalent [ProviderCapabilitySource] fake (a boundary BELOW
 * the use case). The fake mirrors `ProviderCapabilitySourceImpl`'s descriptor
 * read: it returns the kind for the queried provider and records WHICH id it was
 * asked about so the blank→active-tracker fallback is verifiable.
 *
 * Primary assertion: the resolved [ProviderDownloadKind] (the value the VM
 * branches on to decide HTTP-file vs `.torrent`).
 *
 * ## Falsifiability rehearsal (§6.T.1)
 *   Mutation: `ResolveProviderDownloadKindUseCase.invoke` hard-coded to return
 *     `ProviderDownloadKind.TORRENT`.
 *   Observed: `archiveorg resolves to HTTP` FAILS — `expected:<HTTP> but was:<TORRENT>`.
 *   Reverted: yes.
 */
class ResolveProviderDownloadKindUseCaseTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private class FakeCapabilitySource(
        private val kinds: Map<String, ProviderDownloadKind>,
    ) : ProviderCapabilitySource {
        var lastQueriedId: String? = null
            private set

        override suspend fun downloadKind(trackerId: String): ProviderDownloadKind {
            lastQueriedId = trackerId
            return kinds[trackerId] ?: ProviderDownloadKind.NONE
        }
    }

    private fun useCase(source: ProviderCapabilitySource) =
        ResolveProviderDownloadKindUseCase(source, TestDispatchers(dispatcherRule.testDispatcher))

    @Test
    fun `archiveorg resolves to HTTP`() = runTest(dispatcherRule.testDispatcher) {
        val source = FakeCapabilitySource(mapOf("archiveorg" to ProviderDownloadKind.HTTP))
        assertEquals(ProviderDownloadKind.HTTP, useCase(source).invoke("archiveorg"))
    }

    @Test
    fun `rutracker resolves to TORRENT`() = runTest(dispatcherRule.testDispatcher) {
        val source = FakeCapabilitySource(mapOf("rutracker" to ProviderDownloadKind.TORRENT))
        assertEquals(ProviderDownloadKind.TORRENT, useCase(source).invoke("rutracker"))
    }

    @Test
    fun `unknown provider resolves to NONE`() = runTest(dispatcherRule.testDispatcher) {
        val source = FakeCapabilitySource(emptyMap())
        assertEquals(ProviderDownloadKind.NONE, useCase(source).invoke("nope"))
    }

    @Test
    fun `blank provider id is forwarded for active-tracker fallback`() =
        runTest(dispatcherRule.testDispatcher) {
            val source = FakeCapabilitySource(emptyMap())
            useCase(source).invoke("")
            // The use case forwards the (blank) id unchanged; the IMPL resolves
            // blank → active tracker. Pinning the forward proves the fallback is
            // reachable, not swallowed in the use case.
            assertEquals("", source.lastQueriedId)
        }
}
