package lava.tracker.client.persistence

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import lava.sdk.api.MirrorUrl
import lava.tracker.mirror.MirrorConfigStore
import lava.tracker.mirror.MirrorsConfig
import lava.tracker.mirror.TrackerMirrorConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the merged mirror list for one tracker. Layers user-supplied
 * entries from [UserMirrorRepository] on top of the bundled `mirrors.json`
 * shipped in `assets/`. User entries supersede bundled entries that share
 * the same URL.
 *
 * Added in SP-3a Phase 4 (Task 4.3).
 */
@Singleton
class MirrorConfigLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepo: UserMirrorRepository,
) {

    /**
     * Returns the bundled+user merged mirror list for [trackerId]. Sorted by
     * ascending priority. Returns an empty list when the tracker has neither
     * a bundled nor a user-supplied entry (the caller MUST treat this as
     * "tracker has no mirrors" — distinct from "tracker is unhealthy").
     */
    suspend fun loadFor(trackerId: String): List<MirrorUrl> {
        val bundled = bundled()[trackerId]?.mirrors ?: emptyList()
        val user = userRepo.loadAsMirrorUrls(trackerId)
        return mergeAndSort(bundled, user)
    }

    /** Returns the bundled config for all trackers (no user merge). */
    suspend fun loadBundled(): MirrorsConfig = bundled().let {
        // Re-wrap into MirrorsConfig because [bundled] returns the trackers map.
        MirrorsConfig(version = 1, trackers = it)
    }

    /** Returns just the bundled mirror list for [trackerId] without user merge. */
    fun bundledFor(trackerId: String): List<MirrorUrl> =
        bundled()[trackerId]?.mirrors ?: emptyList()

    /** Returns the bundled health marker for [trackerId], or null when missing. */
    fun bundledMarkerFor(trackerId: String): String? =
        bundled()[trackerId]?.expectedHealthMarker

    /**
     * Reads `assets/mirrors.json` once and caches the parsed map. Synchronous
     * because asset reads are file-system local and do not block on I/O long
     * enough to warrant a background dispatcher; the data structure is tiny
     * (under 1 KB).
     */
    @Synchronized
    private fun bundled(): Map<String, TrackerMirrorConfig> {
        cached?.let { return it }
        val raw = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = MirrorConfigStore(raw).load().trackers
        cached = parsed
        return parsed
    }

    private fun mergeAndSort(bundled: List<MirrorUrl>, user: List<MirrorUrl>): List<MirrorUrl> {
        val merged = LinkedHashMap<String, MirrorUrl>()
        bundled.forEach { merged[it.url] = it }
        user.forEach { merged[it.url] = it } // user overrides bundled at the same URL
        return merged.values.sortedBy { it.priority }
    }

    @Volatile
    private var cached: Map<String, TrackerMirrorConfig>? = null

    companion object {
        const val ASSET_PATH = "mirrors.json"
    }
}
