package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import com.rustyrazorblade.easydblab.services.HelmService
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import com.rustyrazorblade.easydblab.services.KitRuntime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration

/**
 * Tests `<kit> status`: the running-state line, and that a running kit lists its declared
 * endpoints at the private IP of every host of the endpoint's node type. The endpoint URL
 * formatting tests cover [KitEndpoint.formatUrl], which status output relies on.
 */
class KitStatusCommandTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val mockKubeService: KubernetesService = mock()
    private val mockHelmService: HelmService = mock()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<KubernetesService> { mockKubeService }
                single<HelmService> { mockHelmService }
            },
        )

    private fun host(
        alias: String,
        privateIp: String,
    ) = ClusterHost(
        publicIp = "3.4.5.6",
        privateIp = privateIp,
        alias = alias,
        availabilityZone = "us-west-2a",
        instanceId = "i-$alias",
    )

    @BeforeEach
    fun setupClusterState() {
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(host("control0", "10.0.0.1")),
                        ServerType.Cassandra to listOf(host("db0", "10.0.2.1"), host("db1", "10.0.2.2")),
                    ),
            ),
        )
    }

    private val runningPod =
        KubernetesPod(
            namespace = "default",
            name = "mydb-0",
            status = "Running",
            ready = "1/1",
            restarts = 0,
            age = Duration.ofMinutes(1),
        )

    private fun runStatus(
        pods: List<KubernetesPod>,
        runtime: KitRuntime? = KitRuntime(type = KitRuntime.RuntimeType.PODS, selector = "easydblab/kit=mydb"),
    ): String = runStatus(Result.success(pods), runtime)

    private fun runStatus(
        podQuery: Result<List<KubernetesPod>>,
        runtime: KitRuntime?,
    ): String {
        whenever(mockKubeService.listPodsByLabel(any(), any())).thenReturn(podQuery)
        val config =
            KitConfig(
                name = "mydb",
                runtime = runtime,
                endpoints =
                    listOf(
                        KitEndpoint(name = "bolt", nodeType = "db", port = 30687, type = KitEndpoint.EndpointType.NATIVE),
                    ),
            )
        val stdout = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(stdout))
        try {
            KitStatusCommand("mydb", config).execute()
        } finally {
            System.setOut(original)
        }
        return stdout.toString()
    }

    @Test
    fun `a running kit lists each endpoint at every host of its node type`() {
        val output = runStatus(listOf(runningPod))

        assertThat(output).contains("Running (1/1 pods ready)")
        assertThat(output).contains("10.0.2.1:30687", "10.0.2.2:30687")
    }

    @Test
    fun `a stopped kit lists no endpoints`() {
        val output = runStatus(emptyList())

        assertThat(output).contains("Stopped")
        assertThat(output).doesNotContain("30687")
    }

    @Test
    fun `status fills KIT_NAME into the runtime selector and queries the runtime namespace`() {
        runStatus(
            emptyList(),
            KitRuntime(type = KitRuntime.RuntimeType.STATEFULSET, selector = "app=\${KIT_NAME}-db", namespace = "data"),
        )

        verify(mockKubeService).listPodsByLabel("app=mydb-db", "data")
    }

    @Test
    fun `status looks a runtime without a selector up by app kubernetes io name`() {
        runStatus(emptyList(), KitRuntime(type = KitRuntime.RuntimeType.PODS))

        verify(mockKubeService).listPodsByLabel("app.kubernetes.io/name=mydb", "default")
    }

    @Test
    fun `status looks a kit without a runtime block up by app kubernetes io name`() {
        runStatus(emptyList(), runtime = null)

        verify(mockKubeService).listPodsByLabel("app.kubernetes.io/name=mydb", "default")
    }

    @Test
    fun `a failed pod query for a runtime-declared kit is Unknown with the cause, not Stopped`() {
        val output =
            runStatus(
                Result.failure(IllegalStateException("SOCKS tunnel down")),
                KitRuntime(type = KitRuntime.RuntimeType.PODS, selector = "easydblab/kit=mydb"),
            )

        assertThat(output).contains("Unknown (K8s query failed: SOCKS tunnel down)")
        assertThat(output).doesNotContain("Stopped")
    }

    @Test
    fun `a failed pod query for a kit without a runtime block is Unknown with the cause`() {
        val output = runStatus(Result.failure(IllegalStateException("SOCKS tunnel down")), runtime = null)

        assertThat(output).contains("Unknown (K8s query failed: SOCKS tunnel down)")
    }

    @Test
    fun `a failed pod query for an installed helm release is Unknown with the cause`() {
        whenever(mockHelmService.releaseExists(any(), any(), any())).thenReturn(true)

        val output =
            runStatus(
                Result.failure(IllegalStateException("SOCKS tunnel down")),
                KitRuntime(type = KitRuntime.RuntimeType.HELM, release = "mydb"),
            )

        assertThat(output).contains("Unknown (K8s query failed: SOCKS tunnel down)")
        assertThat(output).doesNotContain("Running")
    }

    private fun endpoint(
        type: KitEndpoint.EndpointType,
        port: Int = 8080,
        scheme: String = "",
        path: String = "",
    ) = KitEndpoint(
        name = "test",
        nodeType = "app",
        port = port,
        type = type,
        scheme = scheme,
        path = path,
    )

    @Test
    fun `http endpoint formats correctly`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080).formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("http://10.0.1.5:8080")
    }

    @Test
    fun `http endpoint includes path when present`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTP, port = 80, path = "/ui").formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("http://10.0.1.5:80/ui")
    }

    @Test
    fun `https endpoint formats correctly`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTPS, port = 443).formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("https://10.0.1.5:443")
    }

    @Test
    fun `jdbc endpoint uses scheme and path`() {
        val url = endpoint(KitEndpoint.EndpointType.JDBC, port = 8080, scheme = "presto", path = "/cassandra").formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("jdbc:presto://10.0.1.5:8080/cassandra")
    }

    @Test
    fun `native endpoint is ip colon port`() {
        val url = endpoint(KitEndpoint.EndpointType.NATIVE, port = 9000).formatUrl("10.0.2.3")
        assertThat(url).isEqualTo("10.0.2.3:9000")
    }

    @Test
    fun `cql endpoint is ip colon port`() {
        val url = endpoint(KitEndpoint.EndpointType.CQL, port = 9042).formatUrl("10.0.2.3")
        assertThat(url).isEqualTo("10.0.2.3:9042")
    }

    @Test
    fun `http endpoint with empty path omits trailing slash`() {
        val url = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080, path = "").formatUrl("10.0.1.5")
        assertThat(url).isEqualTo("http://10.0.1.5:8080")
        assertThat(url).doesNotEndWith("/")
    }

    @Test
    fun `multiple IPs produce distinct URLs`() {
        val ep = endpoint(KitEndpoint.EndpointType.HTTP, port = 8080)
        val urls = listOf("10.0.1.1", "10.0.1.2", "10.0.1.3").map { ip -> ep.formatUrl(ip) }
        assertThat(urls).containsExactly(
            "http://10.0.1.1:8080",
            "http://10.0.1.2:8080",
            "http://10.0.1.3:8080",
        )
    }
}
