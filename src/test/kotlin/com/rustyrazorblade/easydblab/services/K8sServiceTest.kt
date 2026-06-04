package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Test suite for K8sService.
 *
 * Proxy startup is now handled by [DefaultCommandExecutor] before any command runs,
 * so these tests focus on K8s operation behaviour without proxy concerns.
 * Full integration tests that apply manifests to a real cluster live in
 * [K8sServiceIntegrationTest].
 */
class K8sServiceTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
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
                single<ClusterStateManager> { mockClusterStateManager }
                single { K8sClientProvider(get()) }
                factory<K8sService> { DefaultK8sService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        whenever(mockClusterStateManager.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))

        k8sService = getKoin().get()
    }

    @Test
    fun `applyManifests returns failure when manifest directory does not exist`() {
        val nonExistentDir = tempDir.resolve("does-not-exist").toPath()

        val result = k8sService.applyManifests(testClusterHost, nonExistentDir)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `applyManifests returns failure when kubeconfig is missing`() {
        val manifestDir = createTestManifestDir()

        val result = k8sService.applyManifests(testClusterHost, manifestDir)

        assertThat(result.isFailure).isTrue()
    }

    // ========== HELPER METHODS ==========

    private fun createTestManifestDir(): java.nio.file.Path {
        val manifestDir = tempDir.resolve("k8s")
        manifestDir.mkdirs()

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
