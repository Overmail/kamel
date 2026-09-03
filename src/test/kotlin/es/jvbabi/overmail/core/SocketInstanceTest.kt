package es.jvbabi.overmail.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.sockets.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

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
})
