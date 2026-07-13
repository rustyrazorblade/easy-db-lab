package com.rustyrazorblade.easydblab.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A userspace TCP port-forward that reuses the existing `ssh -D` SOCKS5 tunnel to reach a
 * cluster-private endpoint over plain loopback.
 *
 * The bridge binds a [ServerSocket] on `127.0.0.1:0` (loopback only, OS-assigned ephemeral port,
 * exposed as [localPort]). For every accepted inbound connection it opens an outbound
 * [Socket] scoped to a [Proxy] of type [Proxy.Type.SOCKS] pointed at `127.0.0.1:socksPort` and
 * connects it to `(targetHost, targetPort)` using an **unresolved** address so the SOCKS proxy
 * performs the DNS resolution and connect on the remote side of the tunnel. Bytes are then pumped
 * both directions by daemon threads. It is oblivious to the protocol riding on top, so it works
 * for any JDBC driver (raw-TCP such as postgresql/mysql or HTTP-based such as trino/clickhouse).
 *
 * The SOCKS [Proxy] object is scoped to this bridge's own outbound sockets only. This bridge
 * NEVER sets, clears, or otherwise touches the JVM-global `socksProxyHost`/`socksProxyPort`
 * system properties — doing so would capture every socket in the process (including the AWS SDK)
 * and was the cause of the #725 incident. All threads are daemon threads so the JVM can exit even
 * if a bridge is never closed, and [close] tears down the listener and every live socket.
 *
 * @property socksPort Loopback port of the running `ssh -D` SOCKS5 proxy.
 * @property targetHost Cluster-private host the tunnel should connect to (resolved remotely).
 * @property targetPort Port on [targetHost].
 */
class SocksTcpBridge(
    private val socksPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    private val log = KotlinLogging.logger {}

    private val serverSocket = ServerSocket()
    private val liveSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    @Volatile
    private var running = true

    /** Loopback port the bridge is listening on; a JDBC URL should target `127.0.0.1:localPort`. */
    val localPort: Int

    init {
        serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
        localPort = serverSocket.localPort
    }

    /** Starts the acceptor daemon thread. Call exactly once, after construction. */
    fun start() {
        thread(isDaemon = true, name = "socks-bridge-acceptor-$localPort") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running) {
            val inbound =
                try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    // ServerSocket closed by close(); stop accepting.
                    if (running) log.debug(e) { "Bridge acceptor stopped unexpectedly on port $localPort" }
                    break
                }
            handleConnection(inbound)
        }
    }

    private fun handleConnection(inbound: Socket) {
        liveSockets.add(inbound)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val outbound = Socket(proxy)
        try {
            outbound.connect(InetSocketAddress.createUnresolved(targetHost, targetPort))
        } catch (e: IOException) {
            // Outbound connect failed (tunnel down / target unreachable). Close the inbound
            // socket so the driver observes a reset rather than hanging silently.
            log.debug(e) { "Bridge outbound connect failed to $targetHost:$targetPort via 127.0.0.1:$socksPort" }
            closeQuietly(outbound)
            liveSockets.remove(inbound)
            closeQuietly(inbound)
            return
        }
        liveSockets.add(outbound)
        // A concurrent close() may fire between the successful connect() above and these pumps
        // starting; that is intentional and harmless. close() shuts the outbound socket, so the
        // pump's first read/write hits EOF/IOException and closes both sockets in `finally`.
        // Do not "fix" this into a lock around connect()+pump — that would deadlock teardown.
        pump(inbound, outbound, "in-out")
        pump(outbound, inbound, "out-in")
    }

    private fun pump(
        from: Socket,
        to: Socket,
        direction: String,
    ) {
        thread(isDaemon = true, name = "socks-bridge-pump-$direction-$localPort") {
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (e: IOException) {
                // Socket closed or reset — expected on teardown or peer disconnect.
                log.trace(e) { "Bridge pump ended on port $localPort" }
            } finally {
                liveSockets.remove(from)
                liveSockets.remove(to)
                closeQuietly(from)
                closeQuietly(to)
            }
        }
    }

    /** Idempotent: stops accepting, closes the listener and every live inbound/outbound socket. */
    override fun close() {
        running = false
        closeQuietly(serverSocket)
        synchronized(liveSockets) {
            liveSockets.toList().forEach { closeQuietly(it) }
            liveSockets.clear()
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        runCatching { closeable.close() }
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}

/**
 * Factory for [SocksTcpBridge]. Abstracted to allow test injection of a fake bridge without a
 * real SOCKS proxy. Mirrors the injectable
 * [JdbcConnectionFactory][com.rustyrazorblade.easydblab.services.sql.JdbcConnectionFactory].
 */
fun interface SocksTcpBridgeFactory {
    fun open(
        socksPort: Int,
        targetHost: String,
        targetPort: Int,
    ): SocksTcpBridge
}

/** Default factory that constructs a [SocksTcpBridge] and starts its acceptor thread. */
val defaultSocksTcpBridgeFactory: SocksTcpBridgeFactory =
    SocksTcpBridgeFactory { socksPort, targetHost, targetPort ->
        SocksTcpBridge(socksPort, targetHost, targetPort).also { it.start() }
    }
