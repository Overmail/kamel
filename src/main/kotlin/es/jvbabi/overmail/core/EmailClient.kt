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
import java.io.IOException
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
        val selectorManager = SelectorManager(coroutineScope.coroutineContext)
        aSocket(selectorManager).tcp()
            .connect(host, port)
            .let { if (ssl) it.tls(coroutineScope.coroutineContext) else it }
            .let { socket ->
                SocketInstance(
                    socket = socket,
                    isDebug = debug,
                    input = socket.openReadChannel(),
                    output = socket.openWriteChannel(autoFlush = true)
                )
                    .also { it.login(username, password) }
            }
    },
    name = "ImapClient/$username@$host:$port"
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun testConnection() {
        this.getClient()
    }

    suspend fun getFolders(onlyRoot: Boolean = false): List<ImapFolder> {
        this.getClient().use { socketInstance ->
            val response = socketInstance.execute(buildString {
                append("LIST \"\" \"")
                if (onlyRoot) append("\"")
                else append("*")
                append("\"")
            })

            val folders = mutableListOf<ImapFolder>()
            response.response.consumeEach { line ->
                if (line.startsWith("${response.commandId} OK LIST", ignoreCase = true)) return@consumeEach
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
}

typealias SocketInstanceFactory = suspend () -> SocketInstance
data class SocketInstance(
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val isDebug: Boolean,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : AutoCloseable {

    private val id = System.currentTimeMillis()
    private var lastCommandId: Int = 0
    internal val commandMutex = Mutex()

    suspend fun execute(command: String): CommandResponse {
        commandMutex.lock()
        isReady.await()
        val commandId = lastCommandId++
        val commandIdString = "A${commandId.toString().padStart(3, '0')}"
        val message = "$commandIdString $command"
        val channel = Channel<String>(Channel.BUFFERED)
        val isDone = CompletableDeferred<Unit>()
        this.output.writeStringUtf8("$message\r\n")
        if (isDebug) println("SI $id > " + message.trim())

        var isCancelled = false
        var failure: ImapCommandException? = null

        val job = coroutineScope.launch {
            while (!isCancelled) {
                val line = this@SocketInstance.input.readLine() ?: break
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
                    isReady.completeExceptionally(IOException("Connection closed before server greeting"))
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
        runBlocking {
            this@SocketInstance.coroutineScope.cancel()
            this@SocketInstance.socket.close()
        }
    }
}