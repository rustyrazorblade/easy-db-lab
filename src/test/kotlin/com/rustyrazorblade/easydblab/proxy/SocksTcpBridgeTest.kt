package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lifecycle tests for [SocksTcpBridge]. The byte round-trip through a real SOCKS5 proxy is
 * covered end-to-end by [SocksTcpBridgeIntegrationTest] (TestContainers); here we verify the
 * listener binds loopback-only, tears down cleanly and idempotently, uses daemon threads, and
 * closes the inbound socket when the outbound SOCKS connect fails — all without Docker.
 */
class SocksTcpBridgeTest {
    /**
     * Reserve an ephemeral port, then release it so connecting to it is refused. There is a
     * theoretical TOCTOU window (the OS could hand the freed port to another process before we
     * connect), but it cannot flake these tests: the port is only ever used as the bridge's
     * `socksPort`, and every test needs merely that the SOCKS handshake to it *fails*. Even if
     * the port were reused, the new listener would not speak SOCKS5, so the handshake still fails.
     */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `binds an ephemeral loopback port exposed as localPort`() {
        SocksTcpBridge(socksPort = deadPort(), targetHost = "10.0.0.1", targetPort = 5432).use { bridge ->
            assertThat(bridge.localPort).isGreaterThan(0)

            // The listener accepts connections on loopback while open.
            Socket().use { client ->
                assertThatCode {
                    client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), bridge.localPort), 1000)
                }.doesNotThrowAnyException()
            }
        }
    }

    @Test
    fun `close stops the listener so subsequent connects are refused`() {
        val bridge = SocksTcpBridge(socksPort = deadPort(), targetHost = "10.0.0.1", targetPort = 5432)
        bridge.start()
        val port = bridge.localPort

        bridge.close()

        Socket().use { client ->
            assertThatCode {
                client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
            }.isInstanceOf(ConnectException::class.java)
        }
    }

    @Test
    fun `close is idempotent`() {
        val bridge = SocksTcpBridge(socksPort = deadPort(), targetHost = "10.0.0.1", targetPort = 5432)
        bridge.start()

        assertThatCode {
            bridge.close()
            bridge.close()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `bridge threads are daemon so they do not block jvm exit`() {
        SocksTcpBridge(socksPort = deadPort(), targetHost = "10.0.0.1", targetPort = 5432).use { bridge ->
            bridge.start()
            // Trigger a connection so a pump/handler path spins up too.
            Socket().use { client ->
                client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), bridge.localPort), 1000)
                Thread.sleep(200)
            }

            val bridgeThreads =
                Thread.getAllStackTraces().keys.filter { it.name.startsWith("socks-bridge-") }
            assertThat(bridgeThreads).isNotEmpty()
            assertThat(bridgeThreads).allMatch { it.isDaemon }
        }
    }

    @Test
    fun `outbound connect failure closes the inbound socket so the driver sees a reset`() {
        // socksPort points at a dead port, so the SOCKS handshake fails for every connection.
        SocksTcpBridge(socksPort = deadPort(), targetHost = "10.0.0.1", targetPort = 5432).use { bridge ->
            bridge.start()

            Socket().use { client ->
                client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), bridge.localPort), 1000)
                // The bridge should close the inbound socket after the outbound connect fails,
                // which surfaces to the client as end-of-stream rather than a hang.
                client.soTimeout = 3000
                val firstByte = client.getInputStream().read()
                assertThat(firstByte).isEqualTo(-1)
            }
        }
    }
}
