package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.sidecar.SidecarManifestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for DefaultSidecarService — K3s DaemonSet lifecycle operations.
 */
class SidecarServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var sidecarService: SidecarService

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.10",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = null,
            clusterId = "test-cluster-id",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mockK8sService }
                single { mockClusterStateManager }
                single { TemplateService(get(), get()) }
                factoryOf(::SidecarManifestBuilder)
                factory<SidecarService> {
                    DefaultSidecarService(get(), get(), get())
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = mock()
        mockClusterStateManager = mock()
        sidecarService = getKoin().get()

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockK8sService.applyResource(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.deleteResourcesByLabel(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any()))
            .thenReturn(Result.success(Unit))
    }

    @Test
    fun `deploy applies all resources from manifest builder`() {
        val result = sidecarService.deploy(testControlHost, "ghcr.io/apache/cassandra-sidecar:latest")

        assertThat(result.isSuccess).isTrue()
        // ConfigMap + DaemonSet = 2 resources
        verify(mockK8sService, times(2)).applyResource(any(), any())
    }

    @Test
    fun `deploy returns failure when k8s apply fails`() {
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.failure(RuntimeException("K8s unreachable")))

        val result = sidecarService.deploy(testControlHost, "ghcr.io/apache/cassandra-sidecar:latest")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("K8s unreachable")
    }

    @Test
    fun `undeploy deletes daemonset resources by label`() {
        val result = sidecarService.undeploy(testControlHost)

        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).deleteResourcesByLabel(any(), any(), any(), any())
    }

    @Test
    fun `undeploy returns failure when k8s delete fails`() {
        whenever(mockK8sService.deleteResourcesByLabel(any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Delete failed")))

        val result = sidecarService.undeploy(testControlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Delete failed")
    }

    @Test
    fun `restart triggers daemonset rolling restart`() {
        val result = sidecarService.restart(testControlHost)

        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).rolloutRestartDaemonSet(any(), any(), any())
    }

    @Test
    fun `restart returns failure when k8s rollout fails`() {
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Rollout failed")))

        val result = sidecarService.restart(testControlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("Rollout failed")
    }
}
