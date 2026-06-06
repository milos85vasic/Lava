package lava.network.dto.topic

import kotlinx.serialization.encodeToString
import lava.network.serialization.JsonFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack serialization tests for the [PostElementDto] polymorphic tree.
 *
 * These DTOs are how the proxy/Go API delivers a forum post's rich-text body
 * to the Android client; the rendered post the user reads is the direct image
 * of this deserialization. A drifted `@SerialName` discriminator or a dropped
 * recursive `children` field renders as a blank/garbled post for a real user.
 *
 * Anti-Bluff posture (§6.J): the SUT is the production polymorphic
 * serializer produced by [JsonFactory.create] applied to the production
 * `@Serializable` DTO graph. Nothing is mocked. Primary assertions are on the
 * decoded value (the object tree the renderer walks) and on the wire JSON
 * discriminator (the contract the server emits).
 *
 * Bluff-Audit recorded in the commit body.
 */
class PostElementDtoSerializationTest {

    private val json = JsonFactory.create()

    @Test
    fun `Text element decodes from its discriminator and value`() {
        val decoded = json.decodeFromString<PostElementDto>(
            """{"type":"Text","value":"hello world"}""",
        )
        assertEquals(Text("hello world"), decoded)
    }

    @Test
    fun `Text element encodes with the Text discriminator and value`() {
        val encoded = json.encodeToString<PostElementDto>(Text("hi"))
        // The wire contract: the discriminator field is "type" with value "Text".
        val reDecoded = json.decodeFromString<PostElementDto>(encoded)
        assertEquals(Text("hi"), reDecoded)
        assertTrue("encoded JSON must carry the Text discriminator: $encoded", encoded.contains("\"type\":\"Text\""))
        assertTrue("encoded JSON must carry the value: $encoded", encoded.contains("\"value\":\"hi\""))
    }

    @Test
    fun `nested Box of Bold of Text round-trips preserving the recursive children`() {
        val original: PostElementDto = Box(
            children = listOf(
                Bold(children = listOf(Text("important"))),
                Text(" plain"),
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PostElementDto>(encoded)

        // The whole tree (every nested discriminator + every leaf value) survives.
        assertEquals(original, decoded)
        val box = decoded as Box
        assertEquals(2, box.children.size)
        val bold = box.children[0] as Bold
        assertEquals(Text("important"), bold.children.single())
        assertEquals(Text(" plain"), box.children[1])
    }

    @Test
    fun `Color element preserves the ColorValue Hex subtype across a round-trip`() {
        val original: PostElementDto = Color(
            color = ColorValue.Hex(hex = 0xFF0000),
            children = listOf(Text("red text")),
        )

        val decoded = json.decodeFromString<PostElementDto>(json.encodeToString(original))

        assertEquals(original, decoded)
        val color = decoded as Color
        assertEquals(ColorValue.Hex(0xFF0000), color.color)
        assertEquals(Text("red text"), color.children.single())
    }

    @Test
    fun `Color element preserves the ColorValue Name subtype across a round-trip`() {
        val original: PostElementDto = Color(
            color = ColorValue.Name(name = "crimson"),
            children = listOf(Text("named")),
        )

        val decoded = json.decodeFromString<PostElementDto>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(ColorValue.Name("crimson"), (decoded as Color).color)
    }

    @Test
    fun `Align element preserves its TextAlignment enum`() {
        val original: PostElementDto = Align(
            alignment = TextAlignment.Center,
            children = listOf(Text("centered")),
        )

        val decoded = json.decodeFromString<PostElementDto>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(TextAlignment.Center, (decoded as Align).alignment)
    }

    @Test
    fun `ImageAligned preserves src and Alignment enum`() {
        val decoded = json.decodeFromString<PostElementDto>(
            """{"type":"ImageAligned","src":"http://img.test/x.png","alignment":"End"}""",
        )
        assertEquals(ImageAligned(src = "http://img.test/x.png", alignment = Alignment.End), decoded)
    }

    @Test
    fun `data-object elements Hr Br PostBr decode from their discriminators`() {
        assertEquals(Hr, json.decodeFromString<PostElementDto>("""{"type":"Hr"}"""))
        assertEquals(Br, json.decodeFromString<PostElementDto>("""{"type":"Br"}"""))
        assertEquals(PostBr, json.decodeFromString<PostElementDto>("""{"type":"PostBr"}"""))
    }

    @Test
    fun `Quote preserves title id and children`() {
        val original: PostElementDto = Quote(
            title = "Re: topic",
            id = "post-99",
            children = listOf(Text("quoted body")),
        )

        val decoded = json.decodeFromString<PostElementDto>(json.encodeToString(original))

        assertEquals(original, decoded)
        val quote = decoded as Quote
        assertEquals("Re: topic", quote.title)
        assertEquals("post-99", quote.id)
        assertEquals(Text("quoted body"), quote.children.single())
    }

    @Test
    fun `Spoiler element with a UList of items survives a deep round-trip`() {
        val original: PostElementDto = Spoiler(
            title = "Show files",
            children = listOf(
                UList(
                    children = listOf(
                        Text("file1.mkv"),
                        Link(src = "http://dl.test/2", children = listOf(Text("file2.mkv"))),
                    ),
                ),
            ),
        )

        val decoded = json.decodeFromString<PostElementDto>(json.encodeToString(original))

        assertEquals(original, decoded)
        val list = (decoded as Spoiler).children.single() as UList
        assertEquals(2, list.children.size)
        val link = list.children[1] as Link
        assertEquals("http://dl.test/2", link.src)
        assertEquals(Text("file2.mkv"), link.children.single())
    }
}
