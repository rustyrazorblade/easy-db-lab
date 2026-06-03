package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class ProxiedKubernetesClientFactoryTest {
    @TempDir
    lateinit var tempDir: File

    private val socksProxyService: SocksProxyService =
        mock<SocksProxyService>().also {
            whenever(it.getLocalPort()).thenReturn(1080)
        }

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

    @Test
    fun `httpsProxy is null on client config when tailscaleActive is true`() {
        val factory =
            ProxiedKubernetesClientFactory(
                proxyHost = "127.0.0.1",
                socksProxyService = socksProxyService,
                tailscaleActive = true,
            )

        factory.createClient(writeKubeconfig()).use { client ->
            assertThat(client.configuration.httpsProxy).isNull()
        }
    }

    @Test
    fun `httpsProxy is set on client config when tailscaleActive is false`() {
        val factory =
            ProxiedKubernetesClientFactory(
                proxyHost = "127.0.0.1",
                socksProxyService = socksProxyService,
                tailscaleActive = false,
            )

        factory.createClient(writeKubeconfig()).use { client ->
            assertThat(client.configuration.httpsProxy).isEqualTo("socks5://127.0.0.1:1080")
        }
    }
}
