package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ClusterServices
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import com.rustyrazorblade.easydblab.services.access.ServiceTarget
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
import java.time.Duration

/**
 * The HTTP client stands in at the network boundary: an interceptor records each request and answers
 * it, so no socket is opened. The seam stands in for the route: it hands out a fixed endpoint per
 * Service and records each open.
 */
class ObservabilityHttpTest {
    private val requests = mutableListOf<Request>()
    private val readTimeouts = mutableListOf<Int>()
    private val opened = mutableListOf<ServiceTarget>()

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

    private val access =
        object : ServiceAccess {
            override fun open(target: ServiceTarget): ServiceEndpoint {
                opened.add(target)
                return ServiceEndpoint.ViaSocks("10.0.1.5", target.nodePort, 23456)
            }
        }

    private val http = DefaultObservabilityHttp(access, { "acme" }, factory)

    @Test
    fun `a GET reaches the Service at the endpoint the seam opened, with the cluster's tenant`() {
        val response = http.get(ClusterServices.LOKI, "/loki/api/v1/labels")

        assertThat(response).isEqualTo(ObservabilityResponse(204, "done"))
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("http://10.0.1.5:3100/loki/api/v1/labels")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
        assertThat(opened).containsExactly(ClusterServices.LOKI)
    }

    @Test
    fun `a POST carries its body, content type and the tenant`() {
        http.post(ClusterServices.MIMIR, "/ingester/shutdown", body = "{}", contentType = "application/json")

        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("http://10.0.1.5:9009/ingester/shutdown")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
        assertThat(request.body?.contentType().toString()).startsWith("application/json")
        assertThat(Buffer().also { request.body?.writeTo(it) }.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `every request opens its Service again, so a replaced route is followed`() {
        http.get(ClusterServices.LOKI, "/ready")
        http.get(ClusterServices.LOKI, "/ready")

        assertThat(opened).containsExactly(ClusterServices.LOKI, ClusterServices.LOKI)
    }

    @Test
    fun `a long call gets its own read timeout`() {
        http.post(ClusterServices.LOKI, "/ingester/shutdown", timeout = Duration.ofSeconds(600))

        assertThat(readTimeouts.single()).isEqualTo(600_000)
    }

    @Test
    fun `a Service the seam cannot open fails before any request is sent`() {
        val unreachable =
            DefaultObservabilityHttp(
                { error("No control node in cluster state runs default/loki") },
                { "acme" },
                factory,
            )

        assertThatThrownBy { unreachable.get(ClusterServices.LOKI, "/ready") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control node")
        assertThat(requests).isEmpty()
    }
}
