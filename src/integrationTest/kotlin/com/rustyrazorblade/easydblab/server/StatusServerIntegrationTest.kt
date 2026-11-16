package com.rustyrazorblade.easydblab.server

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.get
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class StatusServerIntegrationTest : BaseKoinTest() {
    private val events = mutableListOf<Event>()
    private val http = HttpClient.newHttpClient()
    private var refreshes = 0
    private lateinit var server: RunningStatusServer

    private val status =
        object : CachedStatus {
            override fun getStatus(section: String?): String? =
                when (section) {
                    null -> """{"cluster":"lab"}"""
                    "stressJobs" -> """{"stressJobs":[]}"""
                    else -> throw IllegalArgumentException("Invalid section '$section'")
                }

            override fun forceRefresh() {
                refreshes++
            }
        }

    @BeforeEach
    fun start() {
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        server = StatusServer(status).start(port = 0, bind = "127.0.0.1")
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `serves the cached status, one section, and the stress status`() {
        assertThat(get("/status").body()).isEqualTo("""{"cluster":"lab"}""")
        assertThat(get("/stress/status").body()).isEqualTo("""{"stressJobs":[]}""")
        assertThat(get("/status?section=bogus").statusCode()).isEqualTo(400)
    }

    @Test
    fun `a live request refreshes the status first`() {
        get("/status?live=true")

        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `serves the API docs`() {
        assertThat(get("/swagger").statusCode()).isEqualTo(200)
    }

    @Test
    fun `serves no MCP endpoint`() {
        assertThat(get("/sse").statusCode()).isEqualTo(404)
        assertThat(get("/message").statusCode()).isEqualTo(404)
    }

    @Test
    fun `the ready event names only the status and swagger URLs`() {
        val ready = events.filterIsInstance<Event.Server.Ready>().single()

        assertThat(ready.statusUrl).isEqualTo("http://127.0.0.1:${server.port}/status")
        assertThat(ready.swaggerUrl).isEqualTo("http://127.0.0.1:${server.port}/swagger")
        assertThat(ready.toDisplayString()).doesNotContain("/sse")
    }
}
