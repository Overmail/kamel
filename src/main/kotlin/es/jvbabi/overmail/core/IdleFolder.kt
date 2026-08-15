package es.jvbabi.overmail.core

import es.jvbabi.overmail.parser.IdleEvent
import es.jvbabi.overmail.parser.IdleResponseParser
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
                when (val event = IdleResponseParser.parse(line)) {
                    is IdleEvent.NewMessage -> config._onNewMessage.forEach { it(event.messageIndex) }
                    is IdleEvent.RemovedMessage -> config._onRemovedMessage.forEach { it(event.messageIndex) }
                    is IdleEvent.FlagsChanged -> config._onFlagChanged.forEach { it(event.messageIndex, event.flags) }
                    null -> Unit
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