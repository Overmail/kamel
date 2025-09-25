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

    suspend fun runCommand(command: String): ImapMessage {
        isReady.await()
        val commandId = lastCommandId++
        val commandIdString = "A${commandId.toString().padStart(3, '0')}"
        val message = ImapMessage(commandIdString, command, false)
        messages.add(message)
        this.output.writeStringUtf8("$commandIdString $command\r\n")
        return message
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
                    lastUnfinishedMessage.content
                        .apply { if (this.isNotEmpty()) this.append("\n") }
                        .append(data)
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
    var isFinished: Boolean
) {
    val response: CompletableDeferred<String> = CompletableDeferred()
    var content = StringBuilder()

    fun done() {
        isFinished = true
        response.complete(content.lines().joinToString("\n"))
    }

    override fun toString(): String {
        return "[$id] $command --> $content; finished: $isFinished"
    }
}