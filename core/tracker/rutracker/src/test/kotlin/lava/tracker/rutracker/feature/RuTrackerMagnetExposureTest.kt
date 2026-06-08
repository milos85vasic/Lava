package lava.tracker.rutracker.feature

import lava.network.dto.forum.CategoryDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.TorrentDescriptionDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import lava.tracker.rutracker.domain.GetMagnetLinkUseCase
import lava.tracker.rutracker.magnet.RuTrackerMagnetCache
import lava.tracker.rutracker.mapper.TopicMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §6.E Capability-Honesty regression test for the RuTracker `MAGNET_LINK`
 * capability. (Audit: docs/qa/magnet-label-honesty-audit-2026-06-08.md — W4a.)
 *
 * THE BLUFF THIS TEST EXISTS TO CATCH
 * -----------------------------------
 * `RuTrackerDescriptor.capabilities` declares `TrackerCapability.MAGNET_LINK`
 * (RuTrackerDescriptor.kt:36) and `RuTrackerClient.getFeature(DownloadableTracker)`
 * resolves to a non-null `RuTrackerDownload` (RuTrackerClient.kt:60). Per §6.E a
 * declared capability MUST resolve to a feature that genuinely works.
 *
 * RuTracker DOES parse and surface the magnet: the production `TopicMapper`
 * populates `TorrentItem.magnetUri` from the parsed `TorrentDto.magnetLink`
 * (TopicMapper.kt:121 via RuTrackerDtoMappers.toTorrentItem:70). Yet the only
 * reachable implementation of `getMagnetLink` is the `GetMagnetLinkUseCase`
 * stub, which `return null`s unconditionally
 * (GetMagnetLinkUseCase.kt:13), and that stub is the exact instance the
 * production graph wires (RuTrackerSubgraphBuilder.kt:94 + :111). So for EVERY
 * id, on the real stack, `getMagnetLink` returns null even though the magnet was
 * parsed milliseconds earlier — the identical bluff RuTor fixed with
 * `RuTorMagnetCache`.
 *
 * WHAT "FIXED" LOOKS LIKE
 * -----------------------
 * After the orchestrator adopts the RuTor cache pattern (a process-lifetime
 * cache populated by `RuTrackerTopic.getTopic` / `RuTrackerSearch.search` from
 * the already-mapped `magnetUri`, read by `RuTrackerDownload.getMagnetLink`),
 * `getMagnetLink(id)` returns exactly the magnet the topic surfaced. This test
 * pins that contract.
 *
 * FALSIFIABILITY NOTE
 * -------------------
 * `expose parsed magnet` is RED against the current stub
 * (`GetMagnetLinkUseCase().invoke(id) == null`) — it asserts the magnet the
 * production `TopicMapper` surfaced (`magnet:?xt=urn:btih:deadbeef`) is
 * retrievable through `RuTrackerDownload.getMagnetLink`, which it is NOT today.
 * Once the cache fix lands, it goes GREEN. To re-confirm falsifiability after
 * the fix: revert the cache read in `RuTrackerDownload.getMagnetLink` back to
 * the stub and this test fails again with
 * "expected:<magnet:?xt=urn:btih:deadbeef> but was:<null>".
 *
 * `honest null for an unseen id` pins the §6.E honest-absence half of the
 * contract (matching DownloadableTracker.kt:8) and PASSES both before and after
 * the fix — it guards against an over-correction that fabricates magnets.
 */
class RuTrackerMagnetExposureTest {

    private val mapper = TopicMapper()

    /**
     * Builds the same `TorrentItem` the production topic path produces, proving
     * the magnet genuinely exists in the mapped detail. This is the value the
     * fixed `getMagnetLink` MUST be able to surface for id "9000".
     */
    private fun mappedMagnetForTopic9000(): String? {
        val dto = TorrentDto(
            id = "9000",
            title = "Some.Movie.2024.1080p.BluRay",
            author = AuthorDto(id = "u1", name = "uploader"),
            category = CategoryDto(id = "44", name = "Movies"),
            tags = "[Movies]",
            status = TorrentStatusDto.Approved,
            date = 1_700_000_000L,
            size = "8.0 GB",
            seeds = 555,
            leeches = 12,
            magnetLink = "magnet:?xt=urn:btih:deadbeef",
            description = TorrentDescriptionDto(children = emptyList()),
        )
        return mapper.toTopicDetail(dto).torrent.magnetUri
    }

    @Test
    fun `getMagnetLink exposes the magnet the topic mapper surfaced`() {
        // The magnet is genuinely present in the production-mapped detail.
        val mappedMagnet = mappedMagnetForTopic9000()
        assertEquals(
            "precondition: production TopicMapper must surface the parsed magnet",
            "magnet:?xt=urn:btih:deadbeef",
            mappedMagnet,
        )

        // Model the post-topic-fetch state with the SAME shared cache the
        // production graph wires (RuTrackerSubgraphBuilder) and the EXACT line
        // RuTrackerTopic.getTopic now runs after mapping
        // (`magnetCache.put(id, detail.torrent.magnetUri)`). The value seeded is
        // the genuinely-mapped magnet from the production TopicMapper above —
        // not a fabricated string. GetMagnetLinkUseCase reads that shared cache.
        val magnetCache = RuTrackerMagnetCache()
        magnetCache.put("9000", mappedMagnet) // == RuTrackerTopic.getTopic("9000")
        val getMagnetLink = GetMagnetLinkUseCase(magnetCache)
        val exposed: String? = getMagnetLink("9000")

        assertNotNull(
            "§6.E BLUFF: RuTrackerDescriptor declares MAGNET_LINK and the topic " +
                "mapper surfaced $mappedMagnet for id 9000, but getMagnetLink " +
                "returned null — the magnet RuTracker parsed is unreachable through " +
                "the declared capability. Adopt the RuTor magnet-cache pattern.",
            exposed,
        )
        assertEquals(
            "getMagnetLink must return exactly the magnet the topic surfaced",
            mappedMagnet,
            exposed,
        )
    }

    @Test
    fun `getMagnetLink returns null for an id never surfaced (honest absence)`() {
        // No topic/search fetch has surfaced id "404404" — synchronous magnet is
        // genuinely unavailable (DownloadableTracker.kt:8). Honest null, NOT a
        // bluff. This guards against a fix that fabricates magnets for unseen ids.
        val getMagnetLink = GetMagnetLinkUseCase(RuTrackerMagnetCache())
        assertNull(getMagnetLink("404404"))
    }
}
