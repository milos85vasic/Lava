package lava.tracker.rutracker.domain

import lava.tracker.rutracker.magnet.RuTrackerMagnetCache
import javax.inject.Inject

/**
 * Synchronous magnet-link lookup for RuTracker.
 *
 * RuTracker parses the magnet during topic and search fetches; the production
 * mappers surface it into [lava.tracker.api.model.TorrentItem.magnetUri], and
 * [lava.tracker.rutracker.feature.RuTrackerTopic] /
 * [lava.tracker.rutracker.feature.RuTrackerSearch] record it into the shared
 * [RuTrackerMagnetCache] on every fetch. This use case reads that cache so the
 * synchronous [lava.tracker.api.feature.DownloadableTracker.getMagnetLink]
 * surfaces the genuinely-parsed magnet (§6.E — the declared `MAGNET_LINK`
 * capability must actually resolve).
 *
 * Returns null only when no topic/search has surfaced [id] yet — an honest
 * absence per the synchronous-only `DownloadableTracker` contract, never a
 * fabricated value. (Was an unconditional `return null` stub; see
 * docs/qa/magnet-label-honesty-audit-2026-06-08.md W4a +
 * RuTrackerMagnetExposureTest.)
 */
class GetMagnetLinkUseCase @Inject constructor(
    private val magnetCache: RuTrackerMagnetCache,
) {
    operator fun invoke(id: String): String? = magnetCache.get(id)
}
