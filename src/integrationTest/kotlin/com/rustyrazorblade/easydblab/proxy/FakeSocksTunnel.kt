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
 * @param behaviour how the tunnel's far end answers
 */
internal class FakeSocksTunnel(
    private val behaviour: Behaviour = Behaviour.WORKING,
) : AutoCloseable {
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

    val port: Int = server.localPort

    init {
        thread(isDaemon = true, name = "fake-socks-$port") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                clients += client
                thread(isDaemon = true, name = "fake-socks-$port-client") { runCatching { serve(client) } }
            }
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

    override fun close() {
        server.close()
        clients.forEach { runCatching { it.close() } }
    }

    companion object {
        /** How long a [Behaviour.SLOW_THEN_SILENT] tunnel takes to answer the CONNECT. */
        const val SLOW_REPLY_MS = 700L

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
