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
 * the same time. The check for a live session and the connect happen in one atomic step per host,
 * so concurrent callers get the same connection and a host is never connected twice.
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

    /** The open connection to [host], replacing one whose session has closed. */
    fun get(host: Host): ISSHClient =
        connections.compute(host) { _, existing ->
            when {
                existing == null -> connect(host)
                existing.isSessionOpen() -> existing
                else -> {
                    log.warn { "Session to ${host.alias} is no longer valid, will reconnect" }
                    closeQuietly(existing, host)
                    connect(host)
                }
            }
        } ?: error("unreachable: compute always returns a connection for ${host.alias}")

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
