package es.jvbabi.overmail.kamel

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.lang.AutoCloseable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the pool of socket instances.
 *
 * A borrowed instance is never closed by its caller: this pool only lends out, it has no way of
 * taking an instance back, so closing one would leave the pool holding a socket it still believes
 * in. The pool owns every instance it created and closes them all in [close].
 */
abstract class ClosableClientPool(
    private val factory: SocketInstanceFactory,
    private val maxPoolSize: Int = 5,
    name: String = "SocketInstancePool",
    /** See [ImapClient.checkConnectionsAfter]. */
    private val checkConnectionsAfter: Duration = 60.seconds,
    /** See [ImapClient.connectionCheckTimeout]. */
    private val connectionCheckTimeout: Duration = 5.seconds,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(name)
    private val socketInstances = mutableSetOf<SocketInstance>()

    /** Guards [socketInstances]; folders, body fetches and watches share a pool across threads. */
    private val instancesLock = Mutex()

    internal suspend fun getClient(requireNew: Boolean = false): SocketInstance {
        if (!requireNew) while (true) {
            val quiet = instancesLock.withLock {
                // A socket that is gone answers every command with an empty response instead of an
                // error, so handing it out again would turn into an empty folder list or an empty
                // mailbox rather than into a failure.
                socketInstances.removeAll { !it.isAlive }

                val free = socketInstances.filter { !it.commandMutex.isLocked }
                free.firstOrNull { !it.isQuietFor(checkConnectionsAfter) }?.let { return it }
                // A connection that has been quiet for a while may be gone without anybody having
                // been told: a NAT or a firewall forgets it without a FIN, and the socket still
                // looks open. A command on it would only fail after the read timeout, so it is
                // asked with a NOOP first. Claimed while the lock is held, so no other caller gets
                // it handed out as free while the check runs.
                free.firstOrNull { it.tryClaimForCheck() }
                    ?: run {
                        // Every instance is busy: a new one, unless the pool is full, in which case
                        // the command waits on the mutex of whichever instance it gets.
                        //
                        // Unless the caller is busy on one of them itself, as the collector of a
                        // fetch that downloads a body for every mail is. Its fetch only goes on
                        // once this command is done, so waiting for a connection that other such
                        // collectors hold in the same way would never end. It gets a connection
                        // beyond the limit instead.
                        val caller = currentCoroutineContext()[Job]
                        if (socketInstances.size >= maxPoolSize && socketInstances.none { it.isHeldBy(caller) }) {
                            return socketInstances.random()
                        }
                        null
                    }
            } ?: break

            // The round trip happens outside the lock, nobody else should wait for it.
            if (quiet.checkClaimed(connectionCheckTimeout)) return quiet
            logger.debug("Dropping a socket instance that did not answer a NOOP within $connectionCheckTimeout")
            // Closed by the check already; removed here so the pool does not count it until the
            // next caller comes by.
            instancesLock.withLock { socketInstances -= quiet }
        }

        logger.debug("Creating new socket instance")
        // Connecting and logging in happens outside the lock: it takes a round trip, and a folder
        // factory even selects on top of that, which no other caller of this pool should wait for.
        val instance = factory()
        instancesLock.withLock { socketInstances += instance }
        return instance
    }

    override fun close() {
        logger.debug("Closing pool with ${socketInstances.size} instances")
        this.socketInstances.forEach { it.close() }
        this.socketInstances.clear()
    }
}
