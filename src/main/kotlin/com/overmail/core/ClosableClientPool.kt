package com.overmail.core

import org.slf4j.LoggerFactory
import java.lang.AutoCloseable

/**
 * Manages the pool of socket instances.
 */
abstract class ClosableClientPool(
    private val factory: SocketInstance.Factory,
    private val maxPoolSize: Int = 5,
    name: String = "SocketInstancePool"
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(name)
    private val socketInstances = mutableSetOf<SocketInstance>()

    internal suspend fun getClient(requireNew: Boolean = false): SocketInstance {
        if ((requireNew || socketInstances.all { it.commandMutex.isLocked } && socketInstances.size < maxPoolSize)) {
            logger.debug("Creating new socket instance")
            return this.factory()
                .also { socketInstances += it }
        }
        return socketInstances.firstOrNull { !it.commandMutex.isLocked } ?: this.socketInstances.random()
    }

    override fun close() {
        logger.debug("Closing pool with ${socketInstances.size} instances")
        this.socketInstances.forEach { it.close() }
    }
}