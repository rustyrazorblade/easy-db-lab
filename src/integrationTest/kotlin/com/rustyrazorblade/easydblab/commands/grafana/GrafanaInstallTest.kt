package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.DashboardInstallContextFactory
import com.rustyrazorblade.easydblab.services.DefaultGrafanaClient
import com.rustyrazorblade.easydblab.services.GrafanaClient
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.TenantDirectory
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
            s3Bucket = "acct-bucket",
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
                single<GrafanaClient> {
                    DefaultGrafanaClient(
                        eventBus = get<EventBus>(),
                        okHttpClient = get<OkHttpClient>(),
                    )
                }
                single {
                    val objectStore = mock<ObjectStore>()
                    whenever(objectStore.listFiles(any(), eq(false), any()))
                        .thenReturn(listOf(ObjectStore.FileInfo(ClusterS3Path.fromKey("acct-bucket", "mimir/acme/"), 0, "")))
                    DashboardInstallContextFactory(TenantDirectory(objectStore))
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
    fun `execute posts dashboard JSON with overwrite and folderUid fields`() {
        val dashboardJson = """{"title": "My Dashboard", "panels": []}"""
        val dashboardFile = File(tempDir, "dashboard.json").also { it.writeText(dashboardJson) }

        mockWebServer.enqueue(MockResponse(code = 200, body = "[]"))
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"id":1,"uid":"abc123","title":"General"}"""))
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"status":"success"}"""))

        val command = GrafanaInstall()
        command.dashboardPath = dashboardFile.absolutePath
        command.execute()

        mockWebServer.takeRequest() // GET /api/folders
        mockWebServer.takeRequest() // POST /api/folders (create)
        val request = mockWebServer.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/dashboards/db")
        assertThat(request.method).isEqualTo("POST")

        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
        assertThat(body["overwrite"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["folderUid"]?.jsonPrimitive?.content).isEqualTo("abc123")
        assertThat(
            body["dashboard"]
                ?.jsonObject
                ?.get("title")
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("My Dashboard")
    }

    @Test
    fun `execute applies the install-time pass for the workspace cluster`() {
        val dashboardJson =
            """
            {"title": "T", "panels": [], "templating": {"list": [
              {"name": "metrics_datasource", "type": "datasource", "query": "prometheus", "current": {}},
              {"name": "cluster", "type": "query", "multi": true, "current": {}},
              {"name": "doc_tenant", "type": "custom", "query": "", "options": []}
            ]}}
            """.trimIndent()
        val dashboardFile = File(tempDir, "dashboard.json").also { it.writeText(dashboardJson) }
        mockWebServer.enqueue(MockResponse(code = 200, body = """[{"uid":"f1","title":"General"}]"""))
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"status":"success"}"""))

        val command = GrafanaInstall()
        command.dashboardPath = dashboardFile.absolutePath
        command.execute()

        mockWebServer.takeRequest() // GET /api/folders
        val body = Json.parseToJsonElement(requireNotNull(mockWebServer.takeRequest().body).utf8()).jsonObject
        val variables =
            body
                .getValue("dashboard")
                .jsonObject
                .getValue("templating")
                .jsonObject
                .getValue("list")
                .jsonArray
                .associate {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content to it.jsonObject.getValue("current").jsonObject["value"]
                }
        assertThat(variables["metrics_datasource"]?.jsonPrimitive?.content).isEqualTo("mimir")
        assertThat(variables["cluster"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly(testClusterState.clusterLabelName())
        assertThat(variables["doc_tenant"]?.jsonPrimitive?.content).isEqualTo("default")
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

        mockWebServer.enqueue(MockResponse(code = 200, body = "[]"))
        mockWebServer.enqueue(MockResponse(code = 200, body = """{"id":1,"uid":"abc123","title":"General"}"""))
        mockWebServer.enqueue(MockResponse(code = 500, body = "Internal Server Error"))

        val command = GrafanaInstall()
        command.dashboardPath = dashboardFile.absolutePath

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("500")
    }
}
