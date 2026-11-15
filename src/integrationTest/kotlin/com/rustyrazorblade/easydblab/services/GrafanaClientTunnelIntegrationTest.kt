package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.proxy.ProxiedHttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Regression test for issue 1104: the `down` save's Grafana annotation call failed with "Malformed
 * reply from SOCKS server", because the shared client's selector read a process-wide tunnel port that
 * named a tunnel since replaced. Two loopback sockets stand in for the old and the new tunnel; the old
 * one stays published as the process-wide port, and the call must open its connection on the new one.
 */
class GrafanaClientTunnelIntegrationTest {
    private val factory = ProxiedHttpClientFactory()
    private val previous: String? = System.getProperty(Constants.Proxy.PORT_PROPERTY)

    @AfterEach
    fun restore() {
        factory.close()
        if (previous == null) {
            System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        } else {
            System.setProperty(Constants.Proxy.PORT_PROPERTY, previous)
        }
    }

    @Test
    fun `an annotation call uses the current tunnel's port, not a stale process-wide one`() {
        val loopback = InetAddress.getByName(Constants.Proxy.TUNNEL_LISTEN_ADDRESS)
        ServerSocket(0, 1, loopback).use { stale ->
            ServerSocket(0, 1, loopback).use { current ->
                System.setProperty(Constants.Proxy.PORT_PROPERTY, stale.localPort.toString())
                val greeting = firstByteOn(current)
                val access = ServiceAccess { target -> ServiceEndpoint.ViaSocks("10.0.1.5", target.nodePort, current.localPort) }

                assertThatThrownBy {
                    DefaultGrafanaClient(EventBus(), access, factory)
                        .createAnnotation(GrafanaAnnotationRequest(text = "down", time = 1L))
                }.isInstanceOf(IllegalStateException::class.java)

                // 5 is the SOCKS version byte of the client's greeting: the request went to the new tunnel.
                assertThat(greeting.get(5, TimeUnit.SECONDS)).isEqualTo(SOCKS_VERSION)
                stale.soTimeout = STALE_ACCEPT_TIMEOUT_MS
                assertThatThrownBy { stale.accept() }.isInstanceOf(SocketTimeoutException::class.java)
            }
        }
    }

    private fun firstByteOn(socket: ServerSocket): CompletableFuture<Int> {
        val first = CompletableFuture<Int>()
        thread(isDaemon = true) {
            runCatching { socket.accept().use { first.complete(it.getInputStream().read()) } }
                .onFailure { first.completeExceptionally(it) }
        }
        return first
    }

    private companion object {
        const val SOCKS_VERSION = 5
        const val STALE_ACCEPT_TIMEOUT_MS = 200
    }
}
