package es.jvbabi.overmail.util

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.io.encoding.Base64

object MimeUtility {

    /**
     * A single encoded word: `=?charset?encoding?text?=`.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc2047#section-2">RFC 2047 - 2</a>
     */
    private val encodedWord = Regex("""=\?([^?\s]+)\?([BbQq])\?([^?\s]*)\?=""")

    /**
     * A line break of a folded header, together with the following folding whitespace.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc5322#section-2.2.3">RFC 5322 - 2.2.3</a>
     */
    private val foldedLineBreak = Regex("""\R[ \t]*""")

    /**
     * Some clients omit the base64 padding of encoded words.
     */
    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /**
     * Decodes all encoded words in [payload]. Any charset known to the JVM is supported; encoded
     * words with an unknown charset or a malformed body are kept as they are.
     *
     * A folded header value is unfolded first, so the result never contains a line break. Whitespace
     * between two adjacent encoded words is dropped, whitespace between an encoded word and
     * unencoded text is kept, and surrounding whitespace is trimmed.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc2047#section-6.2">RFC 2047 - 6.2</a>
     */
    fun decode(payload: String): String {
        val unfolded = payload.replace(foldedLineBreak, " ")

        return buildString {
            var index = 0
            var previousWasEncodedWord = false

            for (match in encodedWord.findAll(unfolded)) {
                val (charsetName, encoding, text) = match.destructured
                val decoded = decodeEncodedWord(charsetName, encoding, text)
                val between = unfolded.substring(index, match.range.first)

                // Only whitespace between two words that were actually decoded is folding whitespace.
                if (!(previousWasEncodedWord && decoded != null && between.isBlank())) append(between)
                append(decoded ?: match.value)
                previousWasEncodedWord = decoded != null
                index = match.range.last + 1
            }

            append(unfolded.substring(index))
        }.trim()
    }

    /**
     * @return the decoded text, or `null` if [charsetName] is unknown or [text] cannot be decoded.
     */
    private fun decodeEncodedWord(charsetName: String, encoding: String, text: String): String? {
        val charset = charsetOrNull(charsetName) ?: return null
        return try {
            when (encoding.uppercase()) {
                "B" -> base64.decode(text).toString(charset)
                else -> decodeQuotedPrintableBytes(text).toByteArray().toString(charset)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        if (Charset.isSupported(name)) Charset.forName(name) else null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun decodeQuotedPrintable(input: String): String {
        return decodeQuotedPrintableBytes(input).toByteArray().toString(Charsets.UTF_8)
    }

    private fun decodeQuotedPrintableBytes(input: String): ByteArrayOutputStream {
        val output = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                '_' -> output.write(' '.code)
                '=' -> {
                    val hex = if (i + 2 < input.length) input.substring(i + 1, i + 3) else null
                    val byte = hex?.toIntOrNull(16)
                    if (byte != null) {
                        output.write(byte)
                        i += 2
                    }
                }
                else -> output.write(c.code)
            }
            i++
        }
        return output
    }
}
