package es.jvbabi.overmail.kamel

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
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.kotest.matchers.comparables.shouldBeLessThan

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
                    instance.execute("SELECT \"Sent Items\"")
                }
                exception.response shouldBe "A000 BAD Error in IMAP command SELECT: Too many arguments"

                // Used to block forever: on BAD the reader job kept reading and never unlocked
                // commandMutex.
                instance.execute("NOOP")
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a NO completion fails the read of the response") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, _ -> listOf("$tag NO Mailbox does not exist") }
                val instance = connect(server)

                shouldThrow<ImapCommandException> { instance.execute("SELECT \"Nope\"") { next() } }

                // The completion was read, so the socket carries the next command.
                instance.isAlive shouldBe true
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a response nobody reads is dropped") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                server = scope.startServer { tag, _ -> (1..200).map { "* $it EXISTS" } + "$tag OK completed" }
                val instance = connect(server)

                // The second command would read the rest of the first response as its own.
                instance.execute("NOOP")
                instance.execute("NOOP")
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
                shouldThrow<ImapConnectionClosedException> { instance.execute("LIST \"\" \"*\"") }
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
                instance.execute("NOOP")
                instance.close()

                instance.isAlive shouldBe false
                shouldThrow<ImapConnectionClosedException> { instance.execute("NOOP") }
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
                instance.execute("NOOP")
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

                instance.execute("NOOP")
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
                instance.execute("NOOP")
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
                instance.execute("NOOP")
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }
    test("a literal is one item of its response, framed by its byte count") {
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

                val responses = instance.execute("FETCH 1 (ENVELOPE)") {
                    buildList { while (true) add(next() ?: break) }
                }

                responses shouldBe listOf(listOf(head.trimEnd('\r', '\n'), subject, " NIL))"))
                instance.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a server that stops answering fails the command after the read timeout") {
        withServerScope { scope ->
            // Reads the command and never answers it; the socket stays open.
            val server = scope.startScriptedServer { input, _ -> while (input.readLine() != null) { } }
            val instance = connect(server, readTimeout = 300.milliseconds)

            shouldThrow<ImapConnectionClosedException> { instance.execute("NOOP") }.message shouldContain "300ms"

            // What it would still send belongs to a command nobody waits for any more.
            instance.isAlive shouldBe false
            server.close()
        }
    }

    test("a server that never greets fails the first command after the read timeout") {
        withServerScope { scope ->
            val server = aSocket(SelectorManager(Dispatchers.IO)).tcp().bind("127.0.0.1", 0)
            scope.launch { while (true) server.accept() }
            val instance = connect(server, readTimeout = 300.milliseconds)

            shouldThrow<ImapConnectionClosedException> { instance.execute("NOOP") }
            server.close()
        }
    }

    test("a literal shorter than announced fails the command") {
        withServerScope { scope ->
            val server = scope.startScriptedServer { input, output ->
                input.readLine()
                output.writeStringUtf8("* 1 FETCH (BODY[] {5678}\r\nSubject: cut off")
                output.flush()
            }

            // Streamed, as a body is ...
            val received = ByteArrayOutputStream()
            shouldThrow<ImapConnectionClosedException> {
                connect(server).executeWithLiterals("UID FETCH 1 BODY.PEEK[]") { received.write(it) }
            }
            received.size() shouldBe 0

            // ... and as an item of a response, as in an envelope.
            shouldThrow<ImapConnectionClosedException> { connect(server).execute("FETCH 1 (ENVELOPE)") { next() } }
            server.close()
        }
    }

    test("cancelling the caller cancels the read and closes the half read socket") {
        withServerScope { scope ->
            val server = scope.startScriptedServer { input, output ->
                input.readLine()
                output.writeStringUtf8("* 1 EXISTS\r\n")
                // The tagged completion never comes.
                while (input.readLine() != null) { }
            }
            val instance = connect(server)

            val reachedResponse = CompletableDeferred<Unit>()
            val command = launch {
                instance.execute("NOOP") {
                    next()
                    reachedResponse.complete(Unit)
                    next()
                }
            }
            reachedResponse.await()
            command.cancelAndJoin()

            instance.isAlive shouldBe false
            shouldThrow<ImapConnectionClosedException> { instance.execute("NOOP") }
            server.close()
        }
    }

    test("a caller cancelled before its command was sent does not leave the connection behind") {
        withServerScope { scope ->
            // Never greets, so the command waits before it is written.
            val server = aSocket(SelectorManager(Dispatchers.IO)).tcp().bind("127.0.0.1", 0)
            scope.launch { while (true) server.accept() }
            val instance = connect(server)

            val command = launch { instance.execute("NOOP") }
            delay(200.milliseconds)
            command.cancelAndJoin()

            // Whatever arrives now would be read by the next command as its own.
            instance.isAlive shouldBe false
            server.close()
        }
    }

    test("a command runs in the context of its caller") {
        withServerScope { scope ->
            val server = scope.startServer { tag, _ -> listOf("* 1 EXISTS", "$tag OK completed") }
            val instance = connect(server)

            val caller = CoroutineName("the caller")
            val reader = withContext(caller) {
                instance.execute("NOOP") { currentCoroutineContext()[CoroutineName] }
            }

            reader shouldBe caller
            instance.close()
            server.close()
        }
    }

    test("an IDLE the server never completes is given up after the DONE timeout") {
        withServerScope { scope ->
            // Answers the IDLE and reports a change, then goes silent, the way a connection a NAT
            // dropped while idling does: the DONE never arrives anywhere.
            val server = scope.startScriptedServer { input, output ->
                val tag = input.readLine()!!.substringBefore(' ')
                output.writeStringUtf8("+ idling\r\n* 23 EXISTS\r\n")
                while (input.readLine() != null) { }
                output.writeStringUtf8("$tag OK never sent\r\n")
            }
            val instance = connect(server, readTimeout = 20.seconds, doneTimeout = 300.milliseconds)

            val started = TimeSource.Monotonic.markNow()
            shouldThrow<IllegalStateException> {
                instance.idle { error("seen enough") }
            }

            // Used to wait the whole readTimeout before the caller got its connection back.
            started.elapsedNow() shouldBeLessThan 5.seconds
            instance.isAlive shouldBe false
            server.close()
        }
    }
})
