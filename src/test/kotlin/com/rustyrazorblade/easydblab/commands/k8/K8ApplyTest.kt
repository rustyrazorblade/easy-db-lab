package com.rustyrazorblade.easydblab.commands.k8

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpload
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path

/**
 * Test suite for K8Apply command following TDD principles.
 *
 * These tests verify K8s observability stack deployment including
 * manifest extraction and K8sService integration.
 */
class K8ApplyTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockGrafanaUpload: GrafanaUpload

    @TempDir
    lateinit var testWorkDir: File

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

                // Real BeylaManifestBuilder with real TemplateService
                single { BeylaManifestBuilder(get()) }

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
    fun `command has correct default options`() {
        val command = K8Apply()

        assertThat(command.timeoutSeconds).isEqualTo(120)
        assertThat(command.skipWait).isFalse()
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
    fun `execute should apply manifests successfully`() {
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
        whenever(mockK8sService.applyManifests(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))

        val command = K8Apply()

        // When
        command.execute()

        // Then
        verify(mockK8sService).applyManifests(any(), any<Path>())
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
        whenever(mockK8sService.applyManifests(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))

        val command = K8Apply()
        command.skipWait = true

        // When
        command.execute()

        // Then
        verify(mockK8sService).applyManifests(any(), any<Path>())
        // waitForPodsReady should not be called
    }

    @Test
    fun `execute should fail when applyManifests fails`() {
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
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.failure(RuntimeException("kubectl apply failed")))

        val command = K8Apply()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("kubectl apply failed")
    }
}
