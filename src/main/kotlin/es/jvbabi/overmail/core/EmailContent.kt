package es.jvbabi.overmail.core

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

class EmailContent(
    private val email: Email
) {
    /**
     * The message source as the server sent it, in chunks, byte for byte.
     *
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

    /**
     * Use the parameters to determine which parts of the email content you want to retrieve.
     * If you try to access a part that is not requested, the method might hang indefinitely
     * since the requested stream is not being consumed.
     *
     * @throws ImapCommandException if the server refused the FETCH
     */
    fun getContent(
        rawStream: OutputStream,
        textStream: OutputStream,
        htmlStream: OutputStream,
    ) {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val failure = AtomicReference<Throwable?>(null)

        email.folder.imapClient.coroutineScope.launch {
            try {
                getRawContent().collect { chunk ->
                    pipeOut.write(chunk)
                    rawStream.write(chunk)
                }
            } catch (e: Throwable) {
                failure.set(e)
                // Cancellation belongs to the scope, not to this fetch, so it is only recorded.
                if (e is CancellationException) throw e
            } finally {
                // Closing in any case: the parser below reads until EOF, so a failed fetch would
                // block it forever instead of surfacing.
                pipeOut.close()
            }
        }

        // getInstance, not getDefaultInstance: the latter returns the JVM-wide default session and
        // ignores the properties passed here as soon as anything else created one first.
        val message = MimeMessage(Session.getInstance(Properties()), pipeIn)

        EmailBody.write(message, textStream, htmlStream)

        rawStream.flush()
        textStream.flush()
        htmlStream.flush()
        rawStream.close()
        textStream.close()
        htmlStream.close()

        failure.get()?.let { throw it }
    }
}
