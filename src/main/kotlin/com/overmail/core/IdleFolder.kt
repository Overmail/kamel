package com.overmail.core

import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking

class IdleFolder(
    internal val folder: ImapFolder,
): AutoCloseable {
    private var client: SocketInstance? = null
    private suspend fun getClient(): SocketInstance {
        return client ?: folder.getClient(requireNew = true).also { client = it }
    }

    private var currentIdleCommand: SocketInstance.CommandResponse? = null

    suspend fun idle(config: IdleFolderConfig) {
        this.cancel()
        currentIdleCommand = getClient().execute("IDLE").also { commandResponse ->
            commandResponse.response.consumeEach { line ->
                val trimmed = line.trim()

                when {
                    trimmed.startsWith("* ") && trimmed.endsWith(" EXISTS") -> {
                        val messageIndex = trimmed.substringAfter("* ").substringBefore(" EXISTS").toLong()
                        config._onNewMessage.forEach { it(messageIndex) }
                    }
                    trimmed.startsWith("* ") && trimmed.endsWith(" EXPUNGE") -> {
                        val messageIndex = trimmed.substringAfter("* ").substringBefore(" EXPUNGE").toLong()
                        config._onRemovedMessage.forEach { it(messageIndex) }
                    }
                    trimmed.startsWith("* ") && "FETCH" in trimmed && "FLAGS" in trimmed -> {
                        // e.G: * 5 FETCH (FLAGS (\Seen \Flagged))
                        val messageIndex = trimmed.substringAfter("* ").substringBefore(" FETCH").toLong()
                        val flagsPart = trimmed.substringAfter("FLAGS (").substringBefore(")")
                        val flags = flagsPart
                            .split(" ")
                            .filter { it.isNotBlank() }
                            .map { Email.Flag.fromString(it) }
                        config._onFlagChanged.forEach { it(messageIndex, flags) }
                    }
                }
            }
        }
    }

    suspend fun cancel() {
        currentIdleCommand?.cancel()
    }

    suspend fun idle(config: IdleFolderConfig.() -> Unit) =
        idle(IdleFolderConfig().apply(config))

    class IdleFolderConfig {
        internal var _onNewMessage = mutableListOf<(messageUid: Long) -> Unit>()
        internal var _onRemovedMessage = mutableListOf<(messageUid: Long) -> Unit>()
        internal var _onFlagChanged = mutableListOf<(messageUid: Long, flags: List<Email.Flag>) -> Unit>()

        fun onNewMessage(block: (messageUid: Long) -> Unit) {
            _onNewMessage.add(block)
        }

        fun onRemovedMessage(block: (messageUid: Long) -> Unit) {
            _onRemovedMessage.add(block)
        }

        fun onFlagChanged(block: (messageUid: Long, flags: List<Email.Flag>) -> Unit) {
            _onFlagChanged.add(block)
        }
    }

    override fun close() = runBlocking {
        this@IdleFolder.cancel()
    }
}