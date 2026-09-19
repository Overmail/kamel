package es.jvbabi.overmail.core

import es.jvbabi.overmail.parser.FolderListParser
import es.jvbabi.overmail.parser.ImapStatus
import es.jvbabi.overmail.parser.TaggedResponseParser
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class ImapClient(
    val host: String,
    val port: Int,
    val ssl: Boolean = true,
    val username: String,
    val password: String,
    val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val debug: Boolean = false,
    maxConnections: Int = 500
): ClosableClientPool(
    maxPoolSize = maxConnections,
    factory = {
        // One per connection, closed with it: a selector manager only lets go of its selector once
        // it is closed, and one that was left open kept a Dispatchers.IO thread spinning in
        // select() after its socket was gone. Enough of them starved every IO coroutine.
        val selectorManager = SelectorManager(coroutineScope.coroutineContext)
        val instance = try {
            val tcpSocket = aSocket(selectorManager).tcp().connect(host, port)
            val socket = try {
                if (ssl) tcpSocket.tls(coroutineScope.coroutineContext) else tcpSocket
            } catch (e: Throwable) {
                tcpSocket.close()
                throw e
            }
            SocketInstance(
                socket = socket,
                isDebug = debug,
                input = socket.openReadChannel(),
                output = socket.openWriteChannel(autoFlush = true),
                selectorManager = selectorManager,
            )
        } catch (e: Throwable) {
            selectorManager.close()
            throw e
        }
        // Not in the pool yet, so nothing else would ever close it.
        try {
            instance.login(username, password)
        } catch (e: Throwable) {
            instance.close()
            throw e
        }
        instance
    },
    name = "ImapClient/$username@$host:$port"
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun testConnection() {
        this.getClient()
    }

    suspend fun getFolders(onlyRoot: Boolean = false): List<ImapFolder> {
        // Borrowed, not owned: closing it here left the instance in the pool, and the next command
        // got the dead socket handed back -- its LIST answered nothing, so the account looked like
        // it had no folders at all.
        val socketInstance = this.getClient()
        val response = socketInstance.execute(buildString {
            append("LIST \"\" \"")
            if (onlyRoot) append("\"")
            else append("*")
            append("\"")
        })

        val folders = mutableListOf<ImapFolder>()
        response.response.consumeEach { line ->
            if (TaggedResponseParser.parse(line, response.commandId) != null) return@consumeEach
            val folder = FolderListParser.parse(line)
            if (folder != null) {
                folders.add(ImapFolder(this, folder.path, folder.delimiter, folder.specialType))
                return@consumeEach
            }
            logger.warn("Failed to parse folder: $line")
        }

        return folders
    }
}

