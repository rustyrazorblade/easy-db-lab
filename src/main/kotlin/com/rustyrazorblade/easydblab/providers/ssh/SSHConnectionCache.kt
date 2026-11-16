package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * One open SSH connection per host, safe to share between threads.
 *
 * `down` runs its teardown steps in parallel, and each asks for the control node's connection at
 * the same time. The check for a live session and the connect happen under a lock of that host's
 * own, so concurrent callers for one host get the same connection and it is never connected twice,
 * while connections to different hosts open in parallel.
 *
 * @property connect Opens a new connection to a host.
 */
class SSHConnectionCache(
    private val connect: (Host) -> ISSHClient,
) {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    private val connections = ConcurrentHashMap<Host, ISSHClient>()

    // One lock per host, so a connect (which can take seconds over SSM) holds up only callers for
    // that host. Connecting inside ConcurrentHashMap.compute would block every host whose key
    // shares a bin with it.
    private val hostLocks = ConcurrentHashMap<Host, Any>()

    /** The open connection to [host], replacing one whose session has closed. */
    fun get(host: Host): ISSHClient =
        synchronized(hostLocks.computeIfAbsent(host) { Any() }) {
            val existing = connections[host]
            when {
                existing != null && existing.isSessionOpen() -> existing
                else -> {
                    if (existing != null) {
                        log.warn { "Session to ${host.alias} is no longer valid, will reconnect" }
                        connections.remove(host, existing)
                        closeQuietly(existing, host)
                    }
                    connect(host).also { connections[host] = it }
                }
            }
        }

    /** Closes and forgets the connection to [host], if there is one. */
    fun drop(host: Host) {
        connections.remove(host)?.let { closeQuietly(it, host) }
    }

    /** Closes every connection and empties the cache. */
    fun closeAll() {
        connections.forEach { (host, connection) -> closeQuietly(connection, host) }
        connections.clear()
    }

    /** How many connections are open. */
    fun size(): Int = connections.size

    @Suppress("TooGenericExceptionCaught")
    private fun closeQuietly(
        connection: ISSHClient,
        host: Host,
    ) {
        try {
            connection.close()
        } catch (e: IOException) {
            log.debug(e) { "IO error closing the session to ${host.alias}" }
        } catch (e: RuntimeException) {
            log.debug(e) { "Runtime error closing the session to ${host.alias}" }
        }
    }
}
