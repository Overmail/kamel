package es.jvbabi.overmail.core

import es.jvbabi.overmail.util.Optional
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

private data class Streams(val raw: String, val text: String, val html: String)

/**
 * Builds a mail whose only connection is [server] and whose uid is 1.
 */
private fun emailOn(server: ServerSocket, scope: CoroutineScope): Email {
    val client = ImapClient(
        host = "127.0.0.1",
        port = (server.localAddress as InetSocketAddress).port,
        ssl = false,
        username = "user",
        password = "password",
        coroutineScope = scope
    )
    return Email(folder = ImapFolder(client, listOf("INBOX"), "/", null))
        .apply { uidValue = Optional.Set(1L) }
}

/**
 * Serves [message] as the literal of `UID FETCH 1 BODY.PEEK[]` and returns what `getContent`
 * wrote into its three streams.
 */
private suspend fun fetch(message: ByteArray): Streams {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var server: ServerSocket? = null
    try {
        return withTimeout(30.seconds) {
            val running = scope.startRawServer { tag, command ->
                if (command.startsWith("UID FETCH")) fetchBodyResponse(tag, message)
                else "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
            }
            server = running
            val email = emailOn(running, scope)

            val raw = ByteArrayOutputStream()
            val text = ByteArrayOutputStream()
            val html = ByteArrayOutputStream()
            // runInterruptible: getContent blocks its thread until the message is parsed, so a hang
            // has to be cut short by interrupting the thread - withTimeout alone cannot cancel it.
            runInterruptible(Dispatchers.IO) { email.content.getContent(raw, text, html) }

            Streams(
                raw = raw.toString(Charsets.UTF_8),
                text = text.toString(Charsets.UTF_8),
                html = html.toString(Charsets.UTF_8)
            )
        }
    } finally {
        server?.close()
        scope.cancel()
    }
}

private suspend fun fetch(message: String) = fetch(message.toByteArray(Charsets.UTF_8))

/**
 * Drives [EmailContent] against a fake server that answers a `BODY.PEEK[]` fetch with a literal.
 */
class EmailContentTest : FunSpec({

    test("the closing paren and the tagged completion stay out of every stream") {
        // The bug: execute() hands the whole response over line by line, so ")" and
        // "A00x OK FETCH complete (...)" were written into the body of every single part mail.
        val message = """
            Content-Type: text/plain; charset=utf-8
            Subject: Hallo

            erste Body-Zeile
            letzte Body-Zeile
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        val streams = fetch(message)

        listOf(streams.raw, streams.text, streams.html).forEach {
            it shouldNotContain "OK FETCH complete"
            it shouldNotContain "\r\n)\r\n"
        }
        streams.raw shouldBe message
    }

    test("the text stream ends on the last body line") {
        val message = "Content-Type: text/plain; charset=utf-8\r\n\r\nerste Body-Zeile\r\nletzte Body-Zeile\r\n"

        val streams = fetch(message)

        streams.text shouldBe "erste Body-Zeile\r\nletzte Body-Zeile\r\n"
        streams.html shouldBe ""
    }

    test("body lines starting with an asterisk survive") {
        // Second bug: getContent dropped every line starting with "*" to get rid of the single
        // untagged FETCH line, which also killed bullet lists and quoted spam markers.
        val message = "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
                "* Punkt eins\r\n" +
                "* Punkt zwei\r\n" +
                "*** Hinweis ***\r\n" +
                "Ende\r\n"

        val streams = fetch(message)

        streams.text shouldBe "* Punkt eins\r\n* Punkt zwei\r\n*** Hinweis ***\r\nEnde\r\n"
    }

    test("a line that looks like a tagged completion does not cut the body short") {
        val message = "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
                "Der Server antwortete mit:\r\n" +
                "A001 OK FETCH complete (0.005 s)\r\n" +
                "Das war der Fehler.\r\n"

        val streams = fetch(message)

        streams.text shouldContain "A001 OK FETCH complete (0.005 s)\r\n"
        streams.text shouldEndWith "Das war der Fehler.\r\n"
    }

    test("the literal length is counted in bytes, not in characters") {
        // {n} is a byte count in RFC 3501; with non ASCII bodies a character based reader stops
        // short and leaves the rest of the message on the socket.
        val body = "Grüße aus München — 😀 äöüß\r\nZweite Zeile\r\n"
        val message = "Content-Type: text/plain; charset=utf-8\r\n\r\n$body"

        val streams = fetch(message)

        streams.raw shouldBe message
        streams.text shouldBe body
    }

    test("a body with LF line endings is not rewritten to CRLF") {
        val message = "Content-Type: text/plain; charset=utf-8\n\nerste Zeile\nzweite Zeile\n"

        val streams = fetch(message)

        streams.raw shouldBe message
        streams.raw shouldNotContain "\r\n"
        streams.text shouldBe "erste Zeile\nzweite Zeile\n"
    }

    test("a multipart message is still split into text and html") {
        val message = "Content-Type: multipart/alternative; boundary=\"xyz\"\r\n\r\n" +
                "--xyz\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
                "Nur Text\r\n" +
                "--xyz\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n\r\n" +
                "<p>Nur HTML</p>\r\n" +
                "--xyz--\r\n"

        val streams = fetch(message)

        streams.text shouldContain "Nur Text"
        streams.html shouldContain "<p>Nur HTML</p>"
        // The epilog of a multipart message is dropped by the parser, so the protocol leftovers
        // only ever showed up here - in the raw stream.
        streams.raw shouldBe message
    }

    test("a refused fetch fails instead of hanging") {
        // Regression to #14: the FETCH ends on NO, and neither the flow nor getContent may wait
        // for a body that never arrives.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(30.seconds) {
                val running = scope.startRawServer { tag, command ->
                    if (command.startsWith("UID FETCH")) "$tag NO Server unavailable\r\n".toByteArray(Charsets.US_ASCII)
                    else "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
                }
                server = running
                val email = emailOn(running, scope)

                shouldThrow<ImapCommandException> { email.content.getRawContent().collect { } }

                val discard = ByteArrayOutputStream()
                shouldThrow<ImapCommandException> {
                    runInterruptible(Dispatchers.IO) {
                        email.content.getContent(discard, ByteArrayOutputStream(), ByteArrayOutputStream())
                    }
                }
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }
})
