package com.rustyrazorblade.easydblab.localstack

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * How the tool talks to a local stack's Grafana on `127.0.0.1`: whether it answers, and the two
 * provisioning reloads, which need the server admin by basic auth. A real Grafana answering them is
 * covered by `LocalStackReadOnlyIntegrationTest`.
 */
class LocalGrafanaHttpTest {
    private lateinit var server: MockWebServer
    private val grafana = LocalGrafanaHttp()

    @BeforeEach
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    /** The one request Grafana received; a call that sent none fails here instead of waiting forever. */
    private fun request(): RecordedRequest = requireNotNull(server.takeRequest(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS)) { "no request" }

    private companion object {
        const val REQUEST_WAIT_SECONDS = 5L
    }

    @Test
    fun `a Grafana whose health answers 200 is running`() {
        server.enqueue(MockResponse(code = 200, body = """{"database":"ok"}"""))

        assertThat(grafana.isRunning(server.port)).isTrue()
        assertThat(request().url.encodedPath).isEqualTo("/api/health")
    }

    @Test
    fun `a Grafana that answers health with an error is not running`() {
        server.enqueue(MockResponse(code = 503, body = "starting"))

        assertThat(grafana.isRunning(server.port)).isFalse()
    }

    @Test
    fun `nothing listening on the port is not running`() {
        val freePort = ServerSocket(0).use { it.localPort }

        assertThat(grafana.isRunning(freePort)).isFalse()
    }

    @Test
    fun `the datasource reload is a POST to the provisioning API as the server admin`() {
        server.enqueue(MockResponse(code = 200, body = """{"message":"Datasources config reloaded"}"""))

        grafana.reloadDatasources(server.port)

        val request = request()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api/admin/provisioning/datasources/reload")
        assertThat(request.headers["Authorization"]).isEqualTo(Credentials.basic("admin", "admin"))
    }

    @Test
    fun `the dashboard reload is a POST to the provisioning API as the server admin`() {
        server.enqueue(MockResponse(code = 200, body = """{"message":"Dashboards config reloaded"}"""))

        grafana.reloadDashboards(server.port)

        val request = request()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api/admin/provisioning/dashboards/reload")
        assertThat(request.headers["Authorization"]).isEqualTo(Credentials.basic("admin", "admin"))
    }

    @Test
    fun `a refused reload fails with Grafana's status and answer`() {
        server.enqueue(MockResponse(code = 403, body = """{"message":"Permission denied"}"""))

        assertThatThrownBy { grafana.reloadDashboards(server.port) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("403")
            .hasMessageContaining("Permission denied")
    }
}
