package com.overmail.core

import com.overmail.util.substringAfterIgnoreCase
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

            val folderRegex = Regex("""\((.*?)\)\s+"(.*?)"\s+(.*)""")
            val folders = mutableListOf<ImapFolder>()
            response.response.consumeEach { line ->
                if (line.startsWith("${response.commandId} OK LIST", ignoreCase = true)) return@consumeEach
                val data = line.substringAfterIgnoreCase("LIST ")
                val match = folderRegex.find(data)
                if (match != null) {
                    val flags = match.groupValues[1].split(" ").map { it.trim() }
                    val delimiter = match.groupValues[2]
                    val path = match.groupValues[3].trim('"').split(delimiter)
                    val specialType = if (flags.contains("\\Trash")) ImapFolder.SpecialType.TRASH
                    else if (flags.contains("\\Junk")) ImapFolder.SpecialType.SPAM
                    else if (flags.contains("\\Sent")) ImapFolder.SpecialType.SENT
                    else if (flags.contains("\\Drafts")) ImapFolder.SpecialType.DRAFTS
                    else if (path.size == 1 && path[0] == "INBOX") ImapFolder.SpecialType.INBOX
                    else null

                    folders.add(ImapFolder(this, path, delimiter, specialType))
                    return@consumeEach
                }
                logger.warn("Failed to parse folder: $data")
            }

            return folders
        }
    }
}

data class SocketInstance(
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val isDebug: Boolean,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : AutoCloseable {
    typealias Factory = suspend () -> SocketInstance

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
        if (isDebug) println(message.trim())

        var isCancelled = false

        val job = coroutineScope.launch {
            while (!isCancelled) {
                val line = this@SocketInstance.input.readUTF8Line() ?: continue
                if (isDebug) println(line)
                channel.send(line)
                if (line.startsWith("$commandIdString OK")) break
            }
        }.also {
            it.invokeOnCompletion {
                commandMutex.unlock()
                isDone.complete(Unit)
                channel.close()
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
        suspend fun await(): CommandResponse {
            done.await()
            return this
        }
    }

    val isReady = CompletableDeferred<Unit>()

    init {
        this.coroutineScope.launch {
            while (true) {
                val line = this@SocketInstance.input.readUTF8Line() ?: continue
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