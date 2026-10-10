package es.jvbabi.overmail.kamel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * A server that only knows `AUTHENTICATE XOAUTH2` and accepts [validToken]. [startServer] cannot
 * play this: the SASL exchange sends untagged lines and the server has to remember where it is.
 *
 * [received] gets the decoded credentials the client sent.
 */
private suspend fun CoroutineScope.startXoauth2Server(
    validToken: String,
    received: CompletableDeferred<String>,
): ServerSocket {
    val server = aSocket(SelectorManager(Dispatchers.IO)).tcp().bind("127.0.0.1", 0)
    launch {
        val socket = server.accept()
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        output.writeStringUtf8("* OK IMAP4rev1 ready\r\n")
        while (true) {
            val line = input.readLine() ?: break
            val tag = line.substringBefore(' ')
            val command = line.substringAfter(' ')
            if (command != "AUTHENTICATE XOAUTH2") {
                output.writeStringUtf8("$tag OK completed\r\n")
                continue
            }
            output.writeStringUtf8("+ \r\n")
            val credentials = Base64.decode(input.readLine()!!).decodeToString()
            received.complete(credentials)
            if (credentials == "user=user@example.com\u0001auth=Bearer $validToken\u0001\u0001") {
                output.writeStringUtf8("$tag OK AUTHENTICATE completed\r\n")
            } else {
                // What Gmail sends: the error as a challenge, and NO only after the client answered it.
                val error = Base64.encode("""{"status":"400","schemes":"Bearer","scope":"https://mail.google.com/"}""".toByteArray())
                output.writeStringUtf8("+ $error\r\n")
                input.readLine() shouldBe ""
                output.writeStringUtf8("$tag NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)\r\n")
            }
        }
    }
    return server
}

class LoginTest : FunSpec({

    test("XOAUTH2 sends the token as a bearer token") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                val received = CompletableDeferred<String>()
                server = scope.startXoauth2Server(validToken = "ya29.token", received = received)
                val instance = connect(server)

                instance.login(ImapClient.Auth.BearerAuth("user@example.com", "ya29.token"))
                received.await() shouldBe "user=user@example.com\u0001auth=Bearer ya29.token\u0001\u0001"

                // The socket is usable afterwards.
                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a rejected XOAUTH2 token fails the login instead of hanging") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startXoauth2Server(validToken = "ya29.token", received = CompletableDeferred())
                val instance = connect(server)

                val exception = shouldThrow<ImapCommandException> {
                    instance.login(ImapClient.Auth.BearerAuth("user@example.com", "expired"))
                }
                exception.response shouldBe "A000 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)"

                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("Auth does not print its secret") {
        ImapClient.Auth.BasicAuth("user", "hunter2").toString() shouldNotContain "hunter2"
        ImapClient.Auth.BearerAuth("user", "ya29.token").toString() shouldNotContain "ya29.token"
    }
})
