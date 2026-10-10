package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.parser.IdleResponseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches a folder for changes, see [MailFolder.getIdleFolder].
 */
interface IdleFolder : AutoCloseable {

    /**
     * The changes the server reports from the moment the flow is collected. Cancelling the
     * collector ends the watching; collecting again watches again, on the same connection.
     *
     * One collector at a time: a second one waits for the first to end.
     */
    fun events(): Flow<IdleEvent>

    /**
     * Collects [events] into the callbacks of [config]. Suspends for as long as it watches.
     */
    suspend fun idle(config: IdleFolderConfig) {
        events().collect { event ->
            when (event) {
                is IdleEvent.NewMessage -> config._onNewMessage.forEach { it(event.sequenceNumber) }
                is IdleEvent.RemovedMessage -> config._onRemovedMessage.forEach { it(event.sequenceNumber) }
                is IdleEvent.FlagsChanged -> config._onFlagChanged.forEach { it(event.sequenceNumber, event.flags) }
            }
        }
    }

    suspend fun idle(config: IdleFolderConfig.() -> Unit) =
        idle(IdleFolderConfig().apply(config))

    class IdleFolderConfig {
        internal var _onNewMessage = mutableListOf<(sequenceNumber: Long) -> Unit>()
        internal var _onRemovedMessage = mutableListOf<(sequenceNumber: Long) -> Unit>()
        internal var _onFlagChanged = mutableListOf<(sequenceNumber: Long, flags: List<Email.Flag>) -> Unit>()

        fun onNewMessage(block: (sequenceNumber: Long) -> Unit) {
            _onNewMessage.add(block)
        }

        fun onRemovedMessage(block: (sequenceNumber: Long) -> Unit) {
            _onRemovedMessage.add(block)
        }

        fun onFlagChanged(block: (sequenceNumber: Long, flags: List<Email.Flag>) -> Unit) {
            _onFlagChanged.add(block)
        }
    }

    /** Releases what the watching holds. A collector of [events] that is still running fails. */
    override fun close() {}
}

/**
 * Watches [folder] with `IDLE`, on a connection it takes when [events] is first collected and
 * keeps from then on.
 */
internal class ImapIdleFolder(
    private val folder: ImapFolder,
) : IdleFolder {
    @Volatile
    private var client: SocketInstance? = null

    /** Held by the collector that is watching: there is one connection, and one `IDLE` on it. */
    private val watching = Mutex()

    private suspend fun getClient(): SocketInstance {
        // A connection the server dropped is replaced: watching again is how a caller recovers.
        client?.takeIf { it.isAlive }?.let { return it }
        return folder.getClient(requireNew = true).also { client = it }
    }

    override fun events(): Flow<IdleEvent> = flow {
        watching.withLock {
            getClient().idle { line -> IdleResponseParser.parse(line)?.let { emit(it) } }
        }
    }

    override fun close() {
        client?.close()
        client = null
    }
}
