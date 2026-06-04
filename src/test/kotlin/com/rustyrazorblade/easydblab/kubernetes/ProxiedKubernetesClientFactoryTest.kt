package com.rustyrazorblade.easydblab.kubernetes

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProxiedKubernetesClientFactoryTest {
    @TempDir
    lateinit var tempDir: File

    private val minimalKubeconfig =
        """
        apiVersion: v1
        clusters:
        - cluster:
            server: https://10.0.1.5:6443
            insecure-skip-tls-verify: true
          name: test-cluster
        contexts:
        - context:
            cluster: test-cluster
            user: admin
          name: test-context
        current-context: test-context
        kind: Config
        preferences: {}
        users:
        - name: admin
          user:
            token: fake-token-for-testing
        """.trimIndent()

    private fun writeKubeconfig(): java.nio.file.Path {
        val file = File(tempDir, "kubeconfig")
        file.writeText(minimalKubeconfig)
        return file.toPath()
    }

    @BeforeEach
    fun clearSystemProperties() {
        System.clearProperty("socksProxyPort")
    }

    @AfterEach
    fun restoreSystemProperties() {
        System.clearProperty("socksProxyPort")
    }

    @Test
    fun `httpsProxy is null when socksProxyPort system property is not set`() {
        val factory = ProxiedKubernetesClientFactory()
        factory.createClient(writeKubeconfig()).use { client ->
            assertThat(client.configuration.httpsProxy).isNull()
        }
    }

    @Test
    fun `httpsProxy is set to socks5 when socksProxyPort system property is present`() {
        System.setProperty("socksProxyPort", "1080")
        val factory = ProxiedKubernetesClientFactory()
        factory.createClient(writeKubeconfig()).use { client ->
            assertThat(client.configuration.httpsProxy).isEqualTo("socks5://127.0.0.1:1080")
        }
    }
}
