package es.jvbabi.overmail.core

import es.jvbabi.overmail.util.MimeContent
import jakarta.mail.BodyPart
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimePart
import java.io.InputStream
import java.io.OutputStream

/**
 * Splits the body of a message into its text and its html representation.
 */
internal object EmailBody {

    /**
     * Writes every non-attachment text body of [part] - a [Part] or a [Multipart] - into the
     * stream its `Content-Type` names.
     */
    fun write(part: Any, textStream: OutputStream, htmlStream: OutputStream) {
        when (part) {
            is Multipart -> {
                for (i in 0 until part.count) write(part.getBodyPart(i), textStream, htmlStream)
            }

            is Part -> writePart(part, textStream, htmlStream)
        }
    }

    private fun writePart(part: Part, textStream: OutputStream, htmlStream: OutputStream) {
        if (part.disposition?.trim()?.lowercase() == Part.ATTACHMENT) return

        val content = if (part is MimePart) MimeContent.of(part) else part.content
        if (content is Multipart || content is BodyPart) {
            write(content, textStream, htmlStream)
            return
        }

        val stream = streamFor(part, textStream, htmlStream) ?: return
        when (content) {
            is String -> stream.write(content.toByteArray())
            // Content types without a DataContentHandler - text/calendar, or anything unparsable -
            // are handed to us as a stream instead of a string.
            is InputStream -> content.use { it.copyTo(stream) }
        }
    }

    /**
     * The stream the body of [part] belongs into, or `null` if it is not text at all.
     */
    private fun streamFor(part: Part, textStream: OutputStream, htmlStream: OutputStream): OutputStream? {
        // A missing or unparsable Content-Type means text/plain, so nothing gets lost.
        // @see <a href="https://www.rfc-editor.org/rfc/rfc2045#section-5.2">RFC 2045 - 5.2. Content-Type Defaults</a>
        val contentType = runCatching { ContentType(part.contentType) }.getOrNull() ?: return textStream

        return when {
            contentType.match("text/html") -> htmlStream
            // Every other text subtype (text/calendar, text/csv, ...) is readable as it stands and
            // is sometimes the only body a mail has, so it goes to the text stream rather than nowhere.
            contentType.match("text/*") -> textStream
            else -> null
        }
    }
}