typealias SocketInstanceFactory = suspend () -> SocketInstance
data class SocketInstance(
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val isDebug: Boolean,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /** The selector [socket] was opened on, if it belongs to this instance alone. */
    private val selectorManager: SelectorManager? = null,
) : AutoCloseable {

    private val id = System.currentTimeMillis()
    private var lastCommandId: Int = 0
    internal val commandMutex = Mutex()

    @Volatile
    private var isClosed = false

    /** Whether this instance can still carry a command, see [ClosableClientPool.getClient]. */
    internal val isAlive: Boolean get() = !isClosed && !input.isClosedForRead

    suspend fun execute(command: String): CommandResponse {
        commandMutex.lock()
        val commandIdString: String
        val message: String
        try {
            // Before awaiting the greeting: a socket closed while waiting for one never gets it.
            if (isClosed) throw ImapConnectionClosedException("The connection is closed, cannot run $command")
            isReady.await()
            val commandId = lastCommandId++
            commandIdString = "A${commandId.toString().padStart(3, '0')}"
            message = "$commandIdString $command"
            this.output.writeStringUtf8("$message\r\n")
        } catch (e: Throwable) {
            // Nothing reads for this command yet, so the reader job below cannot unlock for it.
            commandMutex.unlock()
            throw e
        }
        if (isDebug) println("SI $id > " + message.trim())

        val channel = Channel<String>(Channel.BUFFERED)
        val isDone = CompletableDeferred<Unit>()

        var isCancelled = false
        var failure: Exception? = null

        val job = coroutineScope.launch {
            while (!isCancelled) {
                val line = this@SocketInstance.input.readLine()
                if (line == null) {
                    // The response ended without its tagged completion: the connection is gone.
                    // Ending the command as a success here is what made a dead socket look like an
                    // empty answer to whatever was asked.
                    // Unless we asked for it: a cancelled IDLE ends its response the same way.
                    if (!isCancelled) failure = ImapConnectionClosedException("The connection closed while reading the response to $message")
                    break
                }
                if (isDebug) println("SI $id < $line")
                channel.send(line)
                // NO and BAD terminate the command just like OK. Without them the loop keeps
                // reading, commandMutex is never unlocked and every later command on this socket
                // blocks forever.
                when (TaggedResponseParser.parse(line, commandIdString)) {
                    ImapStatus.OK -> break
                    ImapStatus.NO, ImapStatus.BAD -> {
                        failure = ImapCommandException(message, line)
                        break
                    }
                    null -> Unit
                }
                // A literal is framed here, on the socket, and handed over as one item: {n} counts
                // bytes, while readLine() has already decoded them, so splitting the literal off a
                // line would cut it at the wrong place as soon as it carries non ASCII bytes.
                val length = LITERAL_LENGTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                channel.send(readLiteral(length).toString(Charsets.UTF_8))
            }
        }.also {
            it.invokeOnCompletion {
                commandMutex.unlock()
                val cause = failure
                if (cause == null) isDone.complete(Unit) else isDone.completeExceptionally(cause)
                channel.close(cause)
            }
        }

        val cancel: suspend () -> Unit = {
            isCancelled = true
            job.cancel()
            this.output.writeStringUtf8("DONE\r\n")
            isDone.completeExceptionally(CancellationException("Command cancelled"))
        }

        val response = CommandResponse(
            commandId = commandIdString,
            response = channel,
            done = isDone,
            cancel = cancel
        )

        return response
    }

    /**
     * Executes [command] and hands the raw bytes of every literal (`{n}`) in its response to
     * [onLiteralChunk], chunk by chunk. Everything around the literals - the untagged response
     * lines, the closing paren, the tagged completion - is consumed and dropped.
     *
     * [execute] cannot do this: it is line based, hands the response over as a `Channel<String>`
     * and therefore neither knows where a literal ends nor keeps its bytes intact. Reading the
     * literal here, over the byte count the server announced, keeps every other command on the
     * line based path instead of rewriting it for the sake of `BODY[]`.
     *
     * @throws ImapCommandException if the server answered with `NO` or `BAD`
     */
    internal suspend fun executeWithLiterals(command: String, onLiteralChunk: suspend (ByteArray) -> Unit) {
        commandMutex.lock()
        try {
            isReady.await()
            val commandId = lastCommandId++
            val commandIdString = "A${commandId.toString().padStart(3, '0')}"
            val message = "$commandIdString $command"
            this.output.writeStringUtf8("$message\r\n")
            if (isDebug) println("SI $id > $message")

            while (true) {
                val line = this.input.readLine()
                    ?: throw ImapConnectionClosedException("The connection closed while reading the response to $message")
                if (isDebug) println("SI $id < $line")
                when (TaggedResponseParser.parse(line, commandIdString)) {
                    ImapStatus.OK -> return
                    ImapStatus.NO, ImapStatus.BAD -> throw ImapCommandException(message, line)
                    null -> Unit
                }
                val length = LITERAL_LENGTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                streamLiteral(length, onLiteralChunk)
            }
        } finally {
            commandMutex.unlock()
        }
    }

    /**
     * Reads exactly [length] bytes - the size the server announced, in bytes, not in characters.
     *
     * The literal is buffered as a whole; the commands going through [execute] carry envelopes and
     * header fields, not message bodies. [executeWithLiterals] streams instead.
     */
    private suspend fun readLiteral(length: Int): ByteArray =
        ByteArray(length).also { this.input.readFully(it, 0, length) }

    /**
     * Reads a literal of [length] bytes and passes it on in chunks.
     */
    private suspend fun streamLiteral(length: Int, onChunk: suspend (ByteArray) -> Unit) {
        var remaining = length
        val buffer = ByteArray(LITERAL_CHUNK_SIZE)
        while (remaining > 0) {
            val size = minOf(remaining, buffer.size)
            this.input.readFully(buffer, 0, size)
            onChunk(buffer.copyOf(size))
            remaining -= size
        }
    }

    class CommandResponse(
        val commandId: String,
        val response: Channel<String>,
        private val done: Deferred<Unit>,
        val cancel: suspend () -> Unit
    ) {
        /**
         * Waits for the tagged completion of the command and discards its response.
         *
         * @throws ImapCommandException if the server answered with `NO` or `BAD`
         */
        suspend fun await(): CommandResponse {
            // Draining is part of awaiting: the reader job fills a bounded channel and only
            // completes once every line was handed over, so an unread response longer than the
            // buffer would block the job - and with it the socket - forever.
            response.consumeEach { }
            done.await()
            return this
        }
    }

    val isReady = CompletableDeferred<Unit>()

    init {
        this.coroutineScope.launch {
            while (true) {
                val line = this@SocketInstance.input.readLine() ?: run {
                    isReady.completeExceptionally(ImapConnectionClosedException("The connection closed before the server greeting"))
                    return@launch
                }
                if (!isReady.isCompleted && line.startsWith("* OK")) break
            }
            isReady.complete(Unit)
        }
    }

    internal suspend fun login(username: String, password: String) {
        execute("LOGIN \"$username\" \"$password\"").await()
    }

    override fun close() {
        isClosed = true
        // A command waiting for a greeting that can no longer arrive would wait forever.
        isReady.completeExceptionally(ImapConnectionClosedException("The connection closed before the server greeting"))
        runBlocking {
            this@SocketInstance.coroutineScope.cancel()
            this@SocketInstance.socket.close()
            this@SocketInstance.selectorManager?.close()
        }
    }

    companion object {
        /**
         * A literal announces its size in bytes at the end of a response line, e.g.
         * `* 12 FETCH (BODY[] {5678}`.
         *
         * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-4.3">RFC 3501 - 4.3. Literals</a>
         */
        private val LITERAL_LENGTH_REGEX = Regex("""\{(\d+)\+?}$""")

        private const val LITERAL_CHUNK_SIZE = 8 * 1024
    }
}