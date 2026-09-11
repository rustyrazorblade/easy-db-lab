package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.DefaultGrafanaDashboardService
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.K8sService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Integration tests for [GrafanaAnnotate].
 *
 * These drive the real [DefaultGrafanaDashboardService] against a [MockWebServer] that stands in for
 * the control node's Grafana HTTP API. They verify the POST body the command sends for the default,
 * explicit-time-and-tags, and dashboard/panel-scope cases, the success event, and the non-zero-exit
 * behavior when the Grafana endpoint is unreachable.
 */
class GrafanaAnnotateTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockWebServer: MockWebServer
    private val capturedEvents = mutableListOf<EventEnvelope>()

    private val controlHost =
        ClusterHost(
            publicIp = "127.0.0.1",
            privateIp = "10.0.1.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single {
                    val interceptor =
                        Interceptor { chain ->
                            val original = chain.request()
                            val redirected =
                                original.url
                                    .newBuilder()
                                    .host(mockWebServer.hostName)
                                    .port(mockWebServer.port)
                                    .build()
                            chain.proceed(original.newBuilder().url(redirected).build())
                        }
                    OkHttpClient.Builder().addInterceptor(interceptor).build()
                }
                single<GrafanaDashboardService> {
                    DefaultGrafanaDashboardService(
                        k8sService = mock<K8sService>(),
                        manifestBuilder = mock<GrafanaManifestBuilder>(),
                        eventBus = get<EventBus>(),
                        okHttpClient = get<OkHttpClient>(),
                    )
                }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = mock()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        capturedEvents.clear()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    capturedEvents.add(envelope)
                }

                override fun close() = Unit
            },
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `annotate with default time posts text and current time and emits event`() {
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"id":42,"message":"Annotation added"}"""))

        val before = System.currentTimeMillis()
        val command = GrafanaAnnotate()
        command.text = "concurrent_reads 64->128"
        command.time = System.currentTimeMillis()
        command.execute()
        val after = System.currentTimeMillis()

        val request = mockWebServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/annotations")
        assertThat(request.method).isEqualTo("POST")

        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
        assertThat(body["text"]?.jsonPrimitive?.content).isEqualTo("concurrent_reads 64->128")
        val postedTime = body["time"]?.jsonPrimitive?.content?.toLong()
        assertThat(postedTime).isBetween(before, after)
        // Null-valued scope fields must be omitted from the wire payload.
        assertThat(body.keys).doesNotContain("timeEnd", "dashboardUID", "panelId")

        val created = capturedEvents.map { it.event }.filterIsInstance<Event.Grafana.AnnotationCreated>()
        assertThat(created).singleElement()
        assertThat(created.first().id).isEqualTo(42L)
    }

    @Test
    fun `annotate with explicit time and tags posts both plus the global tag`() {
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"id":7,"message":"Annotation added"}"""))

        val command = GrafanaAnnotate()
        command.text = "restart"
        command.time = 1767225600000L
        command.tags = listOf("ab-marker", "cassandra")
        command.execute()

        val body = Json.parseToJsonElement(requireNotNull(mockWebServer.takeRequest().body).utf8()).jsonObject
        assertThat(body["time"]?.jsonPrimitive?.content).isEqualTo("1767225600000")
        // This annotation is global (no dashboard/panel scope), so the fixed global tag is appended
        // to the operator's tags, preserving their order.
        val postedTags = body["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertThat(postedTags).containsExactly("ab-marker", "cassandra", Constants.Grafana.GLOBAL_ANNOTATION_TAG)
    }

    @Test
    fun `a global annotate auto-applies the global tag and de-dupes when the user passed it`() {
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"id":11,"message":"Annotation added"}"""))

        val command = GrafanaAnnotate()
        command.text = "global"
        command.time = 1767225600000L
        // The operator already passed the global tag; it must appear exactly once, not twice.
        command.tags = listOf(Constants.Grafana.GLOBAL_ANNOTATION_TAG, "cassandra")
        command.execute()

        val body = Json.parseToJsonElement(requireNotNull(mockWebServer.takeRequest().body).utf8()).jsonObject
        val postedTags = body["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertThat(postedTags).containsExactly(Constants.Grafana.GLOBAL_ANNOTATION_TAG, "cassandra")
        assertThat(postedTags?.count { it == Constants.Grafana.GLOBAL_ANNOTATION_TAG }).isEqualTo(1)
    }

    @Test
    fun `annotate with dashboard and panel scope posts the scope fields and does not auto-add the global tag`() {
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"id":9,"message":"Annotation added"}"""))

        val command = GrafanaAnnotate()
        command.text = "scoped"
        command.time = 1767225600000L
        command.tags = listOf("cassandra")
        command.dashboardUid = "abc123"
        command.panelId = 5
        command.execute()

        val body = Json.parseToJsonElement(requireNotNull(mockWebServer.takeRequest().body).utf8()).jsonObject
        assertThat(body["dashboardUID"]?.jsonPrimitive?.content).isEqualTo("abc123")
        assertThat(body["panelId"]?.jsonPrimitive?.content).isEqualTo("5")
        // A scoped annotation renders on its target dashboard, so the global tag is NOT auto-added;
        // only the operator's own tags are sent.
        val postedTags = body["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertThat(postedTags).containsExactly("cassandra")
        assertThat(postedTags).doesNotContain(Constants.Grafana.GLOBAL_ANNOTATION_TAG)
    }

    @Test
    fun `unreachable Grafana fails with a non-zero exit naming the endpoint`() {
        // Close the server so the connection is refused, simulating an unreachable Grafana.
        mockWebServer.close()

        val command = GrafanaAnnotate()
        command.text = "unreachable"
        command.time = System.currentTimeMillis()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("/api/annotations")

        val created = capturedEvents.map { it.event }.filterIsInstance<Event.Grafana.AnnotationCreated>()
        assertThat(created).isEmpty()
    }
}
