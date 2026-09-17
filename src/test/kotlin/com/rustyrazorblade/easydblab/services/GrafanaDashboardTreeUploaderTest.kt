package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for [DefaultGrafanaDashboardTreeUploader]: the staging, upload, and install sequence
 * that puts the dashboard tree on the control node's Grafana hostPath.
 *
 * [RemoteOperationsService] is mocked because the commands run on the control node over SSH;
 * the commands themselves are the contract and are asserted verbatim.
 */
class GrafanaDashboardTreeUploaderTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private val catalog = GrafanaDashboardCatalog.discover()

    private val controlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )
    private val host = controlHost.toHost()
    private val staging = "/home/ubuntu/easy-db-lab-grafana-dashboards.k3Xq9z"
    private val hostPath = GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setup() {
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(Response(""))
        whenever(mockRemoteOps.executeRemotely(eq(host), argThat { startsWith("mktemp -d") }, any(), any()))
            .thenReturn(Response("$staging\n"))
    }

    private fun uploader() = DefaultGrafanaDashboardTreeUploader(GrafanaDashboardTreeWriter(catalog), mockRemoteOps, EventBus())

    @Test
    fun `stages under the SSH user's home, uploads, then replaces the hostPath tree as the Grafana user`() {
        uploader().upload(controlHost)

        val commands = argumentCaptor<String>()
        val order = inOrder(mockRemoteOps)
        order.verify(mockRemoteOps).executeRemotely(eq(host), commands.capture(), any(), any())
        order.verify(mockRemoteOps).uploadDirectory(eq(host), any(), eq(staging))
        order.verify(mockRemoteOps).executeRemotely(eq(host), commands.capture(), any(), any())

        assertThat(commands.firstValue).isEqualTo("mktemp -d \"\$HOME/easy-db-lab-grafana-dashboards.XXXXXX\"")
        assertThat(commands.secondValue).isEqualTo(
            "sudo rm -rf $hostPath && sudo mv $staging $hostPath && " +
                "sudo chown -R ${GrafanaManifestBuilder.GRAFANA_UID}:${GrafanaManifestBuilder.GRAFANA_UID} $hostPath",
        )
    }

    @Test
    fun `uploads the whole catalog laid out as folder slash file with the control node's Pyroscope URL`() {
        val uploaded = mutableSetOf<String>()
        var profilingJson = ""
        whenever(mockRemoteOps.uploadDirectory(eq(host), any<File>(), any())).doAnswer { invocation ->
            val dir = invocation.getArgument<File>(1)
            dir.walkTopDown().filter { it.isFile }.forEach { uploaded += it.relativeTo(dir).path }
            profilingJson = File(dir, "observability/profiling.json").readText()
            Unit
        }

        uploader().upload(controlHost)

        assertThat(uploaded).containsExactlyInAnyOrderElementsOf(catalog.dashboards.map { it.relativePath })
        assertThat(profilingJson).contains("http://10.0.1.5:4040")
    }

    @Test
    fun `removes the local staging directory even when the upload fails`() {
        var stagedDir: File? = null
        whenever(mockRemoteOps.uploadDirectory(eq(host), any<File>(), any())).doAnswer { invocation ->
            stagedDir = invocation.getArgument<File>(1)
            throw IllegalStateException("sftp failed")
        }

        assertThatThrownBy { uploader().upload(controlHost) }.hasMessage("sftp failed")

        assertThat(stagedDir).isNotNull
        assertThat(stagedDir!!).doesNotExist()
        verify(mockRemoteOps, org.mockito.kotlin.never())
            .executeRemotely(eq(host), argThat { startsWith("sudo rm -rf") }, any(), any())
    }
}
