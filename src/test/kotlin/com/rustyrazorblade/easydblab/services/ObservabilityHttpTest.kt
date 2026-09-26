package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * The HTTP client stands in at the network boundary: an interceptor records each request and answers
 * it, so no socket is opened.
 */
class ObservabilityHttpTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val requests = mutableListOf<Request>()
    private val readTimeouts = mutableListOf<Int>()
    private val socks = mock<SocksProxyService>()
    private val stateManager = mock<ClusterStateManager>()

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests.add(chain.request())
                    readTimeouts.add(chain.readTimeoutMillis())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .body("done".toResponseBody())
                        .build()
                },
            ).build()

    private val factory =
        object : HttpClientFactory {
            override fun createClient(): OkHttpClient = client

            override fun close() = Unit
        }

    private val http = DefaultObservabilityHttp(factory, socks, stateManager)

    private fun state(tailscale: Boolean = false) =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            initConfig = InitConfig(tenant = "acme"),
            hosts = mapOf(ServerType.Control to listOf(control)),
        ).also { it.tailscaleActive = tailscale }

    @Test
    fun `a GET reaches the service on the control node with the cluster's tenant`() {
        whenever(stateManager.load()).thenReturn(state())

        val response = http.get(3100, "/loki/api/v1/labels")

        assertThat(response).isEqualTo(ObservabilityResponse(204, "done"))
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("http://10.0.1.5:3100/loki/api/v1/labels")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
    }

    @Test
    fun `a POST carries its body, content type and the tenant`() {
        whenever(stateManager.load()).thenReturn(state())

        http.post(9009, "/ingester/shutdown", body = "{}", contentType = "application/json")

        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
        assertThat(request.body?.contentType().toString()).startsWith("application/json")
        assertThat(Buffer().also { request.body?.writeTo(it) }.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `the SOCKS tunnel is opened unless Tailscale reaches the private address`() {
        whenever(stateManager.load()).thenReturn(state(tailscale = false))
        http.get(3100, "/ready")
        verify(socks).ensureRunning(control)

        val direct = mock<SocksProxyService>()
        whenever(stateManager.load()).thenReturn(state(tailscale = true))
        DefaultObservabilityHttp(factory, direct, stateManager).get(3100, "/ready")
        verify(direct, never()).ensureRunning(any())
    }

    @Test
    fun `a long call gets its own read timeout`() {
        whenever(stateManager.load()).thenReturn(state())

        http.post(3100, "/ingester/shutdown", timeout = Duration.ofSeconds(600))

        assertThat(readTimeouts.single()).isEqualTo(600_000)
    }

    @Test
    fun `a cluster with no control node fails fast`() {
        whenever(stateManager.load()).thenReturn(ClusterState(name = "lab", versions = mutableMapOf()))

        assertThatThrownBy { http.get(3100, "/ready") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control node")
    }
}
