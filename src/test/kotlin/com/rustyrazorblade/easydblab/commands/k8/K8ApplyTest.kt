package com.rustyrazorblade.easydblab.commands.k8

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpload
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.K8sService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for K8Apply command.
 *
 * K8Apply delegates to GrafanaUpload for deploying all observability resources,
 * then handles pod readiness waiting and access info display.
 */
class K8ApplyTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockGrafanaUpload: GrafanaUpload

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

                single {
                    mock<GrafanaUpload>().also {
                        mockGrafanaUpload = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockClusterStateManager = getKoin().get()
        mockGrafanaUpload = getKoin().get()
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

        val command = K8Apply()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute should delegate to grafanaUpload and wait for pods`() {
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
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))

        val command = K8Apply()
        command.execute()

        verify(mockGrafanaUpload).execute()
        verify(mockK8sService).waitForPodsReady(any(), any())
    }

    @Test
    fun `execute should skip waiting when skipWait is true`() {
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

        val command = K8Apply()
        command.skipWait = true
        command.execute()

        verify(mockGrafanaUpload).execute()
        verify(mockK8sService, never()).waitForPodsReady(any(), any())
    }
}
