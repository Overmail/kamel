package es.jvbabi.overmail.core

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts a fake IMAP server on the loopback interface. It greets every client and answers each
 * command via [respond], which receives the command tag and the command without its tag and
 * returns the raw bytes to send back.
 *
 * Raw bytes, not lines: a FETCH response carries the message as a literal whose length is
 * announced in bytes, so the tests have to control every byte - line endings included - that the
 * client gets to see.
 */
internal suspend fun CoroutineScope.startRawServer(respond: (tag: String, command: String) -> ByteArray): ServerSocket {
    val server = aSocket(SelectorManager(Dispatchers.IO)).tcp().bind("127.0.0.1", 0)
    launch {
        while (true) {
            val socket = server.accept()
            // One coroutine per connection: fetching a body runs over a second connection while the
            // first one is still selected.
            launch {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                output.writeStringUtf8("* OK IMAP4rev1 ready\r\n")
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    val tag = line.substringBefore(' ')
                    output.writeByteArray(respond(tag, line.substringAfter(' ')))
                    output.flush()
                }
            }
        }
    }
    return server
}

/**
 * [startRawServer] for responses that are plain lines; each one is terminated with CRLF.
 */
internal suspend fun CoroutineScope.startServer(respond: (tag: String, command: String) -> List<String>): ServerSocket =
    startRawServer { tag, command ->
        respond(tag, command).joinToString("") { "$it\r\n" }.toByteArray(Charsets.UTF_8)
    }

internal suspend fun connect(server: ServerSocket): SocketInstance {
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
 * The response a server sends for `UID FETCH <uid> BODY.PEEK[]`: the untagged FETCH line with the
 * literal size, the message itself, and the two lines that used to end up inside the body - the
 * closing paren of the FETCH and the tagged completion.
 */
internal fun fetchBodyResponse(tag: String, message: ByteArray, uid: Long = 1L): ByteArray =
    "* 1 FETCH (UID $uid BODY[] {${message.size}}\r\n".toByteArray(Charsets.US_ASCII) +
            message +
            ")\r\n$tag OK FETCH complete (0.005 + 0.000 secs).\r\n".toByteArray(Charsets.US_ASCII)
