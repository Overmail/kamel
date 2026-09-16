package es.jvbabi.overmail.core

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * Fetches the message source of [email] and splits it into its parts.
 */
internal class EmailContent(
    private val email: Email
) {
    /**
     * The bytes come out of the `BODY[]` literal of the FETCH response, read over its announced
     * length, so neither the response lines around it nor a rewritten line ending can end up in
     * the message.
     */
    fun getRawContent(): Flow<ByteArray> = channelFlow {
        val uid = email.uid.await()
        email.folder.getClient().executeWithLiterals("UID FETCH $uid BODY.PEEK[]") { chunk ->
            send(chunk)
        }
    }

    suspend fun getContent(includeAttachments: Boolean): Email.Content {
        val raw = getRawContent()
            .fold(ByteArrayOutputStream()) { buffer, chunk -> buffer.apply { write(chunk) } }
            .toByteArray()

        return withContext(Dispatchers.IO) {
            // getInstance, not getDefaultInstance: the latter returns the JVM-wide default session and
            // ignores the properties passed here as soon as anything else created one first.
            val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
            val parts = EmailBody.parse(message, includeAttachments)
            Email.Content(raw = raw, text = parts.text, html = parts.html, attachments = parts.attachments)
        }
    }
}
