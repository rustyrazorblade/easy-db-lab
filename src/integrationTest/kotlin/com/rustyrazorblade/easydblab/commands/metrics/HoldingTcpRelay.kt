package com.rustyrazorblade.easydblab.commands.metrics

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A loopback TCP relay to [targetHost]:[targetPort] that a test can make hold requests: while
 * [hold] is on, bytes from a client are swallowed, never forwarded, so the client waits on an answer
 * that does not come.  [heldRequest] completes when the first request is held.
 *
 * It stands in for a Redis that stops answering, so a test can put the stream in a blocked Redis
 * call at a moment it observes rather than guesses.
 */
internal class HoldingTcpRelay(
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    var hold: Boolean = false

    val heldRequest = CompletableFuture<Unit>()

    val port: Int = server.localPort

    init {
        thread(isDaemon = true, name = "relay-accept-$port") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = Socket(targetHost, targetPort)
                sockets += client
                sockets += upstream
                thread(isDaemon = true) { runCatching { pump(client.getInputStream(), upstream.getOutputStream(), holdable = true) } }
                thread(isDaemon = true) { runCatching { pump(upstream.getInputStream(), client.getOutputStream(), holdable = false) } }
            }
        }
    }

    private fun pump(
        from: InputStream,
        to: OutputStream,
        holdable: Boolean,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = from.read(buffer)
            if (read < 0) return
            if (holdable && hold) {
                heldRequest.complete(Unit)
            } else {
                to.write(buffer, 0, read)
                to.flush()
            }
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER_SIZE = 8192
    }
}
