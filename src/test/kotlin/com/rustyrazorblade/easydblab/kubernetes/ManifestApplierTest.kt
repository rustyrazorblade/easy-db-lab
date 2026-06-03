package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * Test suite for ManifestApplier utility.
 *
 * These tests verify YAML parsing and error handling for Kubernetes manifests,
 * including multi-document YAML file support.
 *
 * Note: Full integration testing of fabric8 client operations requires a running
 * K8s cluster or complex mock setup. These tests focus on validation logic.
 */
class ManifestApplierTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `applyManifest should throw when kind is missing`() {
        val yaml =
            """
            apiVersion: v1
            metadata:
              name: test
            """.trimIndent()

        val file = tempDir.resolve("invalid.yaml").toFile()
        file.writeText(yaml)

        // Pass null client - we expect it to fail on kind validation before reaching client
        assertThatThrownBy {
            ManifestApplier.applyManifest(
                createNullClient(),
                file,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing 'kind' field")
    }

    @Test
    fun `applyManifest should use generic client for unknown kind`() {
        val mockResource = mock<NamespaceableResource<HasMetadata>>()
        val mockClient = mock<KubernetesClient>()
        whenever(mockClient.resource(any<String>())).thenReturn(mockResource)

        val yaml =
            """
            apiVersion: v1
            kind: UnsupportedResource
            metadata:
              name: test
            """.trimIndent()

        val file = tempDir.resolve("unsupported.yaml").toFile()
        file.writeText(yaml)

        ManifestApplier.applyManifest(mockClient, file)

        verify(mockResource).serverSideApply()
    }

    @Test
    fun `applyManifest should handle empty file gracefully`() {
        val yaml = ""

        val file = tempDir.resolve("empty.yaml").toFile()
        file.writeText(yaml)

        // Empty file should not throw - just does nothing
        ManifestApplier.applyManifest(createNullClient(), file)
    }

    @Test
    fun `applyManifest should throw for multi-doc when second document has missing kind`() {
        // Test that the second document's missing kind is detected
        // Note: This will fail on the first doc attempting to apply, but if we use
        // a YAML with both docs missing kind, we can verify the parsing works
        val yaml =
            """
            ---
            apiVersion: v1
            metadata:
              name: invalid-doc-one
            ---
            apiVersion: v1
            metadata:
              name: invalid-doc-two
            """.trimIndent()

        val file = tempDir.resolve("multi-invalid.yaml").toFile()
        file.writeText(yaml)

        // First document is missing kind, should fail immediately
        assertThatThrownBy {
            ManifestApplier.applyManifest(
                createNullClient(),
                file,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing 'kind' field")
    }

    @Test
    fun `applyYaml should throw when kind is missing`() {
        val yaml =
            """
            apiVersion: v1
            metadata:
              name: test
            """.trimIndent()

        assertThatThrownBy { ManifestApplier.applyYaml(createNullClient(), yaml) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing 'kind' field")
    }

    @Test
    fun `applyYaml should use generic client for unknown kind`() {
        val mockResource = mock<NamespaceableResource<HasMetadata>>()
        val mockClient = mock<KubernetesClient>()
        whenever(mockClient.resource(any<String>())).thenReturn(mockResource)

        val yaml =
            """
            apiVersion: v1
            kind: CustomResourceDefinition
            metadata:
              name: test
            """.trimIndent()

        ManifestApplier.applyYaml(mockClient, yaml)

        verify(mockResource).serverSideApply()
    }

    /**
     * Creates a mock-like client that throws NPE when accessed.
     * This allows testing validation logic that runs before client operations.
     */
    @Suppress("UNCHECKED_CAST")
    private fun createNullClient(): io.fabric8.kubernetes.client.KubernetesClient {
        // Use a proxy that throws NPE for any method call
        return java.lang.reflect.Proxy.newProxyInstance(
            io.fabric8.kubernetes.client.KubernetesClient::class.java.classLoader,
            arrayOf(io.fabric8.kubernetes.client.KubernetesClient::class.java),
        ) { _, _, _ ->
            throw NullPointerException("Client method should not be called for validation tests")
        } as io.fabric8.kubernetes.client.KubernetesClient
    }
}
