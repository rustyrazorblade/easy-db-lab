package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [KubeconfigProxyResolver]. Verifies that shell-step kubeconfig resolution adds a
 * SOCKS `proxy-url` only when a proxy port is published, never patches the workspace kubeconfig
 * in place, and never touches the JVM-global proxy properties.
 */
class KubeconfigProxyResolverTest {
    private val resolver = KubeconfigProxyResolver()

    private val readMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()

    @BeforeEach
    fun clearProxyProps() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    private fun writeWorkspaceKubeconfig(dir: File): File {
        val file = File(dir, Constants.K3s.LOCAL_KUBECONFIG)
        file.writeText(
            """
            apiVersion: v1
            kind: Config
            clusters:
              - name: default
                cluster:
                  server: https://10.0.0.1:6443
                  certificate-authority-data: LS0tLS1CRUdJTg==
            contexts:
              - name: default
                context:
                  cluster: default
                  user: default
            current-context: default
            users:
              - name: default
                user:
                  token: abc123
            """.trimIndent() + "\n",
        )
        return file
    }

    @Suppress("UNCHECKED_CAST")
    private fun clusterEntry(file: File): Map<String, Any> {
        val map = readMapper.readValue(file, Map::class.java) as Map<String, Any>
        val clusters = map["clusters"] as List<Map<String, Any>>
        return clusters.first()["cluster"] as Map<String, Any>
    }

    @Test
    fun `returns the workspace kubeconfig unchanged when no proxy port is published`(
        @TempDir dir: File,
    ) {
        val workspace = writeWorkspaceKubeconfig(dir)
        val originalBytes = workspace.readBytes()

        resolver.resolve(workspace).use { resolved ->
            assertThat(resolved.path).isEqualTo(workspace)
            assertThat(clusterEntry(resolved.path)).doesNotContainKey("proxy-url")
        }

        // Canonical workspace kubeconfig is byte-for-byte untouched.
        assertThat(workspace.readBytes()).isEqualTo(originalBytes)
    }

    @Test
    fun `writes a temp kubeconfig with a socks proxy-url on the cluster entry when a port is published`(
        @TempDir dir: File,
    ) {
        val workspace = writeWorkspaceKubeconfig(dir)
        val originalBytes = workspace.readBytes()
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        resolver.resolve(workspace).use { resolved ->
            // A derived temp copy is returned, not the workspace file itself.
            assertThat(resolved.path).isNotEqualTo(workspace)
            assertThat(resolved.path.name).startsWith("edl-kubeconfig-proxy-")

            val cluster = clusterEntry(resolved.path)
            assertThat(cluster["proxy-url"]).isEqualTo("socks5://127.0.0.1:1080")
            // The rest of the cluster entry is preserved.
            assertThat(cluster["server"]).isEqualTo("https://10.0.0.1:6443")
            assertThat(cluster["certificate-authority-data"]).isEqualTo("LS0tLS1CRUdJTg==")
        }

        // Canonical workspace kubeconfig is never patched in place (fabric8 reads it).
        assertThat(workspace.readBytes()).isEqualTo(originalBytes)
        assertThat(clusterEntry(workspace)).doesNotContainKey("proxy-url")
    }

    @Test
    fun `uses the published port value rather than a hard-coded default`(
        @TempDir dir: File,
    ) {
        val workspace = writeWorkspaceKubeconfig(dir)
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "34567")

        resolver.resolve(workspace).use { resolved ->
            assertThat(clusterEntry(resolved.path)["proxy-url"]).isEqualTo("socks5://127.0.0.1:34567")
        }
    }

    @Test
    fun `close deletes the temp kubeconfig`(
        @TempDir dir: File,
    ) {
        val workspace = writeWorkspaceKubeconfig(dir)
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        val resolved = resolver.resolve(workspace)
        assertThat(resolved.path).exists()
        resolved.close()
        assertThat(resolved.path).doesNotExist()
    }

    @Test
    fun `returns the workspace path unchanged when the kubeconfig does not exist even if a port is published`(
        @TempDir dir: File,
    ) {
        val missing = File(dir, Constants.K3s.LOCAL_KUBECONFIG)
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        resolver.resolve(missing).use { resolved ->
            assertThat(resolved.path).isEqualTo(missing)
        }
    }

    @Test
    fun `fails fast when a published port has no cluster entry to attach the proxy-url to`(
        @TempDir dir: File,
    ) {
        // A kubeconfig with no clusters list: returning a proxy-less copy would silently leave
        // kubectl unable to reach the private API, so resolution must fail loudly instead.
        val file = File(dir, Constants.K3s.LOCAL_KUBECONFIG)
        file.writeText(
            """
            apiVersion: v1
            kind: Config
            contexts: []
            """.trimIndent() + "\n",
        )
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        assertThatThrownBy { resolver.resolve(file) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no cluster entry")
    }

    @Test
    fun `never sets the jvm-global socks proxy properties`(
        @TempDir dir: File,
    ) {
        val workspace = writeWorkspaceKubeconfig(dir)
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "1080")

        resolver.resolve(workspace).use { }

        assertThat(System.getProperty("socksProxyHost")).isNull()
        assertThat(System.getProperty("socksProxyPort")).isNull()
    }
}
