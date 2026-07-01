package lava.tracker.archiveorg.feature

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * kotlinx.serialization analogue of the Go-side `archiveorg.flexString`
 * (LVA-020) — tolerates archive.org `/metadata` fields that arrive as a string,
 * a number, or an ARRAY of strings/numbers.
 *
 * archive.org's `/metadata/{id}` routinely returns `title` / `creator` /
 * `date` as a JSON array for multi-author / multi-date items (e.g.
 * `"creator":["Charles Dickens","John Forster"]`). With the Kotlin client's
 * previous strict `String` decoding, a single array-valued field failed
 * `decodeFromString` of the ENTIRE response — so `ArchiveOrgTopic.getTopic`
 * threw, `LavaTrackerSdk.getTopicPage` returned null, and the topic-detail path
 * fell back to the (unreachable) legacy proxy endpoint → the topic screen's
 * "Something went wrong" error state. This serializer joins arrays with ", "
 * and maps null/absent to "" so a multi-value field no longer breaks the page,
 * matching the Go backend's proven behaviour.
 *
 * Applied to NON-null fields with a default and to nullable fields (kotlinx
 * wraps this non-null serializer for nullable properties automatically).
 */
internal object FlexStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ArchiveOrgFlexString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return flatten(jsonDecoder.decodeJsonElement())
    }

    private fun flatten(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        is JsonArray ->
            element
                .map { flatten(it) }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        is JsonObject -> "" // unexpected shape — treat as empty rather than throw
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
