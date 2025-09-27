package com.overmail.core

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class ImapClient : AutoCloseable {

    val host: String
    val port: Int
    val ssl: Boolean
    val auth: Boolean
    val username: String
    val password: String
    val coroutineScope: CoroutineScope

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
                    socket,
                    socket.openReadChannel(),
                    socket.openWriteChannel(autoFlush = true)
                )
            }
        return socket
    }

    private suspend fun getSocket(): SocketInstance {
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
        val response = getSocket().runCommand("LIST \"\" \"*\"")
        response.response.await()

        val folderRegex = Regex("""\((.*?)\)\s+"(.*?)"\s+(.*)""")
        val folders = response.content.lines().mapNotNull { line ->
            if (line.startsWith("OK LIST")) return@mapNotNull null
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

                return@mapNotNull ImapFolder(this, path, delimiter, specialType)
            }
            logger.warn("Failed to parse folder: $data")
            null
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
}

data class SocketInstance(
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var lastCommandId: Int = 0
    private val messages = mutableListOf<ImapMessage>()
    private val commandMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun runCommand(command: String): ImapMessage {
        isReady.await()
        // Ensure commands do not overlap
        commandMutex.lock()
        try {
            val commandId = lastCommandId++
            val commandIdString = "A${commandId.toString().padStart(3, '0')}"
            val message = ImapMessage(commandIdString, command, false, accumulate = true, onLine = null, onLiteral = null) {
                // Release when finished
                if (commandMutex.isLocked) commandMutex.unlock()
            }
            messages.add(message)
            this.output.writeStringUtf8("$commandIdString $command\r\n")
            return message
        } catch (t: Throwable) {
            if (commandMutex.isLocked) commandMutex.unlock()
            throw t
        }
    }

    // Stream with literal support
    suspend fun runCommandStreamWithLiterals(
        command: String,
        onLine: (String) -> Unit,
        onLiteral: (ByteArray) -> Unit
    ): ImapMessage {
        isReady.await()
        commandMutex.lock()
        try {
            val commandId = lastCommandId++
            val commandIdString = "A${commandId.toString().padStart(3, '0')}"
            val message = ImapMessage(commandIdString, command, false, accumulate = false, onLine = onLine, onLiteral = onLiteral) {
                if (commandMutex.isLocked) commandMutex.unlock()
            }
            messages.add(message)
            this.output.writeStringUtf8("$commandIdString $command\r\n")
            return message
        } catch (t: Throwable) {
            if (commandMutex.isLocked) commandMutex.unlock()
            throw t
        }
    }

    val isReady = CompletableDeferred<Unit>()

    init {
        this.coroutineScope.launch {
            while (!this@SocketInstance.input.isClosedForRead) {
                val line = this@SocketInstance.input.readUTF8Line() ?: continue
                if (!isReady.isCompleted && line.startsWith("* OK")) isReady.complete(Unit)
                val lastUnfinishedMessage = messages.lastOrNull { !it.isFinished }
                if (lastUnfinishedMessage == null) continue

                if (line.startsWith(lastUnfinishedMessage.id + " OK") || line.startsWith("* ")) {
                    val data = line.substringAfter(" ")
                    lastUnfinishedMessage.handleLine(data)
                    // Detect IMAP literal marker at end of line and read exact octets
                    val m = Regex("\\{(\\d+)}\\s*$").find(data)
                    if (m != null) {
                        val size = m.groupValues[1].toInt()
                        if (size > 0) {
                            val bytes = ByteArray(size)
                            this@SocketInstance.input.readFully(bytes, 0, size)
                            lastUnfinishedMessage.handleLiteral(bytes)
                        }
                    }
                    if (line.startsWith(lastUnfinishedMessage.id + " OK")) lastUnfinishedMessage.done()
                }
            }
        }
    }

    suspend fun login(username: String, password: String) {
        val response = runCommand("LOGIN \"$username\" \"$password\"")
        response.response.await()
    }
}

class ImapMessage(
    val id: String,
    val command: String,
    var isFinished: Boolean,
    private val accumulate: Boolean = true,
    private val onLine: ((String) -> Unit)? = null,
    private val onLiteral: ((ByteArray) -> Unit)? = null,
    private val onDone: (() -> Unit)? = null
) {
    val response: CompletableDeferred<String> = CompletableDeferred()
    private val contentBuilder = StringBuilder()
    val content: CharSequence
        get() = contentBuilder

    fun handleLine(data: String) {
        // Stream out immediately if a callback is provided
        onLine?.invoke(data)
        // Accumulate only when requested
        if (accumulate) {
            if (contentBuilder.isNotEmpty()) contentBuilder.append("\n")
            contentBuilder.append(data)
        }
    }

    fun handleLiteral(bytes: ByteArray) {
        onLiteral?.invoke(bytes)
        if (accumulate) {
            // If accumulating, append a placeholder note about literal length to preserve structure
            if (contentBuilder.isNotEmpty()) contentBuilder.append("\n")
            contentBuilder.append("<literal ${'$'}{bytes.size} bytes>")
        }
    }

    fun done() {
        isFinished = true
        // Complete with the aggregated content if we accumulated, otherwise with an empty string
        response.complete(if (accumulate) contentBuilder.lines().joinToString("\n") else "")
        onDone?.invoke()
    }

    override fun toString(): String {
        return "[$id] $command --> $contentBuilder; finished: $isFinished"
    }
}