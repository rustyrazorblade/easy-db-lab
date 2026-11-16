package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RoutedObservabilityHttpIntegrationTest : BaseKoinTest() {
    private val server = MockWebServer()
    private lateinit var state: ClusterState

    // The base client's own selector points at a port nothing serves, so a request that used it
    // instead of the route's explicit proxy would fail.
    private val factory =
        object : HttpClientFactory {
            override fun createClient(): OkHttpClient = OkHttpClient.Builder().proxySelector(DeadSocksSelector).build()

            override fun close() = Unit
        }

    @BeforeEach
    fun start() {
        server.start(InetAddress.getLoopbackAddress(), 0)
        state =
            ClusterState(
                name = "lab",
                versions = mutableMapOf(),
                initConfig = InitConfig(tenant = "acme"),
                hosts =
                    mapOf(
                        ServerType.Control to
                            listOf(ClusterHost("54.0.0.1", "127.0.0.1", "control0", "us-west-2a", "i-1")),
                    ),
            )
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `a direct route reaches the control node with no proxy and sends the tenant`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("ok")
                .build(),
        )

        val response =
            RoutedObservabilityHttp(
                state,
                ObservabilityRoute.Direct,
                factory,
            ).get(server.port, "/prometheus/api/v1/query?query=up")

        assertThat(response).isEqualTo(ObservabilityResponse(200, "ok"))
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertThat(recorded?.headers?.get(Constants.Observability.TENANT_HEADER)).isEqualTo("acme")
        assertThat(recorded?.target).isEqualTo("/prometheus/api/v1/query?query=up")
        assertThat(System.getProperty("socksProxyHost")).isNull()
        assertThat(System.getProperty("socksProxyPort")).isNull()
    }

    @Test
    fun `a SOCKS route opens its connection to the tunnel port on 127 0 0 1`() {
        ServerSocket(0, 1, InetAddress.getByName(Constants.Proxy.TUNNEL_LISTEN_ADDRESS)).use { tunnel ->
            val firstByte = CompletableFuture<Int>()
            thread(isDaemon = true) {
                runCatching { tunnel.accept().use { firstByte.complete(it.getInputStream().read()) } }
                    .onFailure { firstByte.completeExceptionally(it) }
            }

            assertThatThrownBy {
                RoutedObservabilityHttp(state, ObservabilityRoute.Socks(tunnel.localPort), factory).get(server.port, "/x")
            }.isInstanceOf(IOException::class.java)

            // 5 is the SOCKS version byte of the client's greeting.
            assertThat(firstByte.get(5, TimeUnit.SECONDS)).isEqualTo(SOCKS_VERSION)
            assertThat(server.requestCount).isZero()
            assertThat(System.getProperty("socksProxyHost")).isNull()
            assertThat(System.getProperty("socksProxyPort")).isNull()
        }
    }

    private object DeadSocksSelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1)))

        override fun connectFailed(
            uri: URI,
            sa: SocketAddress,
            ioe: IOException,
        ) = Unit
    }

    private companion object {
        const val SOCKS_VERSION = 5
    }
}
