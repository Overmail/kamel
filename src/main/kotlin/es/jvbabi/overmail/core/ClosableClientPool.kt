package es.jvbabi.overmail.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.lang.AutoCloseable

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
    name: String = "SocketInstancePool"
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(name)
    private val socketInstances = mutableSetOf<SocketInstance>()

    /** Guards [socketInstances]; folders, body fetches and watches share a pool across threads. */
    private val instancesLock = Mutex()

    internal suspend fun getClient(requireNew: Boolean = false): SocketInstance {
        if (!requireNew) instancesLock.withLock {
            // A socket that is gone answers every command with an empty response instead of an
            // error, so handing it out again would turn into an empty folder list or an empty
            // mailbox rather than into a failure.
            socketInstances.removeAll { !it.isAlive }

            socketInstances.firstOrNull { !it.commandMutex.isLocked }?.let { return it }
            // Every instance is busy: a new one, unless the pool is full, in which case the
            // command waits on the mutex of whichever instance it gets.
            if (socketInstances.size >= maxPoolSize) return socketInstances.random()
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
