package com.rustyrazorblade.easydblab.services.cassandraversions

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration

/** The plain client of the public version sources, against a real HTTP server. */
class PublicHttpIntegrationTest {
    private lateinit var server: MockWebServer
    private val http = PublicHttp(retryConfig = PublicHttp.retryConfig(Duration.ofMillis(1)))

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun url() = server.url("/index.html").toString()

    @Test
    fun `a 5xx is retried and the next answer is returned`() {
        server.enqueue(MockResponse(code = 503))
        server.enqueue(MockResponse(code = 200, body = "listing"))

        assertThat(http.getOrNull(url())).isEqualTo("listing")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a 404 is an answer, with no retry`() {
        server.enqueue(MockResponse(code = 404))

        assertThat(http.getOrNull(url())).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a 403 fails at once, naming the URL and the status`() {
        server.enqueue(MockResponse(code = 403))

        assertThatThrownBy { http.getOrNull(url()) }
            .isInstanceOf(HttpStatusException::class.java)
            .hasMessageContaining("/index.html", "403")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a 5xx on every attempt fails after three`() {
        repeat(3) { server.enqueue(MockResponse(code = 500)) }

        assertThatThrownBy { http.getOrNull(url()) }.isInstanceOf(TransientHttpStatusException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a refused connection is an IOException after the retries`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertThatThrownBy { http.getOrNull("http://127.0.0.1:$closedPort/") }.isInstanceOf(IOException::class.java)
    }
}
