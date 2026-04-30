package lava.tracker.mirror

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lava.sdk.api.MirrorUrl

@Serializable
data class TrackerMirrorConfig(val expectedHealthMarker: String, val mirrors: List<MirrorUrl>)

@Serializable
data class MirrorsConfig(val version: Int, val trackers: Map<String, TrackerMirrorConfig>)

class MirrorConfigStore(private val bundledJson: String) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): MirrorsConfig = json.decodeFromString(MirrorsConfig.serializer(), bundledJson)
}
