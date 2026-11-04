package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.util.quoteImap

/**
 * A command as it goes over the wire, without its tag: text, and literals in between. Before
 * every literal the client has to wait for the server to ask for it with a continuation (`+`).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-4.3">RFC 3501 - 4.3. String</a>
 */
internal class ImapCommand private constructor(val parts: List<Part>) {

    sealed interface Part {
        data class Text(val text: String) : Part

        class Literal(val bytes: ByteArray) : Part
    }

    /** The command as it is logged and named in errors, a literal written as its size and its text. */
    override fun toString(): String = parts.joinToString("") {
        when (it) {
            is Part.Text -> it.text
            is Part.Literal -> "{${it.bytes.size}}${it.bytes.toString(Charsets.UTF_8)}"
        }
    }

    class Builder {
        private val parts = mutableListOf<Part>()
        private val text = StringBuilder()

        fun text(text: String) = apply { this.text.append(text) }

        /**
         * Appends [value] as a quoted string if it can be one, as a literal otherwise: quoted
         * strings carry 7-bit characters only, and no line breaks.
         */
        fun string(value: String) = apply {
            if (value.isQuotable()) text(value.quoteImap())
            else literal(value.toByteArray(Charsets.UTF_8))
        }

        fun literal(bytes: ByteArray) = apply {
            flushText()
            parts += Part.Literal(bytes)
        }

        private fun flushText() {
            if (text.isNotEmpty()) parts += Part.Text(text.toString())
            text.clear()
        }

        fun build(): ImapCommand {
            flushText()
            return ImapCommand(parts.toList())
        }
    }

    companion object {
        fun of(text: String) = ImapCommand(listOf(Part.Text(text)))

        fun build(block: Builder.() -> Unit): ImapCommand = Builder().apply(block).build()

        /** Whether [this] can be sent as a quoted string. */
        fun String.isQuotable(): Boolean = all { it in '\u0001'..'\u007f' && it != '\r' && it != '\n' }
    }
}
