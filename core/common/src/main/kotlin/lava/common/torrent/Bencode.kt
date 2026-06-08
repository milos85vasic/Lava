package lava.common.torrent

/**
 * A minimal, strict bencode decoder sufficient to validate `.torrent` files.
 *
 * Bencode (the BitTorrent encoding) has four types:
 *  - byte strings:  `<len>:<bytes>`            e.g. `4:spam`
 *  - integers:      `i<n>e`                    e.g. `i42e`
 *  - lists:         `l<elements>e`
 *  - dictionaries:  `d<key><value>...e`        keys are byte strings, sorted
 *
 * This decoder is deliberately strict: it rejects trailing garbage, leading
 * zeros in integers, negative lengths, and truncated input — exactly the
 * malformations a real-world `.torrent` validator must catch. It records the
 * byte range each dictionary value occupies so callers can recover the exact
 * sub-slice that was bencoded (needed to compute the info-hash over the
 * verbatim `info` dictionary bytes, not a re-encoding).
 */
internal sealed interface BNode {
    data class BStr(val bytes: ByteArray) : BNode {
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    data class BInt(val value: Long) : BNode

    data class BList(val items: List<BNode>) : BNode

    /**
     * @param map decoded key → value
     * @param valueRanges decoded key → [start, end) byte range of the value in
     *   the ORIGINAL input, so the verbatim bencoded value bytes can be sliced.
     */
    data class BDict(
        val map: Map<String, BNode>,
        val valueRanges: Map<String, IntRange>,
    ) : BNode
}

internal class BencodeException(message: String) : Exception(message)

/**
 * Decodes a single bencoded value starting at offset 0 and requires the whole
 * [input] to be consumed (no trailing bytes). Throws [BencodeException] on any
 * malformation.
 */
internal class BencodeParser(private val input: ByteArray) {

    private var pos = 0

    fun parseWhole(): BNode {
        if (input.isEmpty()) throw BencodeException("empty input")
        val node = parseValue()
        if (pos != input.size) {
            throw BencodeException("trailing bytes after top-level value at offset $pos")
        }
        return node
    }

    private fun parseValue(): BNode {
        if (pos >= input.size) throw BencodeException("unexpected end of input at $pos")
        return when (val c = input[pos].toInt().toChar()) {
            'i' -> parseInt()
            'l' -> parseList()
            'd' -> parseDict()
            in '0'..'9' -> BNode.BStr(parseByteString())
            else -> throw BencodeException("unexpected token '$c' at offset $pos")
        }
    }

    private fun parseByteString(): ByteArray {
        val colon = indexOf(':'.code.toByte(), pos)
            ?: throw BencodeException("byte string length missing ':' at $pos")
        val lenText = String(input, pos, colon - pos, Charsets.US_ASCII)
        if (lenText.isEmpty()) throw BencodeException("empty byte string length at $pos")
        if (lenText.length > 1 && lenText[0] == '0') {
            throw BencodeException("leading zero in byte string length at $pos")
        }
        val len = lenText.toLongOrNull()
            ?: throw BencodeException("non-numeric byte string length '$lenText' at $pos")
        if (len < 0) throw BencodeException("negative byte string length at $pos")
        val start = colon + 1
        val end = start + len
        if (end > input.size) throw BencodeException("byte string truncated at $pos (need $len bytes)")
        val bytes = input.copyOfRange(start, end.toInt())
        pos = end.toInt()
        return bytes
    }

    private fun parseInt(): BNode.BInt {
        // current char is 'i'
        val start = pos + 1
        val end = indexOf('e'.code.toByte(), start)
            ?: throw BencodeException("integer missing terminating 'e' at $pos")
        val text = String(input, start, end - start, Charsets.US_ASCII)
        if (text.isEmpty()) throw BencodeException("empty integer at $pos")
        if (text == "-0") throw BencodeException("negative zero integer at $pos")
        if (text.length > 1 && text[0] == '0') throw BencodeException("leading zero integer at $pos")
        if (text.length > 2 && text.startsWith("-0")) throw BencodeException("leading zero integer at $pos")
        val value = text.toLongOrNull()
            ?: throw BencodeException("non-numeric integer '$text' at $pos")
        pos = end + 1
        return BNode.BInt(value)
    }

    private fun parseList(): BNode.BList {
        pos++ // consume 'l'
        val items = mutableListOf<BNode>()
        while (true) {
            if (pos >= input.size) throw BencodeException("list missing terminating 'e'")
            if (input[pos].toInt().toChar() == 'e') {
                pos++
                return BNode.BList(items)
            }
            items.add(parseValue())
        }
    }

    private fun parseDict(): BNode.BDict {
        pos++ // consume 'd'
        val map = LinkedHashMap<String, BNode>()
        val ranges = LinkedHashMap<String, IntRange>()
        while (true) {
            if (pos >= input.size) throw BencodeException("dict missing terminating 'e'")
            if (input[pos].toInt().toChar() == 'e') {
                pos++
                return BNode.BDict(map, ranges)
            }
            if (input[pos].toInt().toChar() !in '0'..'9') {
                throw BencodeException("dict key must be a byte string at $pos")
            }
            val key = String(parseByteString(), Charsets.UTF_8)
            val valueStart = pos
            val value = parseValue()
            val valueEnd = pos
            map[key] = value
            ranges[key] = valueStart until valueEnd
        }
    }

    private fun indexOf(target: Byte, from: Int): Int? {
        var i = from
        while (i < input.size) {
            if (input[i] == target) return i
            i++
        }
        return null
    }
}
