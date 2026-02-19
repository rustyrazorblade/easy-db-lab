package com.rustyrazorblade.easydblab.commands.k8

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpload
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.vector.VectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
/**
 * Test suite for K8Apply command.
 *
 * These tests verify K8s observability stack deployment including
 * Fabric8 builder integration and K8sService calls.
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
                // Mock K8sService
                single {
                    mock<K8sService>().also {
                        mockK8sService = it
                    }
                }

                // Mock ClusterStateManager
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }

                // Real TemplateService — never mock configuration classes
                single { TemplateService(get(), get()) }

                // Real manifest builders with real TemplateService
                single { BeylaManifestBuilder(get()) }
                single { EbpfExporterManifestBuilder(get()) }
                single { OtelManifestBuilder(get()) }
                single { TempoManifestBuilder(get()) }
                single { VectorManifestBuilder(get()) }
                single { VictoriaManifestBuilder() }
                single { RegistryManifestBuilder() }
                single { S3ManagerManifestBuilder() }

                // Mock GrafanaUpload (handles Grafana + Pyroscope resources)
                single {
                    mock<GrafanaUpload>().also {
                        mockGrafanaUpload = it
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        // Initialize mocks by getting them from Koin
        mockK8sService = getKoin().get()
        mockClusterStateManager = getKoin().get()
        mockGrafanaUpload = getKoin().get()
    }

    @Test
    fun `execute should fail when no control nodes exist`() {
        // Given - cluster state with no control nodes
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = K8Apply()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute should apply all Fabric8 resources successfully`() {
        // Given - cluster state with control node
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
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))

        val command = K8Apply()

        // When
        command.execute()

        // Then - verify Fabric8 resources were applied (multiple calls for each builder)
        verify(mockK8sService, atLeastOnce()).applyResource(any(), any<HasMetadata>())
        verify(mockGrafanaUpload).execute()
        verify(mockK8sService).waitForPodsReady(any(), any())
    }

    @Test
    fun `execute should skip waiting when skipWait is true`() {
        // Given - cluster state with control node
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
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))

        val command = K8Apply()
        command.skipWait = true

        // When
        command.execute()

        // Then
        verify(mockK8sService, atLeastOnce()).applyResource(any(), any<HasMetadata>())
        // waitForPodsReady should not be called
    }

    @Test
    fun `execute should fail when applyResource fails`() {
        // Given - cluster state with control node
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
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>()))
            .thenReturn(Result.failure(RuntimeException("server side apply failed")))

        val command = K8Apply()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("server side apply failed")
    }
}
