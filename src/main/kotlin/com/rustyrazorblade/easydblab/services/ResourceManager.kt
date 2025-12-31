package com.rustyrazorblade.easydblab.services

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages lifecycle of closeable resources across the application.
 *
 * Resources (like CQL sessions, SOCKS proxies) register themselves when created.
 * The CommandExecutor calls [closeAll] after command execution in non-interactive mode,
 * ensuring clean shutdown without requiring each command to handle cleanup explicitly.
 */
interface ResourceManager {
    /**
     * Register a resource for cleanup on application exit.
     */
    fun register(resource: AutoCloseable)

    /**
     * Close all registered resources. Safe to call multiple times.
     */
    fun closeAll()
}

/**
 * Thread-safe implementation using ConcurrentLinkedQueue.
 */
class DefaultResourceManager : ResourceManager {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val resources = ConcurrentLinkedQueue<AutoCloseable>()

    override fun register(resource: AutoCloseable) {
        log.debug { "Registering resource for cleanup: ${resource::class.simpleName}" }
        resources.add(resource)
    }

    override fun closeAll() {
        log.debug { "Closing ${resources.size} registered resources" }
        while (true) {
            val resource = resources.poll() ?: break
            try {
                log.debug { "Closing resource: ${resource::class.simpleName}" }
                resource.close()
            } catch (e: Exception) {
                log.warn(e) { "Error closing resource: ${resource::class.simpleName}" }
            }
        }
    }
}
