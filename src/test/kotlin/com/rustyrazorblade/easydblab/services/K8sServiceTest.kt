package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for K8sService following TDD principles.
 *
 * These tests verify K8s operations (apply manifests, get status, delete, wait for pods)
 * using mocked SOCKS proxy service. Note: Full integration testing of fabric8 client
 * would require a running K8s cluster or mock server.
 */
class K8sServiceTest : BaseKoinTest() {
    private lateinit var mockSocksProxyService: SocksProxyService
    private lateinit var mockTelemetryProvider: TelemetryProvider
    private lateinit var k8sService: K8sService

    private val testClusterHost =
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
                single<SocksProxyService> { mockSocksProxyService }
                single<TelemetryProvider> { mockTelemetryProvider }
                factory<K8sService> { DefaultK8sService(get(), get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockSocksProxyService = mock()
        mockTelemetryProvider = mock()

        // Mock withSpan to just execute the block
        whenever(mockTelemetryProvider.withSpan<Any>(any(), any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val block = invocation.getArgument<() -> Any>(2)
            block()
        }

        k8sService = getKoin().get()
    }

    // ========== SOCKS PROXY TESTS ==========

    @Test
    fun `applyManifests should always use SOCKS proxy`() {
        // Given
        val manifestDir = createTestManifestDir()
        whenever(mockSocksProxyService.getLocalPort()).thenReturn(1080)

        // When - This will fail because there's no real kubeconfig, but we can verify SOCKS proxy was started
        val result = k8sService.applyManifests(testClusterHost, manifestDir)

        // Then - Verify SOCKS proxy service was called
        verify(mockSocksProxyService).ensureRunning(testClusterHost)
    }

    @Test
    fun `applyManifests should return failure when SOCKS proxy fails`() {
        // Given
        val manifestDir = createTestManifestDir()
        whenever(mockSocksProxyService.ensureRunning(any()))
            .thenThrow(RuntimeException("Failed to establish SSH connection"))

        // When
        val result = k8sService.applyManifests(testClusterHost, manifestDir)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("SSH connection")
    }

    @Test
    fun `getObservabilityStatus should always use SOCKS proxy`() {
        // Given
        whenever(mockSocksProxyService.getLocalPort()).thenReturn(1080)

        // When - This will fail because there's no real kubeconfig
        val result = k8sService.getObservabilityStatus(testClusterHost)

        // Then - Verify SOCKS proxy service was called
        verify(mockSocksProxyService).ensureRunning(testClusterHost)
    }

    @Test
    fun `getObservabilityStatus should return failure when SOCKS proxy fails`() {
        // Given
        whenever(mockSocksProxyService.ensureRunning(any()))
            .thenThrow(RuntimeException("Connection refused"))

        // When
        val result = k8sService.getObservabilityStatus(testClusterHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection refused")
    }

    @Test
    fun `deleteObservability should always use SOCKS proxy`() {
        // Given
        whenever(mockSocksProxyService.getLocalPort()).thenReturn(1080)

        // When
        val result = k8sService.deleteObservability(testClusterHost)

        // Then - Verify SOCKS proxy service was called
        verify(mockSocksProxyService).ensureRunning(testClusterHost)
    }

    @Test
    fun `deleteObservability should return failure when SOCKS proxy fails`() {
        // Given
        whenever(mockSocksProxyService.ensureRunning(any()))
            .thenThrow(RuntimeException("Permission denied"))

        // When
        val result = k8sService.deleteObservability(testClusterHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Permission denied")
    }

    @Test
    fun `waitForPodsReady should always use SOCKS proxy`() {
        // Given
        whenever(mockSocksProxyService.getLocalPort()).thenReturn(1080)

        // When
        val result = k8sService.waitForPodsReady(testClusterHost, 60)

        // Then - Verify SOCKS proxy service was called
        verify(mockSocksProxyService).ensureRunning(testClusterHost)
    }

    @Test
    fun `waitForPodsReady should return failure when SOCKS proxy fails`() {
        // Given
        whenever(mockSocksProxyService.ensureRunning(any()))
            .thenThrow(RuntimeException("Network unreachable"))

        // When
        val result = k8sService.waitForPodsReady(testClusterHost, 60)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Network unreachable")
    }

    // ========== HELPER METHODS ==========

    private fun createTestManifestDir(): java.nio.file.Path {
        val manifestDir = tempDir.resolve("k8s")
        manifestDir.mkdirs()

        // Create a simple test namespace manifest
        val namespaceFile = java.io.File(manifestDir, "namespace.yaml")
        namespaceFile.writeText(
            """
            apiVersion: v1
            kind: Namespace
            metadata:
              name: observability
            """.trimIndent(),
        )

        return manifestDir.toPath()
    }
}
