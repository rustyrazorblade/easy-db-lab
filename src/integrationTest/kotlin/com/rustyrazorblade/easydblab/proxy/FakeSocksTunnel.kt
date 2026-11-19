package com.rustyrazorblade.easydblab.proxy

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A stand-in for the local end of an `ssh -D` tunnel, on an OS-assigned loopback port, for the real
 * [SocksTunnelReachabilityProbe]. Each connection is served on its own thread, so one that never
 * answers does not hold up the next.
 *
 * The port stays bound until [close]. A test that needs a tunnel to stop working while ssh keeps
 * running calls [die] instead of closing: a closed port is free for any other listener, and the
 * test JVMs run in parallel, so another JVM's working tunnel can bind it and pass the probe.
 *
 * @param initialBehaviour how the tunnel's far end answers
 */
internal class FakeSocksTunnel(
    initialBehaviour: Behaviour = Behaviour.WORKING,
) : AutoCloseable {
    @Volatile
    private var behaviour: Behaviour = initialBehaviour

    /** How the far end of the tunnel answers a client. */
    enum class Behaviour {
        /** Answers the SOCKS5 CONNECT and sends an `sshd` banner, as a working tunnel does. */
        WORKING,

        /**
         * Accepts the greeting and drops every CONNECT: a tunnel whose SSH connection died while ssh
         * kept running, which a client sees as "Malformed reply from SOCKS server".
         */
        DROPS_CONNECT,

        /** Accepts the TCP connection and never answers anything, not even the greeting. */
        SILENT,

        /**
         * Answers the greeting and, after [SLOW_REPLY_MS], the CONNECT, and then never sends a byte: a
         * tunnel whose far end is slow and then dead.
         */
        SLOW_THEN_SILENT,
    }

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Socket>()
    private val lock = Any()
    private var closed = false

    val port: Int = server.localPort

    private val acceptor =
        thread(isDaemon = true, name = "fake-socks-$port") {
            generateSequence { runCatching { server.accept() }.getOrNull() }.forEach(::admit)
        }

    /** Serves [client] on its own thread, unless the tunnel is already closed, when it drops it. */
    private fun admit(client: Socket) {
        val open =
            synchronized(lock) {
                if (!closed) clients += client
                !closed
            }
        when {
            open -> thread(isDaemon = true, name = "fake-socks-$port-client") { runCatching { serve(client) } }
            else -> runCatching { client.close() }
        }
    }

    private fun serve(client: Socket) {
        if (behaviour == Behaviour.SILENT) return
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        // Greeting: VER, NMETHODS, METHODS; answer "no authentication".
        input.readUnsignedByte()
        input.skipNBytes(input.readUnsignedByte().toLong())
        output.write(byteArrayOf(SOCKS_VERSION, 0))
        output.flush()
        // Request: VER, CMD, RSV, then ATYP, address, port.
        input.skipNBytes(REQUEST_HEADER)
        when (input.readUnsignedByte()) {
            ATYP_IPV4 -> input.skipNBytes(IPV4_LENGTH)
            ATYP_DOMAIN -> input.skipNBytes(input.readUnsignedByte().toLong())
            else -> throw SocketException("unsupported address type")
        }
        input.skipNBytes(PORT_LENGTH)
        when (behaviour) {
            Behaviour.DROPS_CONNECT -> client.close()
            Behaviour.SLOW_THEN_SILENT -> {
                Thread.sleep(SLOW_REPLY_MS)
                output.write(CONNECT_REPLY)
                output.flush()
            }
            Behaviour.WORKING -> {
                output.write(CONNECT_REPLY)
                output.write("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
                output.flush()
                client.close()
            }
            Behaviour.SILENT -> Unit
        }
    }

    /**
     * Turns this tunnel into one whose SSH connection died while ssh kept running: it still accepts
     * connections on [port], but drops every CONNECT ([Behaviour.DROPS_CONNECT]).
     */
    fun die() {
        behaviour = Behaviour.DROPS_CONNECT
    }

    /**
     * Stops listening before it returns, so a test that probes the port next finds nothing there. On
     * Linux, closing a [ServerSocket] while another thread is blocked in `accept()` only signals that
     * thread; the JDK releases the listening socket once the thread leaves `accept()`. Until then the
     * port still accepts, and a connection taken in that window would be served as if the tunnel still
     * worked. So this waits for the accept thread to end, and drops every connection accepted after the close.
     */
    override fun close() {
        synchronized(lock) { closed = true }
        server.close()
        acceptor.join()
        clients.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * How long a [Behaviour.SLOW_THEN_SILENT] tunnel takes to answer the CONNECT: most of the probe
         * bound `SocksTunnelReachabilityProbeIntegrationTest` uses, so the reply still comes within it.
         */
        const val SLOW_REPLY_MS = 2500L

        private const val BACKLOG = 50
        private const val SOCKS_VERSION: Byte = 5
        private const val REQUEST_HEADER = 3L
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val IPV4_LENGTH = 4L
        private const val PORT_LENGTH = 2L
        private val CONNECT_REPLY = byteArrayOf(SOCKS_VERSION, 0, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
    }
}
