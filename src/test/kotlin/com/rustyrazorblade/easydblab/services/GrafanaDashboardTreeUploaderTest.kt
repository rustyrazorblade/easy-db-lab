package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.TestDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter.Companion.PYROSCOPE_URL_PLACEHOLDER
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for [DefaultGrafanaDashboardTreeUploader]: the local tree handed to
 * [RemoteOperationsService.replaceDirectory] and its cleanup.
 *
 * [RemoteOperationsService] is mocked because the swap runs on the control node over SSH; the
 * swap itself is `DefaultRemoteOperationsService`'s contract and is proven in its own test. The
 * tree's layout is the writer's contract and is proven in `GrafanaDashboardTreeWriterTest`.
 */
class GrafanaDashboardTreeUploaderTest : BaseKoinTest() {
    private val catalog = TestDashboardCatalog.catalog

    private lateinit var mockRemoteOps: RemoteOperationsService

    private val controlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )
    private val host = controlHost.toHost()
    private val hostPath = GrafanaManifestBuilder.GRAFANA_DASHBOARD_HOST_PATH
    private val grafanaUid = GrafanaManifestBuilder.GRAFANA_UID

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setup() {
        mockRemoteOps = getKoin().get()
    }

    private fun uploader() = DefaultGrafanaDashboardTreeUploader(GrafanaDashboardTreeWriter(catalog), mockRemoteOps, EventBus())

    /** Captures the local tree at the moment it is handed over; it is deleted once the call returns. */
    private fun captureUploadedTree(): Map<String, String> {
        val files = mutableMapOf<String, String>()
        whenever(mockRemoteOps.replaceDirectory(eq(host), any(), eq(hostPath), eq("$grafanaUid:$grafanaUid")))
            .doAnswer { invocation ->
                val root = invocation.getArgument<File>(1)
                root.walkTopDown().filter { it.isFile }.forEach { files[it.relativeTo(root).path] = it.readText() }
                Unit
            }
        return files
    }

    @Test
    fun `hands the whole dashboard tree to the Grafana hostPath as the Grafana user`() {
        val uploaded = captureUploadedTree()

        uploader().upload(controlHost)

        assertThat(uploaded.keys).containsExactlyInAnyOrderElementsOf(catalog.dashboards.map { it.relativePath })
    }

    @Test
    fun `the uploaded profiling dashboard carries the control node's Pyroscope URL`() {
        val profiling =
            catalog.dashboards.single {
                checkNotNull(javaClass.getResource(catalog.resourcePathOf(it))).readText().contains(PYROSCOPE_URL_PLACEHOLDER)
            }
        val uploaded = captureUploadedTree()

        uploader().upload(controlHost)

        assertThat(uploaded[profiling.relativePath]).contains(pyroscopeIngestBaseUrl("10.0.1.5"))
    }

    @Test
    fun `the local tree is removed whether the upload succeeds or fails`() {
        var localTree: File? = null
        whenever(mockRemoteOps.replaceDirectory(eq(host), any(), eq(hostPath), any())).doAnswer { invocation ->
            localTree = invocation.getArgument<File>(1)
            error("sftp failed")
        }

        assertThatThrownBy { uploader().upload(controlHost) }.hasMessage("sftp failed")

        assertThat(localTree).isNotNull().doesNotExist()
    }
}
