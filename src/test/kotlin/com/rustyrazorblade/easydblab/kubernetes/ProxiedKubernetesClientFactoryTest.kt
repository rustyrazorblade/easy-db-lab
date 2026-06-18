package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File

@ResourceLock(Constants.Proxy.PORT_PROPERTY)
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
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @AfterEach
    fun restoreSystemProperties() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @Test
    fun `httpsProxy is null when the proxy port is not published (Tailscale or proxy not started)`() {
        val factory = ProxiedKubernetesClientFactory()
        factory.createClient(writeKubeconfig()).use { client ->
            assertThat(client.configuration.httpsProxy).isNull()
        }
    }

    @Test
    fun `httpsProxy is set to socks5 when the proxy port is published`() {
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")
        val factory = ProxiedKubernetesClientFactory()
        factory.createClient(writeKubeconfig()).use { client ->
            assertThat(client.configuration.httpsProxy).isEqualTo("socks5://127.0.0.1:1080")
        }
    }
}
