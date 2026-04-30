package lava.tracker.rutracker.mapper

import lava.network.dto.topic.Align
import lava.network.dto.topic.Bold
import lava.network.dto.topic.Box
import lava.network.dto.topic.Br
import lava.network.dto.topic.Code
import lava.network.dto.topic.Color
import lava.network.dto.topic.Crossed
import lava.network.dto.topic.Hr
import lava.network.dto.topic.Image
import lava.network.dto.topic.ImageAligned
import lava.network.dto.topic.Italic
import lava.network.dto.topic.Link
import lava.network.dto.topic.PostBr
import lava.network.dto.topic.PostElementDto
import lava.network.dto.topic.Quote
import lava.network.dto.topic.Size
import lava.network.dto.topic.Spoiler
import lava.network.dto.topic.Text
import lava.network.dto.topic.UList
import lava.network.dto.topic.Underscore

/**
 * Flatten a tree of [PostElementDto] (rutracker's rich post AST) to plain
 * text. Quotes are rendered with a leading `>` per line, code/spoiler blocks
 * carry their title, lists collapse to comma-joined items, line breaks
 * become "\n".
 *
 * Information-loss note: this is a lossy projection; the rich AST cannot be
 * reconstructed from the resulting String. The reverse direction (Section E)
 * has to encode the full text as the entire post body — there is no
 * round-trip for embedded HTML/quote semantics. This is documented in the
 * Comment / Comments mapper code as well.
 */
internal fun List<PostElementDto>.flattenToText(): String {
    val sb = StringBuilder()
    appendInto(sb)
    return sb.toString().trim()
}

private fun List<PostElementDto>.appendInto(sb: StringBuilder) {
    for (el in this) el.appendInto(sb)
}

private fun PostElementDto.appendInto(sb: StringBuilder) {
    when (this) {
        is Text -> sb.append(value)
        is Box -> children.appendInto(sb)
        is Align -> children.appendInto(sb)
        is Size -> children.appendInto(sb)
        is Color -> children.appendInto(sb)
        is Bold -> children.appendInto(sb)
        is Italic -> children.appendInto(sb)
        is Underscore -> children.appendInto(sb)
        is Crossed -> children.appendInto(sb)
        is Quote -> {
            sb.append("> ")
            if (title.isNotBlank()) sb.append(title).append(": ")
            val inner = StringBuilder()
            children.appendInto(inner)
            // Prefix every line of the inner text with "> " for readability.
            val prefixed = inner.toString().split('\n').joinToString("\n") { "> $it" }
            sb.append(prefixed)
        }
        is Code -> {
            if (title.isNotBlank()) sb.append(title).append(": ")
            children.appendInto(sb)
        }
        is Spoiler -> {
            if (title.isNotBlank()) sb.append(title).append(": ")
            children.appendInto(sb)
        }
        is Image -> sb.append("[image] ").append(src)
        is ImageAligned -> sb.append("[image] ").append(src)
        is Link -> {
            children.appendInto(sb)
            sb.append(" (").append(src).append(")")
        }
        is UList -> {
            val parts = children.map { child ->
                val s = StringBuilder()
                listOf(child).appendInto(s)
                s.toString().trim()
            }.filter { it.isNotEmpty() }
            sb.append(parts.joinToString(", "))
        }
        Hr, Br, PostBr -> sb.append('\n')
    }
}
