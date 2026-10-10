package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.parser.generatedMessageId
import es.jvbabi.overmail.kamel.util.MimeUtility
import es.jvbabi.overmail.kamel.util.Optional
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.*
import kotlin.time.Instant

/**
 * Creates an [Email] from its message source and fills it with what a server would put into the
 * `ENVELOPE` of the same message.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-7.4.2">RFC 3501 - 7.4.2. FETCH Response</a>
 */
internal object EmailParser {

    fun parse(raw: ByteArray): Email {
        val message = try {
            MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
        } catch (e: MessagingException) {
            throw IllegalArgumentException("Could not read the message source", e)
        }

        val sentAt = runCatching { message.sentDate }.getOrNull()?.let { Instant.fromEpochMilliseconds(it.time) }
        val rawSubject = message.header("Subject")
        val from = message.addresses("From")
        val inReplyTo = message.header("In-Reply-To")?.let(MimeUtility::decode)?.ifBlank { null }

        return Email.ofSource(raw).apply {
            sentAt?.let { sentAtValue = Optional.Set(it) }
            subjectValue = Optional.Set(rawSubject?.let(MimeUtility::decode))
            fromValue = Optional.Set(from)
            // A server fills a missing Sender and Reply-To with From, so the same is done here.
            sendersValue = Optional.Set(message.addresses("Sender").ifEmpty { from })
            replyToValue = Optional.Set(message.addresses("Reply-To").ifEmpty { from })
            toValue = Optional.Set(message.addresses("To"))
            ccValue = Optional.Set(message.addresses("Cc"))
            bccValue = Optional.Set(message.addresses("Bcc"))
            inReplyToValue = Optional.Set(inReplyTo)
            messageIdValue = Optional.Set(
                message.header("Message-ID")?.trim()?.removeSurrounding("<", ">")?.ifBlank { null }
                    ?: generatedMessageId(sentAt, rawSubject, inReplyTo, from)
            )
        }
    }

    private fun MimeMessage.header(name: String): String? = runCatching { getHeader(name, null) }.getOrNull()

    /**
     * The addresses of all [name] headers, the members of a group included. A header that cannot be
     * parsed yields no addresses instead of failing the whole message.
     */
    private fun MimeMessage.addresses(name: String): Set<EmailUser> {
        val header = runCatching { getHeader(name, ",") }.getOrNull() ?: return emptySet()
        val addresses = runCatching { InternetAddress.parseHeader(header, false) }.getOrNull() ?: return emptySet()

        return addresses
            .flatMap { if (it.isGroup) it.getGroup(false)?.toList().orEmpty() else listOf(it) }
            .filter { !it.address.isNullOrBlank() }
            .map { EmailUser(it.address, it.personal) }
            .toSet()
    }
}
