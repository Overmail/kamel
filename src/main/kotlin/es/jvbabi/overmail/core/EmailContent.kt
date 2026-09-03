package es.jvbabi.overmail.core

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.*

class EmailContent(
    private val email: Email
) {
    fun getRawContent(): MutableSharedFlow<String> {
        val flow = MutableSharedFlow<String>()
        email.folder.imapClient.coroutineScope.launch {
            val response = email.folder.getClient().execute("UID FETCH ${email.uid.await()} BODY.PEEK[]")
            response.response.consumeEach {
                flow.emit("OVERMAIL_CONTENT: $it")
            }
            flow.emit("OVERMAIL_DONE")
        }
        return flow
    }

    /**
     * Use the parameters to determine which parts of the email content you want to retrieve.
     * If you try to access a part that is not requested, the method might hang indefinitely
     * since the requested stream is not being consumed.
     */
    fun getContent(
        rawStream: OutputStream,
        textStream: OutputStream,
        htmlStream: OutputStream,
    ) {
        val raw = getRawContent()

        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        email.folder.imapClient.coroutineScope.launch {
            raw
                .takeWhile { it != "OVERMAIL_DONE" }
                .map { it.removePrefix("OVERMAIL_CONTENT: ") }
                .filterNot { it.startsWith("*") }
                .collect {
                    val line = it + "\r\n"
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    pipeOut.write(bytes)
                    rawStream.write(bytes)
                }
            pipeOut.close()
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
    }
}
