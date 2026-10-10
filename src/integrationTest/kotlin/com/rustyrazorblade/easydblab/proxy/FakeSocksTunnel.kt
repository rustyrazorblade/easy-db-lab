package com.rustyrazorblade.easydblab.proxy

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * A stand-in for the local end of an `ssh -D` tunnel, on an OS-assigned loopback port, for the real
 * [SocksTunnelReachabilityProbe].
 *
 * With [farEndAlive] it answers a SOCKS5 CONNECT and sends an `sshd` banner, as a working tunnel to
 * the control node does. Without it, it accepts connections and the SOCKS5 greeting but drops every
 * CONNECT: the local listener of a tunnel whose SSH connection died while ssh kept running, which a
 * client sees as "Malformed reply from SOCKS server".
 */
internal class FakeSocksTunnel(
    private val farEndAlive: Boolean = true,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    val port: Int = server.localPort

    init {
        thread(isDaemon = true, name = "fake-socks-$port") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { client.use { serve(it) } }
            }
        }
    }

    private fun serve(client: Socket) {
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
        if (!farEndAlive) return
        output.write(byteArrayOf(SOCKS_VERSION, 0, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.write("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
        output.flush()
    }

    override fun close() = server.close()

    private companion object {
        const val BACKLOG = 50
        const val SOCKS_VERSION: Byte = 5
        const val REQUEST_HEADER = 3L
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val IPV4_LENGTH = 4L
        const val PORT_LENGTH = 2L
    }
}
