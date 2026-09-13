package es.jvbabi.overmail.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * A server that answers the first command halfway and then drops the connection, the way one
 * enforcing a connection limit does.
 */
private suspend fun CoroutineScope.startDroppingServer(): ServerSocket {
    val server = aSocket(SelectorManager(Dispatchers.IO)).tcp().bind("127.0.0.1", 0)
    launch {
        while (true) {
            val socket = server.accept()
            launch {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                output.writeStringUtf8("* OK IMAP4rev1 ready\r\n")
                input.readLine()
                output.writeStringUtf8("* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n")
                output.flush()
                socket.close()
            }
        }
    }
    return server
}

/**
 * Drives [SocketInstance] against [startServer]. Every test runs under a timeout, because the bugs
 * under test show up as a hang, not as a wrong result.
 */
class SocketInstanceTest : FunSpec({

    test("a BAD completion fails the command and releases the socket") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, command ->
                    if (command.startsWith("SELECT")) listOf("$tag BAD Error in IMAP command SELECT: Too many arguments")
                    else listOf("$tag OK completed")
                }
                val instance = connect(server)

                val exception = shouldThrow<ImapCommandException> {
                    instance.execute("SELECT \"Sent Items\"").await()
                }
                exception.response shouldBe "A000 BAD Error in IMAP command SELECT: Too many arguments"

                // Used to block forever: on BAD the reader job kept reading and never unlocked
                // commandMutex.
                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a NO completion closes the response channel with the failure") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, _ -> listOf("$tag NO Mailbox does not exist") }
                val instance = connect(server)

                val response = instance.execute("SELECT \"Nope\"")
                shouldThrow<ImapCommandException> { response.response.consumeEach { } }
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("await drains responses longer than the channel buffer") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, _ -> (1..200).map { "* $it EXISTS" } + "$tag OK completed" }
                val instance = connect(server)

                // Deadlocks from line 65 of the response on if await() does not consume the channel.
                instance.execute("NOOP").await()
                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }
    test("a connection dropped mid response fails the command") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startDroppingServer()
                val instance = connect(server)

                // Used to end the command as a success: the reader stopped at the end of the
                // stream, and the caller took the response it got so far for the whole answer.
                shouldThrow<ImapConnectionClosedException> { instance.execute("LIST \"\" \"*\"").await() }
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a command on a closed instance fails instead of answering nothing") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, _ -> listOf("$tag OK completed") }
                val instance = connect(server)
                instance.execute("NOOP").await()
                instance.close()

                instance.isAlive shouldBe false
                shouldThrow<ImapConnectionClosedException> { instance.execute("NOOP").await() }
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a literal is read over its announced byte count") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                // Non ASCII and a bare LF: {n} counts bytes, and the literal must arrive unchanged.
                val message = "Subject: Grüße\r\n\r\nerste Zeile\nletzte Zeile\n".toByteArray(Charsets.UTF_8)
                server = scope.startRawServer { tag, command ->
                    if (command.startsWith("UID FETCH")) fetchBodyResponse(tag, message)
                    else "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
                }
                val instance = connect(server)

                val received = ByteArrayOutputStream()
                instance.executeWithLiterals("UID FETCH 1 BODY.PEEK[]") { received.write(it) }

                received.toByteArray() shouldBe message

                // Only works if the reader stopped after exactly `message.size` bytes: everything
                // after them is the closing paren and the tagged completion of the FETCH.
                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a NO completion fails a literal command and releases the socket") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, command ->
                    if (command.startsWith("UID FETCH")) listOf("$tag NO Server unavailable")
                    else listOf("$tag OK completed")
                }
                val instance = connect(server)

                val exception = shouldThrow<ImapCommandException> {
                    instance.executeWithLiterals("UID FETCH 1 BODY.PEEK[]") { }
                }
                exception.response shouldBe "A000 NO Server unavailable"

                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }
    test("a literal larger than the read buffer arrives complete") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                // Bigger than one read chunk, and not a multiple of it.
                val message = (1..5000).joinToString("\r\n") { "Zeile $it mit etwas Text" }.toByteArray(Charsets.UTF_8)
                server = scope.startRawServer { tag, command ->
                    if (command.startsWith("UID FETCH")) fetchBodyResponse(tag, message)
                    else "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
                }
                val instance = connect(server)

                val received = ByteArrayOutputStream()
                instance.executeWithLiterals("UID FETCH 1 BODY.PEEK[]") { received.write(it) }

                received.toByteArray() shouldBe message
                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a literal that does not end on a line break is read completely") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                // The closing paren of the FETCH follows the last body byte on the same line.
                val message = "Subject: Hallo\r\n\r\nkein Zeilenumbruch am Ende".toByteArray(Charsets.UTF_8)
                server = scope.startRawServer { tag, command ->
                    if (command.startsWith("UID FETCH")) fetchBodyResponse(tag, message)
                    else "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
                }
                val instance = connect(server)

                val received = ByteArrayOutputStream()
                instance.executeWithLiterals("UID FETCH 1 BODY.PEEK[]") { received.write(it) }

                received.toByteArray() shouldBe message
                instance.execute("NOOP").await()
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }
    test("a literal is handed over as one item, framed by its byte count") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                // 17 characters, 20 bytes: counting characters would take three bytes of the
                // following field into the literal.
                val subject = "Grüße aus München"
                val head = "* 1 FETCH (ENVELOPE (\"date\" {${subject.toByteArray(Charsets.UTF_8).size}}\r\n"
                server = scope.startRawServer { tag, _ ->
                    head.toByteArray(Charsets.UTF_8) +
                            subject.toByteArray(Charsets.UTF_8) +
                            " NIL))\r\n$tag OK FETCH completed\r\n".toByteArray(Charsets.US_ASCII)
                }
                val instance = connect(server)

                val items = mutableListOf<String>()
                instance.execute("FETCH 1 (ENVELOPE)").response.consumeEach { items += it }

                items shouldBe listOf(
                    head.trimEnd('\r', '\n'),
                    subject,
                    " NIL))",
                    "A000 OK FETCH completed"
                )
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }
})
