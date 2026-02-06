package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.K8sService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for ClickHouseGenerateDashboards command.
 *
 * These tests verify dashboard extraction from JAR resources and
 * application to K8s cluster via K8sService.
 */
class ClickHouseGenerateDashboardsTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClusterStateManager: ClusterStateManager

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
                    mock<K8sService>().also {
                        mockK8sService = it
                    }
                }

                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockClusterStateManager = getKoin().get()
    }

    @Test
    fun `execute should fail when no control nodes exist`() {
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = ClickHouseGenerateDashboards()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute should extract dashboard files and apply them`() {
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
        whenever(mockK8sService.applyManifests(any(), any())).thenReturn(Result.success(Unit))

        val command = ClickHouseGenerateDashboards()
        command.execute()

        // Verify applyManifests was called for each dashboard file (2 dashboards)
        verify(mockK8sService, times(2)).applyManifests(any(), any())
    }

    @Test
    fun `extractDashboardResources should only extract dashboard files`() {
        val command = ClickHouseGenerateDashboards()
        val dashboardFiles = command.extractDashboardResources()

        // Should find dashboard files
        assertThat(dashboardFiles).isNotEmpty()

        // All extracted files should contain 'grafana-dashboard' in the name
        assertThat(dashboardFiles).allSatisfy { file ->
            assertThat(file.name).contains("grafana-dashboard")
        }

        // Should not include non-dashboard clickhouse manifests
        assertThat(dashboardFiles).noneSatisfy { file ->
            assertThat(file.name).doesNotContain("grafana-dashboard")
        }
    }

    @Test
    fun `execute should fail when applyManifests fails`() {
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
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.failure(RuntimeException("Failed to apply manifest")))

        val command = ClickHouseGenerateDashboards()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to apply")
    }
}
