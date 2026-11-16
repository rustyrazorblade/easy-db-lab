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
    fun `a SOCKS server that answers nonsense is a request that never reached the cluster`() {
        ServerSocket(0).use { fakeProxy ->
            val answerer =
                Thread {
                    fakeProxy.accept().use { client ->
                        client.getInputStream().read()
                        client.getOutputStream().write(byteArrayOf(0x7f, 0x7f))
                    }
                }.apply { start() }

            val failure =
                runCatching {
                    Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", fakeProxy.localPort))).use {
                        it.connect(InetSocketAddress.createUnresolved("10.0.1.5", 6443), CONNECT_TIMEOUT_MS)
                    }
                }.exceptionOrNull()
            answerer.join()

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
            }.satisfies({ failure -> assertThat(ClusterReach.neverReached(failure)).isFalse() })
            resetter.join()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
