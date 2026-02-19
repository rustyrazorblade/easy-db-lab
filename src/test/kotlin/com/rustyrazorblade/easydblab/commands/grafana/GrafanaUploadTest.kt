package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.K8sService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for GrafanaUpload command.
 */
class GrafanaUploadTest : BaseKoinTest() {
    private lateinit var mockDashboardService: GrafanaDashboardService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockPyroscopeBuilder: PyroscopeManifestBuilder
    private lateinit var mockK8sService: K8sService

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<GrafanaDashboardService>().also {
                        mockDashboardService = it
                    }
                }

                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }

                single {
                    mock<PyroscopeManifestBuilder>().also {
                        mockPyroscopeBuilder = it
                    }
                }

                single {
                    mock<K8sService>().also {
                        mockK8sService = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockDashboardService = getKoin().get()
        mockClusterStateManager = getKoin().get()
        mockPyroscopeBuilder = getKoin().get()
        mockK8sService = getKoin().get()

        // Default: pyroscope manifest builder returns empty list
        whenever(mockPyroscopeBuilder.buildAllResources()).thenReturn(emptyList())
    }

    @Test
    fun `execute fails when no control nodes exist`() {
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = GrafanaUpload()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute calls uploadDashboards on service`() {
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockDashboardService.uploadDashboards(any(), any())).thenReturn(Result.success(Unit))

        val command = GrafanaUpload()
        command.execute()

        verify(mockDashboardService).uploadDashboards(any(), any())
    }

    @Test
    fun `execute fails when uploadDashboards fails`() {
        val stateWithControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControl)
        whenever(mockDashboardService.uploadDashboards(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Upload failed")))

        val command = GrafanaUpload()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to upload dashboards")
    }
}
