package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

/**
 * Test suite for K8sService.
 *
 * Proxy startup is now handled by [DefaultCommandExecutor] before any command runs,
 * so these tests focus on K8s operation behaviour without proxy concerns.
 * Full integration tests that apply manifests to a real cluster live in
 * [K8sServiceIntegrationTest].
 */
class K8sServiceTest : BaseKoinTest() {
    private lateinit var k8sService: K8sService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { K8sClientProvider(get(), ProxiedKubernetesClientFactory()) }
                factory<K8sService> { DefaultK8sService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        k8sService = getKoin().get()
    }

    @Test
    fun `applyManifests fails naming the manifest directory when it does not exist`() {
        writeWorkspaceKubeconfig()
        val nonExistentDir = tempDir.resolve("does-not-exist").toPath()

        val result = k8sService.applyManifests(nonExistentDir)

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No manifest files found at")
            .hasMessageContaining(nonExistentDir.toString())
    }

    @Test
    fun `applyManifests fails naming the workspace kubeconfig when it is missing`() {
        val manifestDir = createTestManifestDir()
        val expectedKubeconfig = File(context.workingDirectory, Constants.K3s.LOCAL_KUBECONFIG)

        val result = k8sService.applyManifests(manifestDir)

        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("kubeconfig not found")
            .hasMessageContaining(expectedKubeconfig.absolutePath)
    }

    // ========== HELPER METHODS ==========

    /**
     * A kubeconfig in the test workspace that names a server nothing listens on. Building a client
     * from it connects to nothing, so a test that stops before the first API call never waits.
     */
    private fun writeWorkspaceKubeconfig() {
        File(context.workingDirectory, Constants.K3s.LOCAL_KUBECONFIG).writeText(
            """
            apiVersion: v1
            kind: Config
            clusters:
            - name: test
              cluster:
                server: https://127.0.0.1:1
            contexts:
            - name: test
              context:
                cluster: test
                user: test
            current-context: test
            users:
            - name: test
              user:
                token: test
            """.trimIndent(),
        )
    }

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
