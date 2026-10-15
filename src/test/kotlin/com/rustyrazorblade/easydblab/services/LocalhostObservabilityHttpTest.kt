package com.rustyrazorblade.easydblab.services

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.Proxy

/**
 * The HTTP client stands in at the network boundary: an interceptor records each request and answers
 * it, so no socket is opened.
 */
class LocalhostObservabilityHttpTest {
    private val requests = mutableListOf<Request>()

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests.add(chain.request())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("ok".toResponseBody())
                        .build()
                },
            ).build()

    private val ports = mapOf(9009 to 41009, 3100 to 41100)

    @Test
    fun `a request reaches the port the stack publishes for the backend, with the stack's tenant`() {
        val http = LocalhostObservabilityHttp(tenant = "acme|default", publishedPorts = ports, client = client)

        val response = http.get(3100, "/loki/api/v1/labels")

        assertThat(response).isEqualTo(ObservabilityResponse(200, "ok"))
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("http://127.0.0.1:41100/loki/api/v1/labels")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme|default")
    }

    @Test
    fun `a backend the stack does not publish fails fast and names its port`() {
        val http = LocalhostObservabilityHttp(tenant = "default", publishedPorts = ports, client = client)

        assertThatThrownBy { http.get(3200, "/ready") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("3200")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `the default client goes straight to localhost, never through a proxy`() {
        val target = LocalhostObservabilityEndpoint(tenant = "default", publishedPorts = ports).target(9009)

        assertThat(target.client.proxy).isEqualTo(Proxy.NO_PROXY)
        assertThat(target.baseUrl).isEqualTo("http://127.0.0.1:41009")
    }
}
