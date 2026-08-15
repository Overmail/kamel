package es.jvbabi.overmail.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
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
import kotlin.time.Duration.Companion.seconds

/**
 * Starts a fake IMAP server on the loopback interface. It greets the client and answers each
 * command via [respond], which receives the command tag and the command without its tag and
 * returns the lines to send back.
 */
private suspend fun CoroutineScope.startServer(respond: (tag: String, command: String) -> List<String>): ServerSocket {
    val server = aSocket(SelectorManager(Dispatchers.IO)).tcp().bind("127.0.0.1", 0)
    launch {
        val socket = server.accept()
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        output.writeStringUtf8("* OK IMAP4rev1 ready\r\n")
        while (true) {
            val line = input.readLine() ?: break
            val tag = line.substringBefore(' ')
            respond(tag, line.substringAfter(' ')).forEach { output.writeStringUtf8("$it\r\n") }
        }
    }
    return server
}

private suspend fun connect(server: ServerSocket): SocketInstance {
    val port = (server.localAddress as InetSocketAddress).port
    val socket = aSocket(SelectorManager(Dispatchers.IO)).tcp().connect("127.0.0.1", port)
    return SocketInstance(
        socket = socket,
        input = socket.openReadChannel(),
        output = socket.openWriteChannel(autoFlush = true),
        isDebug = false
    )
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
})
