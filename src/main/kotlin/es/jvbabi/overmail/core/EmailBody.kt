package es.jvbabi.overmail.core

import es.jvbabi.overmail.util.MimeContent
import es.jvbabi.overmail.util.MimeUtility
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimePart
import java.io.InputStream

/**
 * Splits a message into its text body, its html body and its attachments.
 */
internal object EmailBody {

    data class Parts(
        val text: String?,
        val html: String?,
        val attachments: List<Email.Attachment>,
    )

    fun parse(message: Part, includeAttachments: Boolean): Parts =
        Collector(includeAttachments).apply { add(message) }.toParts()

    private class Collector(private val includeAttachments: Boolean) {
        private var text: StringBuilder? = null
        private var html: StringBuilder? = null
        private val attachments = mutableListOf<Email.Attachment>()

        fun toParts() = Parts(text?.toString(), html?.toString(), attachments)

        /**
         * Adds [content] - a [Part] or a [Multipart].
         */
        fun add(content: Any) {
            when (content) {
                is Multipart -> repeat(content.count) { add(content.getBodyPart(it)) }
                is Part -> addPart(content)
            }
        }

        private fun addPart(part: Part) {
            // A missing or unparsable Content-Type means text/plain, so nothing gets lost.
            // @see <a href="https://www.rfc-editor.org/rfc/rfc2045#section-5.2">RFC 2045 - 5.2. Content-Type Defaults</a>
            val contentType = runCatching { ContentType(part.contentType) }.getOrNull()

            when {
                part.disposition?.trim()?.lowercase() == Part.ATTACHMENT -> addAttachment(part, contentType)
                contentType?.match("multipart/*") == true -> add(contentOf(part))
                contentType == null -> addText(part, contentType, text ?: StringBuilder().also { text = it })
                contentType.match("text/html") -> addText(part, contentType, html ?: StringBuilder().also { html = it })
                // Every other text subtype (text/calendar, text/csv, ...) is readable as it stands and
                // is sometimes the only body a mail has, so it goes to the text body rather than nowhere.
                contentType.match("text/*") -> addText(part, contentType, text ?: StringBuilder().also { text = it })
                else -> addAttachment(part, contentType)
            }
        }

        private fun addText(part: Part, contentType: ContentType?, body: StringBuilder) {
            when (val content = contentOf(part)) {
                is String -> body.append(content)
                // Content types without a DataContentHandler - text/calendar, or anything unparsable -
                // are handed to us as a stream instead of a string.
                is InputStream -> body.append(content.use { it.readBytes() }.toString(MimeContent.charsetOf(contentType, null)))
            }
        }

        private fun addAttachment(part: Part, contentType: ContentType?) {
            if (!includeAttachments) return

            attachments += Email.Attachment(
                fileName = runCatching { part.fileName }.getOrNull()?.let(MimeUtility::decode)?.ifBlank { null },
                contentType = contentType?.baseType?.lowercase() ?: "application/octet-stream",
                contentId = (part as? MimePart)?.contentID?.trim()?.removeSurrounding("<", ">"),
                isInline = part.disposition?.trim()?.lowercase() == Part.INLINE,
                data = MimeContent.bytesOf(part),
            )
        }

        private fun contentOf(part: Part): Any = if (part is MimePart) MimeContent.of(part) else part.content
    }
}
