package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.DefaultGrafanaDashboardService
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.K8sService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import java.io.File

class GrafanaInstallTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockWebServer: MockWebServer

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
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun `execute posts dashboard JSON with overwrite and folderId fields`() {
        val dashboardJson = """{"title": "My Dashboard", "panels": []}"""
        val dashboardFile = File(tempDir, "dashboard.json").also { it.writeText(dashboardJson) }

        mockWebServer.enqueue(MockResponse(code = 200, body = """{"status":"success"}"""))

        val command = GrafanaInstall()
        command.dashboardPath = dashboardFile.absolutePath
        command.execute()

        val request = mockWebServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/dashboards/db")
        assertThat(request.method).isEqualTo("POST")

        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
        assertThat(body["overwrite"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["folderId"]?.jsonPrimitive?.int).isEqualTo(0)
        assertThat(
            body["dashboard"]
                ?.jsonObject
                ?.get("title")
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("My Dashboard")
    }

    @Test
    fun `execute fails when dashboard file does not exist`() {
        val command = GrafanaInstall()
        command.dashboardPath = "/nonexistent/dashboard.json"

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Dashboard file not found")
    }

    @Test
    fun `execute fails when Grafana API returns non-2xx`() {
        val dashboardFile =
            File(tempDir, "dashboard.json").also { it.writeText("""{"title": "Test"}""") }

        mockWebServer.enqueue(MockResponse(code = 500, body = "Internal Server Error"))

        val command = GrafanaInstall()
        command.dashboardPath = dashboardFile.absolutePath

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("500")
    }
}
