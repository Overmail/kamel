package com.overmail.core

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory

class ImapClient : AutoCloseable {

    val host: String
    val port: Int
    val ssl: Boolean
    val auth: Boolean
    val username: String
    val password: String
    val coroutineScope: CoroutineScope
    val debug: Boolean

    private var socket: SocketInstance? = null

    private val logger = LoggerFactory.getLogger(this::class.java)

    constructor(builder: ImapClientConfig.() -> Unit) {
        val config = ImapClientConfig().apply(builder)

        this.host = config.host
        this.port = config.port
        this.ssl = config.ssl
        this.auth = config.auth
        this.username = config.username
        this.password = config.password
        this.coroutineScope = config.scope
        this.debug = config.debug
    }

    @Suppress("unused")
    constructor(config: ImapClientConfig) {
        this.host = config.host
        this.port = config.port
        this.ssl = config.ssl
        this.auth = config.auth
        this.username = config.username
        this.password = config.password
        this.coroutineScope = config.scope
        this.debug = config.debug
    }

    suspend fun connect() {
        this.socket?.socket?.close()
        this.socket = createNewSocket()

        this.socket!!.isReady.await()
    }

    internal suspend fun createNewSocket(): SocketInstance {
        val selectorManager = SelectorManager(this.coroutineScope.coroutineContext)
        val socket = aSocket(selectorManager).tcp()
            .connect(this.host, this.port)
            .tls(this.coroutineScope.coroutineContext)
            .let { socket ->
                SocketInstance(
                    socket = socket,
                    isDebug = this.debug,
                    input = socket.openReadChannel(),
                    output = socket.openWriteChannel(autoFlush = true)
                )
            }
        return socket
    }

    internal suspend fun getSocket(): SocketInstance {
        if (socket == null || socket?.socket?.isClosed == true) {
            connect()
            return socket!!
        }
        return socket!!
    }

    suspend fun login() {
        getSocket().login(this.username, this.password)
    }


    override fun close() {
        socket?.socket?.close()
        coroutineScope.cancel()
    }

    suspend fun getFolders(): List<ImapFolder> {
        val response = getSocket().execute("LIST \"\" \"*\"")

        val folderRegex = Regex("""\((.*?)\)\s+"(.*?)"\s+(.*)""")
        val folders = mutableListOf<ImapFolder>()
        response.response.consumeEach { line ->
            if (line.startsWith("${response.commandId} OK LIST")) return@consumeEach
            val data = line.substringAfter("LIST ")
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

class ImapClientConfig {
    var host: String = ""
    var port: Int = 993
    var ssl: Boolean = true
    var auth: Boolean = true
    var username: String = ""
    var password: String = ""
    var scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    var debug: Boolean = false
}

data class SocketInstance(
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val isDebug: Boolean,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var lastCommandId: Int = 0
    private val commandMutex = Mutex()

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

        coroutineScope.launch {
            while (true) {
                val line = this@SocketInstance.input.readUTF8Line() ?: continue
                if (isDebug) println(line)
                channel.send(line)
                if (line.startsWith("$commandIdString OK")) break
            }
        }.invokeOnCompletion {
            commandMutex.unlock()
            isDone.complete(Unit)
            channel.close()
        }

        val response = CommandResponse(
            command = message,
            commandId = commandIdString,
            response = channel,
            done = isDone
        )

        return response
    }

    data class CommandResponse(
        val command: String,
        val commandId: String,
        val response: Channel<String>,
        private val done: Deferred<Unit>
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

    suspend fun login(username: String, password: String) {
        execute("LOGIN \"$username\" \"$password\"").await()
    }
}