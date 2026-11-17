package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * [ClusterReach] against the failure the JDK's own SOCKS client throws, not a hand-built stand-in.
 * The JDK gives that failure no type, so the classifier reads where it was thrown; this test fails if
 * a JDK release moves the throw out of the class the classifier names.
 */
class ClusterReachSocksIntegrationTest {
    @Test
    fun `a SOCKS server that refuses every authentication method is a request that never reached the cluster`() {
        ServerSocket(0).use { fakeProxy ->
            val answerer =
                Thread {
                    fakeProxy.accept().use { client ->
                        // The reply ends in a FIN, and what the client sends after it is drained: a close
                        // with unread bytes sends a reset, and the client would see that, not the reply.
                        client.getInputStream().readNBytes(GREETING_BYTES)
                        client.getOutputStream().write(NO_ACCEPTABLE_METHOD)
                        client.shutdownOutput()
                        client.getInputStream().readAllBytes()
                    }
                }.apply { start() }

            val failure =
                runCatching {
                    Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", fakeProxy.localPort))).use {
                        it.connect(InetSocketAddress("10.0.1.5", 6443), CONNECT_TIMEOUT_MS)
                    }
                }.exceptionOrNull()
            answerer.join(JOIN_TIMEOUT_MS)

            assertThat(failure).isInstanceOf(SocketException::class.java)
            assertThat(ClusterReach.neverReached(requireNotNull(failure))).isTrue()
        }
    }

    @Test
    fun `a connection reset after the request went out is not a request that never reached the cluster`() {
        ServerSocket(0).use { server ->
            val resetter =
                Thread {
                    server.accept().use { client ->
                        client.setSoLinger(true, 0)
                        client.getInputStream().read()
                    }
                }.apply { start() }

            assertThatThrownBy {
                Socket("127.0.0.1", server.localPort).use {
                    it.getOutputStream().write(1)
                    it.getInputStream().read()
                    it.getInputStream().read()
                }
            }.isInstanceOf(SocketException::class.java)
                .satisfies({ failure -> assertThat(ClusterReach.neverReached(failure)).isFalse() })
            resetter.join(JOIN_TIMEOUT_MS)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val JOIN_TIMEOUT_MS = 10_000L

        /** The SOCKS5 greeting the JDK sends: version 5, two methods, "no authentication" and "username/password". */
        const val GREETING_BYTES = 4

        /** The SOCKS5 server reply "no acceptable authentication method", which the JDK client reports as a SocketException. */
        val NO_ACCEPTABLE_METHOD = byteArrayOf(0x05, 0xFF.toByte())
    }
}
